"""Cyclone Lab HTTP routes for Glass and agents. Same loopback bearer as every gateway route; no route here runs a
phone command directly: experiments go through the typed lab contract and the lab's fixed probes."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import FileResponse

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError
from .runner import LabError, LabService


def create_lab_router(runtime: Any, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def lab() -> LabService:
        service = getattr(runtime, "lab", None)
        if service is None:
            raise HTTPException(status_code=503, detail={"code": "CAPABILITY_UNAVAILABLE", "message": "Cyclone Lab is not available."})
        return service

    def call(fn):
        try:
            return fn()
        except LabError as exc:
            raise HTTPException(status_code=400, detail={"code": "INVALID_REQUEST", "message": str(exc)}) from exc
        except DesktopRuntimeError as exc:
            raise HTTPException(status_code=409, detail=exc.to_dict()) from exc

    @router.get("/v1/lab/missions", dependencies=[Depends(auth)])
    def missions():
        return call(lambda: lab().catalog())

    @router.get("/v1/lab/experiments", dependencies=[Depends(auth)])
    def experiments():
        return call(lambda: {"experiments": lab().list()})

    @router.post("/v1/lab/experiments", dependencies=[Depends(auth)])
    def create(body: dict[str, Any]):
        if not set(body) <= {"deviceId", "name", "missions", "variants", "repetitions"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Unknown experiment field."})
        device_id = body.get("deviceId")
        if not isinstance(device_id, str) or not device_id:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "deviceId is required."})

        def start():
            session = runtime.fleet.get(device_id)
            if not session.credential:
                raise LabError("Pair this phone with Cyclone One before running the lab.")
            return lab().create(device_id, body.get("name", ""), body.get("missions"), body.get("variants"), body.get("repetitions", 1))
        return call(start)

    @router.get("/v1/lab/experiments/{exp_id}", dependencies=[Depends(auth)])
    def experiment(exp_id: str):
        return call(lambda: lab().get(exp_id))

    @router.post("/v1/lab/experiments/{exp_id}/stop", dependencies=[Depends(auth)])
    def stop(exp_id: str):
        return call(lambda: lab().stop(exp_id))

    @router.get("/v1/lab/experiments/{exp_id}/trials.jsonl", dependencies=[Depends(auth)])
    def export(exp_id: str):
        path = call(lambda: lab().export_path(exp_id))
        return FileResponse(path, media_type="application/x-ndjson", filename=f"cyclone-lab-{exp_id}.jsonl",
                            headers={"Cache-Control": "no-store"})

    return router
