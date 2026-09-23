from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..desktop_runtime.v5_contract import V5ContractService


def create_v5_contract_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    service = getattr(runtime, "v5_contract", None) or V5ContractService(runtime.fleet)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/devices/{device_id}/atlas/places", dependencies=[Depends(auth)])
    def atlas_places(device_id: str):
        return _call(lambda: service.atlas_places(device_id))

    @router.get("/v1/devices/{device_id}/atlas", dependencies=[Depends(auth)])
    def atlas_get(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
    ):
        return _call(lambda: service.atlas_get(device_id, placeId, persona))

    @router.get("/v1/devices/{device_id}/atlas/diff", dependencies=[Depends(auth)])
    def atlas_diff(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
        since: str | None = Query(default=None, max_length=128),
    ):
        return _call(lambda: service.atlas_diff(device_id, placeId, persona, since))

    @router.post("/v1/devices/{device_id}/mapping/start", dependencies=[Depends(auth)])
    def mapping_start(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.start", body))

    @router.post("/v1/devices/{device_id}/mapping/pause", dependencies=[Depends(auth)])
    def mapping_pause(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.pause", body))

    @router.post("/v1/devices/{device_id}/mapping/stop", dependencies=[Depends(auth)])
    def mapping_stop(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.stop", body))

    @router.post("/v1/devices/{device_id}/mapping/status", dependencies=[Depends(auth)])
    def mapping_status(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "mapping.status", body))

    @router.post("/v1/devices/{device_id}/ask/start", dependencies=[Depends(auth)])
    def ask_start(device_id: str, body: dict[str, Any]):
        # Goal text only; secret-bearing payloads are rejected before forwarding or logging.
        return _call(lambda: service.forward(device_id, "ask.start", body))

    @router.post("/v1/devices/{device_id}/ask/status", dependencies=[Depends(auth)])
    def ask_status(device_id: str, body: dict[str, Any]):
        return _call(lambda: service.forward(device_id, "ask.status", body))

    @router.get("/v1/devices/{device_id}/secrets/slots", dependencies=[Depends(auth)])
    def secret_slots(
        device_id: str,
        placeId: str = Query(min_length=9, max_length=512),
        persona: Literal["live", "mapping"] = Query(),
    ):
        return _call(lambda: service.secret_slots(device_id, placeId, persona))

    @router.post("/v1/devices/{device_id}/secrets/request", dependencies=[Depends(auth)])
    def secret_request(device_id: str, body: dict[str, Any]):
        # Raw object is inspected by the contract service before schema-style field validation.
        # This avoids framework validation responses echoing an accidental secret-bearing extra.
        return _call(lambda: service.forward(device_id, "secrets.request", body))

    return router


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        code = str(exc.code)
        status = {
            RuntimeErrorCode.INVALID_REQUEST.value: 400,
            RuntimeErrorCode.SESSION_REQUIRED.value: 400,
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH.value: 409,
            RuntimeErrorCode.HUMAN_HAS_CONTROL.value: 409,
            RuntimeErrorCode.STALE_CONTROL_REVISION.value: 409,
            RuntimeErrorCode.MAPPING_PLANE_BUSY.value: 409,
            RuntimeErrorCode.MAPPING_JOB_NOT_FOUND.value: 404,
            RuntimeErrorCode.MAPPING_INVALID_STATE.value: 409,
            RuntimeErrorCode.ASK_BUSY.value: 409,
            RuntimeErrorCode.OVERLAY_UNAVAILABLE.value: 503,
            RuntimeErrorCode.AUTH_REJECTED.value: 403,
            RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
            RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
            RuntimeErrorCode.PROTOCOL_MISMATCH.value: 502,
        }.get(code, 503)
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
