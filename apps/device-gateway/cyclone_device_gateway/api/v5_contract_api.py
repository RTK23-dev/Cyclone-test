from __future__ import annotations

from typing import Any, Literal
from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from ..desktop_runtime.v5_contract import V5ContractService


class SecretRequestBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    placeId: str = Field(min_length=9, max_length=512)
    persona: Literal["live", "mapping"]
    slot: str = Field(min_length=1, max_length=64)
    reason: str = Field(min_length=1, max_length=120)


def create_v5_contract_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()
    service = getattr(runtime, "v5_contract", None) or V5ContractService(runtime.fleet)

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    @router.get("/v1/devices/{device_id}/atlas/places", dependencies=[Depends(auth)])
    def atlas_places(device_id: str):
        return _call(lambda: service.atlas_places(device_id))

    @router.get("/v1/devices/{device_id}/atlas", dependencies=[Depends(auth)])
    def atlas_get(device_id: str, placeId: str = Query(min_length=9, max_length=512), persona: Literal["live", "mapping"] = Query()):
        return _call(lambda: service.atlas_get(device_id, placeId, persona))

    @router.get("/v1/devices/{device_id}/secrets/slots", dependencies=[Depends(auth)])
    def secret_slots(device_id: str, placeId: str = Query(min_length=9, max_length=512), persona: Literal["live", "mapping"] = Query()):
        return _call(lambda: service.secret_slots(device_id, placeId, persona))

    @router.post("/v1/devices/{device_id}/secrets/request", dependencies=[Depends(auth)])
    def secret_request(device_id: str, body: SecretRequestBody):
        return _call(lambda: service.secret_request(
            device_id,
            place_id=body.placeId,
            persona=body.persona,
            slot=body.slot,
            reason=body.reason,
        ))

    return router


def _call(fn):
    try:
        return fn()
    except DesktopRuntimeError as exc:
        status = 400 if exc.code == RuntimeErrorCode.INVALID_REQUEST else 503
        raise HTTPException(status_code=status, detail=exc.to_dict()) from exc
