from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService


class FakeBridge:
    def __init__(self):
        self.calls = []

    def request(self, op, args, request_id=None):
        self.calls.append((op, dict(args), request_id))
        if op == "atlas.places":
            return {"places": []}
        if op == "atlas.get":
            package = args["placeId"].removeprefix("package:")
            return {
                "place": {
                    "placeId": args["placeId"],
                    "kind": "package",
                    "label": package.rsplit(".", 1)[-1],
                    "packageName": package,
                },
                "persona": args["persona"],
                "mapStatus": "unmapped",
                "screens": [],
                "edges": [],
                "capabilities": [],
                "confidence": 0.0,
                "lastObservedAt": None,
                "lastVerifiedAt": None,
            }
        if op == "secrets.slots":
            return {"placeId": args["placeId"], "persona": args["persona"], "slots": {}}
        if op == "secrets.request":
            return {"state": "needs-secret", "request": dict(args)}
        if op == "atlas.diff":
            return {
                "placeId": args["placeId"],
                "persona": args["persona"],
                "since": args.get("since"),
                "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:0",
                "resyncRequired": False,
                "changes": [],
            }
        if op.startswith("mapping."):
            state = {
                "mapping.start": "running",
                "mapping.pause": "paused",
                "mapping.stop": "stopped",
                "mapping.status": "running",
            }[op]
            return {
                "mappingJobId": "map-phone-0001",
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "state": state,
                "sessionId": args["sessionId"],
                "displayId": args["displayId"],
                "plane": {
                    "kind": "foreground",
                    "sessionId": args["sessionId"],
                    "displayId": args["displayId"],
                    "workspaceId": None,
                    "workspaceGeneration": None,
                    "label": "Foreground",
                },
                "controlRevision": 7,
                "executionGeneration": None,
                "budget": {
                    "maxNewScreens": 40,
                    "maxElapsedMs": 600000,
                    "maxConsecutiveNonProgress": 6,
                    "maxAttemptsPerDoor": 3,
                },
                "currentAtlasNodeId": None,
                "progress": {
                    "newScreens": 0,
                    "verifiedMutations": 0,
                    "consecutiveNonProgress": 0,
                    "attemptedDoors": 0,
                    "remainingDarkRegions": 0,
                },
                "atlasStatus": None,
                "danger": None,
                "boundary": None,
                "startedAtEpochMs": 100,
                "updatedAtEpochMs": 100,
                "failureCode": None,
            }
        raise AssertionError(op)


class FakeSession:
    credential = "paired"

    def __init__(self, bridge):
        self._bridge = bridge

    def bridge(self):
        return self._bridge


class FakeFleet:
    def __init__(self, bridge):
        self.session = FakeSession(bridge)

    def get(self, device_id):
        assert device_id == "phone-1"
        return self.session


@pytest.fixture
def service():
    bridge = FakeBridge()
    return V5ContractService(FakeFleet(bridge)), bridge


def test_forwards_all_run1_ops_to_phone_authority(service):
    svc, bridge = service
    assert svc.atlas_places("phone-1") == {"places": []}
    assert svc.atlas_get("phone-1", "package:com.example.app", "live")["mapStatus"] == "unmapped"
    assert svc.secret_slots("phone-1", "package:com.example.app", "mapping")["slots"] == {}
    ack = svc.secret_request(
        "phone-1",
        place_id="package:com.example.app",
        persona="live",
        slot="password",
        reason="Login required",
    )
    assert ack["state"] == "needs-secret"
    assert [call[0] for call in bridge.calls] == [
        "atlas.places",
        "atlas.get",
        "secrets.slots",
        "secrets.request",
    ]


def test_android_response_is_rejected_before_secret_can_enter_pc_context(service):
    svc, bridge = service

    def secret_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        if op == "atlas.get":
            return {
                "place": {
                    "placeId": args["placeId"],
                    "kind": "package",
                    "label": "Example",
                    "packageName": "com.example.app",
                },
                "persona": args["persona"],
                "mapStatus": "mapped",
                "screens": [{"screenId": "login", "factSlots": [{"name": "password"}]}],
                "edges": [],
                "capabilities": [],
                "confidence": 1.0,
                "lastObservedAt": None,
                "lastVerifiedAt": None,
            }
        raise AssertionError(op)

    bridge.request = secret_result
    with pytest.raises(DesktopRuntimeError):
        svc.atlas_get("phone-1", "package:com.example.app", "live")


def test_secret_slot_presence_accepts_secret_slot_names_only_as_booleans(service):
    svc, bridge = service

    def slots_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        if op == "secrets.slots":
            return {
                "placeId": args["placeId"],
                "persona": args["persona"],
                "slots": {"password": True, "otp": False},
            }
        raise AssertionError(op)

    bridge.request = slots_result
    result = svc.secret_slots("phone-1", "package:com.example.app", "live")
    assert result["slots"] == {"password": True, "otp": False}

    def bad_slots_result(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        return {
            "placeId": args["placeId"],
            "persona": args["persona"],
            "slots": {"password": 1},
        }

    bridge.request = bad_slots_result
    with pytest.raises(DesktopRuntimeError):
        svc.secret_slots("phone-1", "package:com.example.app", "live")


def test_secret_payload_is_rejected_not_stripped_or_forwarded(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward(
            "phone-1",
            "secrets.request",
            {
                "placeId": "package:com.example.app",
                "persona": "live",
                "slot": "password",
                "reason": "Login required",
                "password": True,
            },
        )
    assert bridge.calls == []


def _schema(name):
    root = Path(__file__).resolve().parents[3]
    return json.loads((root / "protocol" / name).read_text(encoding="utf-8"))


def test_valid_empty_atlas_document_validates():
    document = {
        "place": {
            "placeId": "package:com.example.app",
            "kind": "package",
            "label": "Example",
            "packageName": "com.example.app",
        },
        "persona": "live",
        "mapStatus": "unmapped",
        "screens": [],
        "edges": [],
        "capabilities": [],
        "confidence": 0.0,
        "lastObservedAt": None,
        "lastVerifiedAt": None,
    }
    Draft202012Validator(_schema("cyclone-atlas-v1.schema.json")).validate(document)


def test_partial_atlas_document_validates():
    document = {
        "place": {
            "placeId": "package:com.example.app",
            "kind": "package",
            "label": "Example",
            "packageName": "com.example.app",
        },
        "persona": "live",
        "mapStatus": "partial",
        "screens": [],
        "edges": [],
        "capabilities": [],
        "confidence": 0.5,
        "lastObservedAt": None,
        "lastVerifiedAt": None,
    }
    Draft202012Validator(_schema("cyclone-atlas-v1.schema.json")).validate(document)


def test_valid_slot_presence_validates_and_value_field_is_rejected():
    validator = Draft202012Validator(_schema("cyclone-secrets-v1.schema.json"))
    validator.validate({
        "placeId": "package:com.example.app",
        "persona": "live",
        "slots": {"username": True, "password": True, "otp": False},
    })
    for forbidden_field in ("password", "value"):
        with pytest.raises(Exception):
            validator.validate({
                "placeId": "package:com.example.app",
                "persona": "live",
                "slot": "password",
                "reason": "Login required",
                forbidden_field: True,
            })


def test_run2_atlas_diff_is_android_forwarded_and_cursor_is_phone_owned(service):
    svc, bridge = service
    result = svc.atlas_diff(
        "phone-1",
        "package:com.example.app",
        "mapping",
        None,
    )
    assert result["cursor"] == "c1:aaaaaaaaaaaaaaaaaaaa:0"
    assert result["changes"] == []
    assert bridge.calls[-1][0] == "atlas.diff"
    assert bridge.calls[-1][1] == {
        "placeId": "package:com.example.app",
        "persona": "mapping",
        "since": None,
    }


def test_run2_mapping_state_is_forwarded_from_android_not_created_on_pc(service):
    svc, bridge = service
    result = svc.forward(
        "phone-1",
        "mapping.start",
        {
            "placeId": "package:com.example.app",
            "persona": "mapping",
            "sessionId": "default-foreground",
            "displayId": 0,
        },
    )
    assert result["mappingJobId"] == "map-phone-0001"
    assert result["state"] == "running"
    assert result["controlRevision"] == 7
    assert bridge.calls[-1][0] == "mapping.start"


def test_run2_mapping_requires_explicit_session_before_forwarding(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "displayId": 0,
            },
        )
    assert str(caught.value.code) == "SESSION_REQUIRED"
    assert bridge.calls == []


def test_run2_named_display_zero_is_rejected_before_forwarding(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "sessionId": "workspace-1",
                "displayId": 0,
                "executionGeneration": 1,
            },
        )
    assert str(caught.value.code) == "SESSION_DISPLAY_MISMATCH"
    assert bridge.calls == []


def test_run2_mapping_secret_bearing_extra_is_rejected_not_stripped(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward(
            "phone-1",
            "mapping.start",
            {
                "placeId": "package:com.example.app",
                "persona": "mapping",
                "sessionId": "default-foreground",
                "displayId": 0,
                "password": True,
            },
        )
    assert bridge.calls == []


def test_run2_diff_rejects_non_structural_android_payload(service):
    svc, bridge = service

    def bad_diff(op, args, request_id=None):
        bridge.calls.append((op, dict(args), request_id))
        return {
            "placeId": args["placeId"],
            "persona": args["persona"],
            "since": args.get("since"),
            "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:1",
            "resyncRequired": False,
            "changes": [{
                "cursor": "c1:aaaaaaaaaaaaaaaaaaaa:1",
                "entity": "screen",
                "change": "upsert",
                "id": "screen:home",
                "label": "unexpected",
            }],
        }

    bridge.request = bad_diff
    with pytest.raises(DesktopRuntimeError):
        svc.atlas_diff("phone-1", "package:com.example.app", "mapping", None)
