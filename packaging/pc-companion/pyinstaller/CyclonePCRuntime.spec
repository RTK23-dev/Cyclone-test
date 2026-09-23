from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
entrypoints = repo / "scripts" / "pc-companion" / "entrypoints"
scrcpy = repo / "apps" / "device-gateway" / "third_party" / "scrcpy"
glass_dist = repo / "apps" / "glass" / "dist"
if not (glass_dist / "index.html").is_file():
    raise SystemExit("Cyclone Glass is not built: run `npm ci && npm run build` in apps/glass (build-sidecars.ps1 does this).")
a = Analysis(
    [str(entrypoints / "pc_runtime.py")],
    pathex=[str(repo / "tools" / "codex-phone-mcp"), str(repo / "apps" / "device-gateway"), str(entrypoints)],
    binaries=[],
    datas=[
        (str(scrcpy / "scrcpy-server-v4.0"), "third_party/scrcpy"),
        (str(scrcpy / "scrcpy-v4.0.json"), "third_party/scrcpy"),
        (str(scrcpy / "LICENSE"), "third_party/scrcpy"),
        (str(scrcpy / "NOTICE.md"), "third_party/scrcpy"),
        # Served by cyclone_device_gateway.glass.resolve_glass_dist() from the package-relative static/ folder.
        (str(glass_dist), "cyclone_device_gateway/glass/static"),
    ],
    hiddenimports=["cyclone_phone_mcp.live_phone_ipc", "secure_gateway_token", "cyclone_device_gateway.tooling_seam", "cyclone_device_gateway.glass.launcher"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas,
    [],
    name="CyclonePCRuntime",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)
