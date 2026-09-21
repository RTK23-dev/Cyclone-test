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
                "password": "must-never-cross",
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


def test_valid_slot_presence_validates_and_value_field_is_rejected():
    validator = Draft202012Validator(_schema("cyclone-secrets-v1.schema.json"))
    validator.validate({
        "placeId": "package:com.example.app",
        "persona": "live",
        "slots": {"username": True, "password": True, "otp": False},
    })
    with pytest.raises(Exception):
        validator.validate({
            "placeId": "package:com.example.app",
            "persona": "live",
            "slot": "password",
            "reason": "Login required",
            "password": "must-never-cross",
        })
