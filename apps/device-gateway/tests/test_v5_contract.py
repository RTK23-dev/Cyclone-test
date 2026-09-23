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


GLASS_BUDGET = {
    "maxNewScreens": 12,
    "maxElapsedMs": 180000,
    "maxConsecutiveNonProgress": 6,
    "maxAttemptsPerDoor": 2,
}


def test_alpha3_glass_start_resume_pause_bodies_are_forwarded_unchanged(service):
    """The exact bodies Glass Maps sends (atlasClient.mappingCall) must pass the PC contract."""
    svc, bridge = service
    plane = {"sessionId": "default-foreground", "displayId": 0}

    svc.forward("phone-1", "mapping.start", {
        "placeId": "package:com.example.app", "persona": "mapping", "budget": dict(GLASS_BUDGET), **plane,
    })
    svc.forward("phone-1", "mapping.start", {"resumeJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.pause", {"mappingJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.stop", {"mappingJobId": "map-phone-0001", **plane})
    svc.forward("phone-1", "mapping.status", dict(plane))

    ops = [call[0] for call in bridge.calls]
    assert ops == ["mapping.start", "mapping.start", "mapping.pause", "mapping.stop", "mapping.status"]
    assert bridge.calls[0][1]["budget"] == GLASS_BUDGET
    assert bridge.calls[1][1] == {"resumeJobId": "map-phone-0001", **plane}


def test_alpha3_glass_resume_cannot_smuggle_a_new_place(service):
    svc, bridge = service
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "mapping.start", {
            "resumeJobId": "map-phone-0001", "placeId": "package:com.other.app",
            "sessionId": "default-foreground", "displayId": 0,
        })
    assert bridge.calls == []


ASK_STATUS = {
    "taskId": "task-1",
    "state": "needs-secret",
    "title": "Gmail → Facebook",
    "app": "Facebook",
    "currentMilestone": "Finding the dm of Louella",
    "milestones": [
        {"label": "Finding the signed-in email address", "state": "done"},
        {"label": "Finding the dm of Louella", "state": "action-needed"},
    ],
    "supportingCopy": "Secure input is required to continue.",
    "outcomeCopy": None,
    "sessionId": "default-foreground",
    "displayId": 0,
}


class AskBridge(FakeBridge):
    def __init__(self, status=None):
        super().__init__()
        self.status = dict(status or ASK_STATUS)

    def request(self, op, args, request_id=None):
        if op == "ask.start":
            self.calls.append((op, dict(args), request_id))
            return {"accepted": True, "sessionId": "default-foreground", "displayId": 0}
        if op == "ask.status":
            self.calls.append((op, dict(args), request_id))
            return dict(self.status)
        return super().request(op, args, request_id)


def _ask_service(status=None):
    bridge = AskBridge(status)
    return V5ContractService(FakeFleet(bridge)), bridge


FOREGROUND = {"sessionId": "default-foreground", "displayId": 0}


def test_alpha4_ask_start_forwards_goal_text_on_the_foreground_plane():
    svc, bridge = _ask_service()
    goal = "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella"
    result = svc.forward("phone-1", "ask.start", {"goal": goal, **FOREGROUND})
    assert result["accepted"] is True
    assert bridge.calls == [("ask.start", {"goal": goal, **FOREGROUND}, bridge.calls[0][2])]


def test_alpha4_ask_status_is_the_phone_snapshot():
    svc, _ = _ask_service()
    status = svc.forward("phone-1", "ask.status", dict(FOREGROUND))
    assert status["state"] == "needs-secret"
    assert status["milestones"][1]["state"] == "action-needed"


@pytest.mark.parametrize("body, code", [
    ({"goal": "open clock", "displayId": 0}, "SESSION_REQUIRED"),
    ({"goal": "open clock", "sessionId": "vd-mail", "displayId": 3}, "SESSION_DISPLAY_MISMATCH"),
    ({"goal": "", **FOREGROUND}, "INVALID_REQUEST"),
    ({"goal": "x" * 2001, **FOREGROUND}, "INVALID_REQUEST"),
    ({"goal": "open clock", "placeId": "package:com.x.y", **FOREGROUND}, "INVALID_REQUEST"),
])
def test_alpha4_ask_start_rejects_bad_requests_before_forwarding(body, code):
    svc, bridge = _ask_service()
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward("phone-1", "ask.start", body)
    assert str(caught.value.code) == code
    assert bridge.calls == []


def test_alpha4_secrets_never_travel_in_a_goal():
    svc, bridge = _ask_service()
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "ask.start", {"goal": "log in with password: hunter2", **FOREGROUND})
    with pytest.raises(DesktopRuntimeError):
        svc.forward("phone-1", "ask.start", {"goal": "open clock", "password": "hunter2", **FOREGROUND})
    assert bridge.calls == []


def test_alpha4_malformed_phone_ask_status_is_rejected():
    svc, _ = _ask_service({**ASK_STATUS, "state": "walking"})
    with pytest.raises(DesktopRuntimeError) as caught:
        svc.forward("phone-1", "ask.status", dict(FOREGROUND))
    assert str(caught.value.code) == "PROTOCOL_MISMATCH"


GMAIL_APP = {
    "placeId": "package:com.google.android.gm",
    "kind": "package",
    "label": "Gmail",
    "packageName": "com.google.android.gm",
    "origin": None,
    "installed": True,
    "installedVersion": {"versionName": "2026.09.01", "versionCode": 900},
    "mapStatus": "mapped",
    "rooms": 6,
    "doors": 11,
    "lastVerifiedAt": 1000,
    "needsRemap": True,
    "personas": [{"persona": "mapping", "mapStatus": "mapped", "rooms": 6, "doors": 11, "lastVerifiedAt": 1000}],
    "mappedVersions": [{"versionName": "2026.08.01", "versionCode": 880, "doors": 9}],
}


class AppsBridge(FakeBridge):
    def __init__(self, result):
        super().__init__()
        self.result = result

    def request(self, op, args, request_id=None):
        if op == "apps.list":
            self.calls.append((op, dict(args), request_id))
            return self.result
        return super().request(op, args, request_id)


def test_glass_alpha1_apps_list_is_the_phone_catalog():
    bridge = AppsBridge({"apps": [GMAIL_APP], "truncated": False})
    svc = V5ContractService(FakeFleet(bridge))
    assert svc.forward("phone-1", "apps.list", {}) == {"apps": [GMAIL_APP], "truncated": False}
    assert bridge.calls[0][:2] == ("apps.list", {})
    with pytest.raises(DesktopRuntimeError) as error:
        svc.forward("phone-1", "apps.list", {"all": True})
    assert error.value.code == "INVALID_REQUEST"
    assert len(bridge.calls) == 1


@pytest.mark.parametrize(
    "bad",
    [
        {"apps": [GMAIL_APP]},
        {"apps": [{**GMAIL_APP, "screens": []}], "truncated": False},
        {"apps": [{**GMAIL_APP, "placeId": "file:///sdcard"}], "truncated": False},
        {"apps": [{**GMAIL_APP, "mapStatus": "walking"}], "truncated": False},
        {"apps": [{**GMAIL_APP, "installedVersion": {"versionName": "1", "versionCode": "x"}}], "truncated": False},
        {"apps": [{**GMAIL_APP, "mappedVersions": [{"versionName": "1", "versionCode": 1, "selector": "x"}]}], "truncated": False},
        {"apps": [{**GMAIL_APP, "personas": [{"persona": "you", "mapStatus": "mapped", "rooms": 1, "doors": 1, "lastVerifiedAt": None}]}], "truncated": False},
        {"apps": [{**GMAIL_APP, "label": "x" * 81}], "truncated": False},
    ],
)
def test_glass_alpha1_malformed_phone_catalog_is_rejected(bad):
    svc = V5ContractService(FakeFleet(AppsBridge(bad)))
    with pytest.raises(DesktopRuntimeError) as error:
        svc.forward("phone-1", "apps.list", {})
    assert error.value.code == "PROTOCOL_MISMATCH"


def test_glass_alpha1_apps_list_is_an_allowed_bridge_op():
    from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS, UNAUTHENTICATED_OPS

    assert "apps.list" in ALLOWED_OPS
    assert "apps.list" not in UNAUTHENTICATED_OPS
