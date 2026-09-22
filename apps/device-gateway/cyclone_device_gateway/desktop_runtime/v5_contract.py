from __future__ import annotations

import re
import secrets
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode

V5_CONTRACT_PROTOCOL = "cyclone.v5.run1.contract.v1"
V5_OPS = frozenset({"atlas.places", "atlas.get", "secrets.slots", "secrets.request"})
PERSONAS = frozenset({"live", "mapping"})
FORBIDDEN_SECRET_KEYS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
    "authorization", "cookie", "cvv", "credential", "typed_text", "typed_value",
})
FORBIDDEN_SECRET_TOKENS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "apikey",
    "authorization", "cookie", "cvv", "credential", "credentials", "typedtext", "typedvalue",
})
INLINE_SECRET = re.compile(
    r"(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))\s*[:=]"
)
PACKAGE_PLACE = re.compile(r"^package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
CHROME_PLACE = re.compile(r"^chrome:https?://[^\s/]+(?::[0-9]{1,5})?$")
SLOT = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")
REASON = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")


def _normalized_key(value: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value).lower().replace("-", "_").replace(" ", "_")


def _secret_name(value: str) -> bool:
    normalized = _normalized_key(value)
    compact = normalized.replace("_", "")
    return (
        normalized in FORBIDDEN_SECRET_KEYS
        or compact in FORBIDDEN_SECRET_TOKENS
        or any(part in FORBIDDEN_SECRET_TOKENS for part in normalized.split("_"))
    )


def reject_secret_payload(value: Any, path: str = "$") -> None:
    """Fail closed before a secret-bearing payload can be logged or forwarded."""
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            child = f"{path}.{key_text}"
            if _secret_name(key_text):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.INVALID_REQUEST,
                    "Secret-bearing request payload rejected.",
                )
            reject_secret_payload(nested, child)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            reject_secret_payload(nested, f"{path}[{index}]")
        return
    if isinstance(value, str) and INLINE_SECRET.search(value):
        raise DesktopRuntimeError(
            RuntimeErrorCode.INVALID_REQUEST,
            "Secret-bearing request payload rejected.",
        )


def _validate_slot_presence_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    if set(value) != {"placeId", "persona", "slots"}:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots result is malformed.")
    if value.get("placeId") != args.get("placeId") or value.get("persona") != args.get("persona"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots identity mismatch.")
    slots = value.get("slots")
    if not isinstance(slots, dict):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots presence map is malformed.")
    for slot, present in slots.items():
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None or type(present) is not bool:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Secret slot presence must be boolean metadata only.")


def _validate_secret_request_ack(value: dict[str, Any], args: dict[str, Any]) -> None:
    reject_secret_payload(value)
    if set(value) != {"state", "request"} or value.get("state") != "needs-secret":
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request acknowledgement is malformed.")
    request = value.get("request")
    if not isinstance(request, dict) or request != args:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request metadata mismatch.")


def _reject_atlas_secret_fact_slots(value: Any) -> None:
    if not isinstance(value, dict):
        return
    screens = value.get("screens")
    if not isinstance(screens, list):
        return
    for screen in screens:
        if not isinstance(screen, dict):
            continue
        slots = screen.get("factSlots")
        if not isinstance(slots, list):
            continue
        for slot in slots:
            if not isinstance(slot, dict):
                continue
            name = slot.get("name")
            if isinstance(name, str) and _secret_name(name):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.PROTOCOL_MISMATCH,
                    "Android Atlas result attempted to expose a secret fact slot.",
                )


def validate_android_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
    """Keep a phone bug from turning into PC/model secret context."""
    if op == "secrets.slots":
        _validate_slot_presence_response(value, args)
        return value
    if op == "secrets.request":
        _validate_secret_request_ack(value, args)
        return value
    reject_secret_payload(value)
    if op == "atlas.places":
        if set(value) != {"places"} or not isinstance(value.get("places"), list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.places result is malformed.")
        for summary in value["places"]:
            if not isinstance(summary, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android Atlas place summary is malformed.")
            _reject_atlas_secret_fact_slots(summary)
        return value
    if op == "atlas.get":
        _reject_atlas_secret_fact_slots(value)
        return value
    raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")


class V5ContractService:
    """Constrained PC bridge for Run-1 Atlas/Vault metadata operations.

    This service creates no PC-side Atlas or Vault truth. Every successful result comes from the
    authenticated Android Gateway.
    """

    PROTOCOL = V5_CONTRACT_PROTOCOL

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def atlas_places(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "atlas.places", {})

    def atlas_get(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        self._validate_place_persona(args)
        return self._call(device_id, "atlas.get", args)

    def secret_slots(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        self._validate_place_persona(args)
        return self._call(device_id, "secrets.slots", args)

    def secret_request(
        self,
        device_id: str,
        *,
        place_id: str,
        persona: str,
        slot: str,
        reason: str,
        extra: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {
            "placeId": place_id,
            "persona": persona,
            "slot": slot,
            "reason": reason,
        }
        if extra:
            args.update(extra)
        reject_secret_payload(args)
        self._validate_place_persona(args)
        if set(args) != {"placeId", "persona", "slot", "reason"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unexpected secrets.request field.")
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "slot must be metadata only.")
        if not isinstance(reason, str) or REASON.fullmatch(reason) is None or INLINE_SECRET.search(reason):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "reason must be a bounded-safe-label.")
        return self._call(device_id, "secrets.request", args)

    def forward(self, device_id: str, op: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """Typed forwarding seam used by tests/consumers. No generic Android op passthrough."""
        if op not in V5_OPS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")
        args = dict(payload or {})
        reject_secret_payload(args)
        if op == "atlas.places":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.places takes no arguments.")
            return self.atlas_places(device_id)
        if op == "atlas.get":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.get requires placeId/persona only.")
            return self.atlas_get(device_id, str(args["placeId"]), str(args["persona"]))
        if op == "secrets.slots":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.slots requires placeId/persona only.")
            return self.secret_slots(device_id, str(args["placeId"]), str(args["persona"]))
        if set(args) != {"placeId", "persona", "slot", "reason"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.request requires safe metadata only.")
        return self.secret_request(
            device_id,
            place_id=str(args["placeId"]),
            persona=str(args["persona"]),
            slot=str(args["slot"]),
            reason=str(args["reason"]),
        )

    def _validate_place_persona(self, args: dict[str, Any]) -> None:
        place_id = args.get("placeId")
        persona = args.get("persona")
        if not isinstance(place_id, str) or (
            PACKAGE_PLACE.fullmatch(place_id) is None and CHROME_PLACE.fullmatch(place_id) is None
        ):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Invalid placeId.")
        if persona not in PERSONAS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "persona must be live or mapping.")

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before V5 contract access.")
        return session

    def _call(self, device_id: str, op: str, args: dict[str, Any]) -> dict[str, Any]:
        reject_secret_payload(args)
        session = self._paired(device_id)
        try:
            value = session.bridge().request(op, args, request_id=f"v5-{secrets.token_urlsafe(18)}")
            if not isinstance(value, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android V5 result must be an object.")
            return validate_android_response(op, value, args)
        except BridgeOperationError as exc:
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
                "INVALID_REQUEST": RuntimeErrorCode.INVALID_REQUEST,
                "SECRET_PAYLOAD_REJECTED": RuntimeErrorCode.INVALID_REQUEST,
            }
            raise DesktopRuntimeError(
                mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE),
                f"Android rejected {op}.",
            ) from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                "Phone disconnected from Cyclone Gateway.",
                retryable=True,
            ) from exc
