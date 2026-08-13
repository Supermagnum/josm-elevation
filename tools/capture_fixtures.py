#!/usr/bin/env python3
"""Capture steep-road OSM fixtures by driving a local JOSM via Remote Control.

This is a one-off developer utility (not part of the JOSM plugin, not run in CI).
It talks only to ``http://localhost:8111`` (or ``--host``/``--port``):

* ``/version`` — preflight (JOSM running + Remote Control enabled)
* ``/load_object`` — download OSM ways into new layers
* ``/load_and_zoom`` — download a bbox into a new layer
* ``/export`` — read the active layer as ``.osm`` XML (JOSM r19425+)

It never uploads to OpenStreetMap. Remote Control has no upload command
here; load/export only read from the OSM API into JOSM and read JOSM's
local layer buffer back to this script.

Enable Remote Control in JOSM: Edit → Preferences → Remote Control.

Example::

    python tools/capture_fixtures.py \\
      --ways 764390363,757907237,330233844 \\
      --bbox 10.05,61.50,10.20,61.58
"""

from __future__ import annotations

import argparse
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable
from urllib.parse import urlencode

try:
    import requests
except ImportError:  # pragma: no cover
    print(
        "error: the 'requests' package is required "
        "(pip install requests, or use the prototype venv).",
        file=sys.stderr,
    )
    sys.exit(2)

DEFAULT_WAYS = (764390363, 757907237, 330233844)
DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8111
DEFAULT_OUT_DIR = Path("tests/fixtures/steep_roads/osm")

VERSION_RETRIES = 3
VERSION_RETRY_DELAY_S = 0.4
VERSION_TIMEOUT_S = 2.0

WAY_EXPORT_RETRIES = 12
WAY_EXPORT_DELAY_S = 0.5
AREA_EXPORT_RETRIES = 40
AREA_EXPORT_DELAY_S = 0.75

HttpGet = Callable[..., "requests.Response"]


class CaptureError(Exception):
    """User-facing failure; message is enough (no traceback dump from main)."""


@dataclass(frozen=True)
class BBox:
    left: float
    bottom: float
    right: float
    top: float

    def as_query(self) -> dict[str, str]:
        return {
            "left": _fmt_coord(self.left),
            "bottom": _fmt_coord(self.bottom),
            "right": _fmt_coord(self.right),
            "top": _fmt_coord(self.top),
        }


def _fmt_coord(value: float) -> str:
    # Trim noisy floats while keeping copy-paste fidelity from JOSM UI.
    text = f"{value:.8f}".rstrip("0").rstrip(".")
    return text if text else "0"


def parse_bbox(text: str) -> BBox:
    """Parse ``left,bottom,right,top`` (JOSM /load_and_zoom order)."""
    parts = [p.strip() for p in text.split(",")]
    if len(parts) != 4:
        raise CaptureError(
            f"invalid --bbox {text!r}: expected left,bottom,right,top "
            "(four comma-separated numbers, JOSM order)"
        )
    try:
        left, bottom, right, top = (float(p) for p in parts)
    except ValueError as exc:
        raise CaptureError(f"invalid --bbox {text!r}: {exc}") from exc
    if left >= right or bottom >= top:
        raise CaptureError(
            f"invalid --bbox {text!r}: need left < right and bottom < top"
        )
    return BBox(left=left, bottom=bottom, right=right, top=top)


def parse_ways(text: str) -> list[int]:
    parts = [p.strip() for p in text.split(",") if p.strip()]
    if not parts:
        raise CaptureError("--ways must list at least one OSM way id")
    out: list[int] = []
    for part in parts:
        try:
            wid = int(part)
        except ValueError as exc:
            raise CaptureError(f"invalid way id {part!r}") from exc
        if wid <= 0:
            raise CaptureError(f"invalid way id {wid}: must be positive")
        out.append(wid)
    return out


# --- URL / query builders (pure; unit-tested without JOSM) -------------------


def base_url(host: str, port: int) -> str:
    return f"http://{host}:{port}"


def version_url(host: str, port: int) -> str:
    return f"{base_url(host, port)}/version"


def load_object_url(
    host: str,
    port: int,
    way_id: int,
    *,
    new_layer: bool = True,
    relation_members: bool = True,
    layer_name: str | None = None,
) -> str:
    if layer_name is None:
        layer_name = f"steep_way_{way_id}"
    query = {
        "new_layer": "true" if new_layer else "false",
        "objects": f"w{way_id}",
        "relation_members": "true" if relation_members else "false",
        "layer_name": layer_name,
    }
    return f"{base_url(host, port)}/load_object?{urlencode(query)}"


def load_and_zoom_url(
    host: str,
    port: int,
    bbox: BBox,
    *,
    new_layer: bool = True,
    layer_name: str = "steep_area",
) -> str:
    query = {
        **bbox.as_query(),
        "new_layer": "true" if new_layer else "false",
        "layer_name": layer_name,
    }
    return f"{base_url(host, port)}/load_and_zoom?{urlencode(query)}"


def export_url(host: str, port: int) -> str:
    return f"{base_url(host, port)}/export"


def way_output_path(out_dir: Path, way_id: int) -> Path:
    return out_dir / f"steep_way_{way_id}.osm"


def area_output_path(out_dir: Path) -> Path:
    return out_dir / "steep_area.osm"


# --- HTTP helpers -----------------------------------------------------------


def _get(
    http_get: HttpGet,
    url: str,
    *,
    timeout: float,
) -> requests.Response:
    try:
        return http_get(url, timeout=timeout)
    except requests.exceptions.ConnectionError as exc:
        raise CaptureError(
            f"connection refused talking to JOSM Remote Control at {url}\n"
            "Start JOSM and enable Edit → Preferences → Remote Control."
        ) from exc
    except requests.exceptions.Timeout as exc:
        raise CaptureError(f"timeout talking to JOSM Remote Control at {url}") from exc
    except requests.exceptions.RequestException as exc:
        raise CaptureError(f"HTTP error talking to JOSM Remote Control at {url}: {exc}") from exc


def preflight_version(
    host: str,
    port: int,
    *,
    http_get: HttpGet = requests.get,
    retries: int = VERSION_RETRIES,
    delay_s: float = VERSION_RETRY_DELAY_S,
    timeout_s: float = VERSION_TIMEOUT_S,
    sleep: Callable[[float], None] = time.sleep,
) -> str:
    """GET /version with a few short retries, then fail loudly."""
    url = version_url(host, port)
    last_err: str | None = None
    for attempt in range(1, retries + 1):
        try:
            resp = _get(http_get, url, timeout=timeout_s)
        except CaptureError as exc:
            last_err = str(exc)
            if attempt < retries:
                sleep(delay_s)
                continue
            raise CaptureError(
                f"JOSM Remote Control not reachable at {url} after {retries} tries.\n"
                "Enable it under Edit → Preferences → Remote Control, then retry.\n"
                f"Last error: {last_err}"
            ) from exc
        if resp.status_code == 200 and (resp.text or "").strip():
            return resp.text.strip()
        last_err = f"HTTP {resp.status_code}: {(resp.text or '')[:200]!r}"
        if attempt < retries:
            sleep(delay_s)
            continue
    raise CaptureError(
        f"JOSM Remote Control /version failed at {url} after {retries} tries.\n"
        "Enable Remote Control under Edit → Preferences → Remote Control.\n"
        f"Last response: {last_err}"
    )


def is_well_formed_osm_xml(body: str) -> bool:
    text = (body or "").strip()
    if not text:
        return False
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return False
    return root.tag == "osm" or root.tag.endswith("}osm")


@dataclass
class ExportOutcome:
    """Result of polling /export."""

    kind: str  # "ok" | "empty" | "not_supported" | "http_error"
    body: str = ""
    status_code: int | None = None
    detail: str = ""


def poll_export(
    host: str,
    port: int,
    *,
    http_get: HttpGet = requests.get,
    retries: int = WAY_EXPORT_RETRIES,
    delay_s: float = WAY_EXPORT_DELAY_S,
    timeout_s: float = 30.0,
    sleep: Callable[[float], None] = time.sleep,
) -> ExportOutcome:
    """Poll GET /export until non-empty well-formed OSM XML, or give up.

    Distinguishes:
    * empty body → keep polling
    * HTTP 404 → /export not available (pre-r19425)
    * valid XML → success
    * other non-200 → failure
    """
    url = export_url(host, port)
    last_empty = False
    for attempt in range(1, retries + 1):
        try:
            resp = _get(http_get, url, timeout=timeout_s)
        except CaptureError as exc:
            return ExportOutcome(kind="http_error", detail=str(exc))

        if resp.status_code == 404:
            return ExportOutcome(
                kind="not_supported",
                status_code=404,
                detail="GET /export returned 404 (needs JOSM r19425+)",
            )
        if resp.status_code != 200:
            return ExportOutcome(
                kind="http_error",
                status_code=resp.status_code,
                detail=f"GET /export HTTP {resp.status_code}: {(resp.text or '')[:200]!r}",
            )

        body = resp.text or ""
        if is_well_formed_osm_xml(body):
            return ExportOutcome(kind="ok", body=body, status_code=200)

        last_empty = True
        if attempt < retries:
            sleep(delay_s)

    if last_empty:
        return ExportOutcome(
            kind="empty",
            detail=(
                f"GET /export stayed empty or invalid after {retries} polls "
                "(no active layer, download still running, or mid-upload)"
            ),
        )
    return ExportOutcome(kind="http_error", detail="GET /export failed")


def print_manual_export_fallback(paths: Iterable[Path]) -> None:
    print(
        "\nYour JOSM build does not expose GET /export (added in r19425).\n"
        "Layers were still loaded — save them manually:\n"
        "  File → Save As… for each steep_way_* / steep_area layer into:\n"
    )
    for path in paths:
        print(f"    {path}")
    print()


# --- Capture orchestration --------------------------------------------------


@dataclass
class CaptureResult:
    ok_paths: list[Path]
    failed: list[str]
    export_unsupported: bool = False


def capture_way(
    host: str,
    port: int,
    way_id: int,
    out_dir: Path,
    *,
    http_get: HttpGet = requests.get,
    sleep: Callable[[float], None] = time.sleep,
) -> tuple[Path | None, str | None, bool]:
    """Load one way and export it. Returns (path, error, export_unsupported)."""
    out_path = way_output_path(out_dir, way_id)
    load_url = load_object_url(host, port, way_id)
    try:
        resp = _get(http_get, load_url, timeout=60.0)
    except CaptureError as exc:
        return None, f"way {way_id}: load failed: {exc}", False
    if resp.status_code != 200:
        return (
            None,
            f"way {way_id}: load_object HTTP {resp.status_code}: {(resp.text or '')[:200]!r}",
            False,
        )

    outcome = poll_export(
        host,
        port,
        http_get=http_get,
        retries=WAY_EXPORT_RETRIES,
        delay_s=WAY_EXPORT_DELAY_S,
        sleep=sleep,
    )
    if outcome.kind == "not_supported":
        return None, f"way {way_id}: {outcome.detail}", True
    if outcome.kind != "ok":
        return None, f"way {way_id}: export failed: {outcome.detail}", False

    out_dir.mkdir(parents=True, exist_ok=True)
    out_path.write_text(outcome.body, encoding="utf-8")
    return out_path, None, False


def capture_area(
    host: str,
    port: int,
    bbox: BBox,
    out_dir: Path,
    *,
    http_get: HttpGet = requests.get,
    sleep: Callable[[float], None] = time.sleep,
) -> tuple[Path | None, str | None, bool]:
    """Load bbox layer and export it. Returns (path, error, export_unsupported)."""
    out_path = area_output_path(out_dir)
    load_url = load_and_zoom_url(host, port, bbox)
    try:
        resp = _get(http_get, load_url, timeout=120.0)
    except CaptureError as exc:
        return None, f"area: load_and_zoom failed: {exc}", False
    if resp.status_code != 200:
        return (
            None,
            f"area: load_and_zoom HTTP {resp.status_code}: {(resp.text or '')[:200]!r}",
            False,
        )

    outcome = poll_export(
        host,
        port,
        http_get=http_get,
        retries=AREA_EXPORT_RETRIES,
        delay_s=AREA_EXPORT_DELAY_S,
        sleep=sleep,
    )
    if outcome.kind == "not_supported":
        return None, f"area: {outcome.detail}", True
    if outcome.kind != "ok":
        return None, f"area: export failed: {outcome.detail}", False

    out_dir.mkdir(parents=True, exist_ok=True)
    out_path.write_text(outcome.body, encoding="utf-8")
    return out_path, None, False


def run_capture(
    *,
    ways: list[int],
    bbox: BBox,
    host: str,
    port: int,
    out_dir: Path,
    http_get: HttpGet = requests.get,
    sleep: Callable[[float], None] = time.sleep,
) -> CaptureResult:
    version = preflight_version(host, port, http_get=http_get, sleep=sleep)
    print(f"JOSM Remote Control OK ({version_url(host, port)}): {version[:120]}")

    result = CaptureResult(ok_paths=[], failed=[])
    expected_manual: list[Path] = [way_output_path(out_dir, w) for w in ways]
    expected_manual.append(area_output_path(out_dir))

    for way_id in ways:
        print(f"Loading way {way_id}…")
        path, err, unsupported = capture_way(
            host, port, way_id, out_dir, http_get=http_get, sleep=sleep
        )
        if unsupported:
            result.export_unsupported = True
            result.failed.append(err or f"way {way_id}: /export not supported")
            print(f"  FAIL: {result.failed[-1]}")
            # Still try remaining loads so layers exist for manual save.
            continue
        if err:
            result.failed.append(err)
            print(f"  FAIL: {err}")
            continue
        assert path is not None
        result.ok_paths.append(path)
        print(f"  wrote {path} ({path.stat().st_size} bytes)")

    if not result.export_unsupported:
        print(f"Loading area bbox {bbox.left},{bbox.bottom},{bbox.right},{bbox.top}…")
        path, err, unsupported = capture_area(
            host, port, bbox, out_dir, http_get=http_get, sleep=sleep
        )
        if unsupported:
            result.export_unsupported = True
            result.failed.append(err or "area: /export not supported")
            print(f"  FAIL: {result.failed[-1]}")
        elif err:
            result.failed.append(err)
            print(f"  FAIL: {err}")
        else:
            assert path is not None
            result.ok_paths.append(path)
            print(f"  wrote {path} ({path.stat().st_size} bytes)")
    else:
        # Still request the area layer so the user can Save As.
        print(f"Loading area bbox (manual save required)…")
        load_url = load_and_zoom_url(host, port, bbox)
        try:
            resp = _get(http_get, load_url, timeout=120.0)
            if resp.status_code != 200:
                result.failed.append(
                    f"area: load_and_zoom HTTP {resp.status_code} while preparing manual save"
                )
            else:
                print("  area layer requested; save manually after /export fallback message.")
        except CaptureError as exc:
            result.failed.append(f"area: {exc}")

    if result.export_unsupported:
        print_manual_export_fallback(expected_manual)

    return result


def print_next_steps(bbox: BBox) -> None:
    print(
        "\nNext steps (this script does not do these):\n"
        "  1. Fetch NVDB segmentert for the same bbox and save to\n"
        "     tests/fixtures/steep_roads/nvdb/area.json\n"
        f"     (WGS84 left,bottom,right,top = "
        f"{bbox.left},{bbox.bottom},{bbox.right},{bbox.top})\n"
        "  2. Run the plugin/core pipeline against the fixtures and record\n"
        "     real gradients/signs in tests/fixtures/steep_roads/README.md\n"
        "     (SteepRoadFixtureDumpTest / Step 3 of the testing prompt).\n"
        "This utility never uploads to OpenStreetMap.\n"
    )


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Load steep-road OSM fixtures into a running JOSM via Remote Control "
            "and export .osm files with GET /export (r19425+). Never uploads."
        )
    )
    p.add_argument(
        "--ways",
        default=",".join(str(w) for w in DEFAULT_WAYS),
        help="Comma-separated OSM way ids (default: the three Innlandet steep ways)",
    )
    p.add_argument(
        "--bbox",
        required=True,
        help="Area bbox as left,bottom,right,top (JOSM /load_and_zoom order)",
    )
    p.add_argument("--host", default=DEFAULT_HOST, help="Remote Control host")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help="Remote Control port")
    p.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_OUT_DIR,
        help=f"Directory for .osm outputs (default: {DEFAULT_OUT_DIR})",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        ways = parse_ways(args.ways)
        bbox = parse_bbox(args.bbox)
    except CaptureError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    try:
        result = run_capture(
            ways=ways,
            bbox=bbox,
            host=args.host,
            port=args.port,
            out_dir=args.out_dir,
        )
    except CaptureError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if result.failed:
        print("\nCompleted with failures:", file=sys.stderr)
        for item in result.failed:
            print(f"  - {item}", file=sys.stderr)
        if result.ok_paths:
            print("Partial output written:", file=sys.stderr)
            for path in result.ok_paths:
                print(f"  - {path}", file=sys.stderr)
        print_next_steps(bbox)
        return 1

    print("\nAll loads/exports succeeded.")
    print_next_steps(bbox)
    return 0


if __name__ == "__main__":
    sys.exit(main())
