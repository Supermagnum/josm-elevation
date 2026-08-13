"""Tests for tools/capture_fixtures.py — no live JOSM required."""

from __future__ import annotations

import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import capture_fixtures as cf  # noqa: E402


MINIMAL_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="mock-josm">
  <node id="1" lat="61.5" lon="10.1" version="1"/>
  <way id="764390363" version="1">
    <nd ref="1"/>
    <nd ref="1"/>
  </way>
</osm>
"""


# --- Pure URL / parse unit tests --------------------------------------------


def test_parse_bbox_josm_order():
    bbox = cf.parse_bbox("10.05,61.50,10.20,61.58")
    assert bbox.left == pytest.approx(10.05)
    assert bbox.bottom == pytest.approx(61.50)
    assert bbox.right == pytest.approx(10.20)
    assert bbox.top == pytest.approx(61.58)


def test_parse_bbox_rejects_bad_order():
    with pytest.raises(cf.CaptureError):
        cf.parse_bbox("10.20,61.50,10.05,61.58")  # left > right


def test_parse_ways():
    assert cf.parse_ways("764390363,757907237,330233844") == [
        764390363,
        757907237,
        330233844,
    ]


def test_load_object_url_query():
    url = cf.load_object_url("localhost", 8111, 764390363)
    parsed = urlparse(url)
    assert parsed.scheme == "http"
    assert parsed.netloc == "localhost:8111"
    assert parsed.path == "/load_object"
    q = parse_qs(parsed.query)
    assert q["new_layer"] == ["true"]
    assert q["objects"] == ["w764390363"]
    assert q["relation_members"] == ["true"]
    assert q["layer_name"] == ["steep_way_764390363"]


def test_load_and_zoom_url_query():
    bbox = cf.parse_bbox("10.05,61.50,10.20,61.58")
    url = cf.load_and_zoom_url("127.0.0.1", 8111, bbox)
    parsed = urlparse(url)
    assert parsed.path == "/load_and_zoom"
    q = parse_qs(parsed.query)
    assert q["left"] == ["10.05"]
    assert q["bottom"] == ["61.5"]
    assert q["right"] == ["10.2"]
    assert q["top"] == ["61.58"]
    assert q["new_layer"] == ["true"]
    assert q["layer_name"] == ["steep_area"]


def test_version_and_export_urls():
    assert cf.version_url("localhost", 8111) == "http://localhost:8111/version"
    assert cf.export_url("localhost", 8111) == "http://localhost:8111/export"


def test_output_paths():
    out = Path("tests/fixtures/steep_roads/osm")
    assert cf.way_output_path(out, 42) == out / "steep_way_42.osm"
    assert cf.area_output_path(out) == out / "steep_area.osm"


def test_is_well_formed_osm_xml():
    assert cf.is_well_formed_osm_xml(MINIMAL_OSM)
    assert not cf.is_well_formed_osm_xml("")
    assert not cf.is_well_formed_osm_xml("   ")
    assert not cf.is_well_formed_osm_xml("<html></html>")
    assert not cf.is_well_formed_osm_xml("not xml")


# --- Mock Remote Control HTTP server ----------------------------------------


class MockJosmState:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, list[str]]]] = []
        self.export_bodies: list[str] = []
        self.export_status = 200
        self.version_body = "JOSM mock 1.0"
        self.version_status = 200
        self.load_status = 200
        self.refuse_version = False


def _make_handler(state: MockJosmState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):  # noqa: A003
            return

        def do_GET(self):  # noqa: N802
            parsed = urlparse(self.path)
            q = parse_qs(parsed.query)
            state.calls.append((parsed.path, q))

            if parsed.path == "/version":
                if state.refuse_version:
                    self.close_connection = True
                    return
                body = state.version_body.encode()
                self.send_response(state.version_status)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if parsed.path in ("/load_object", "/load_and_zoom"):
                body = b"OK"
                self.send_response(state.load_status)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if parsed.path == "/export":
                if state.export_status == 404:
                    self.send_response(404)
                    self.end_headers()
                    return
                body_text = ""
                if state.export_bodies:
                    body_text = state.export_bodies.pop(0)
                raw = body_text.encode()
                self.send_response(state.export_status)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
                return

            self.send_response(404)
            self.end_headers()

    return Handler


@pytest.fixture
def mock_josm():
    state = MockJosmState()
    server = HTTPServer(("127.0.0.1", 0), _make_handler(state))
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield state, port
    finally:
        server.shutdown()


def test_preflight_then_load_and_export_happy_path(mock_josm, tmp_path):
    state, port = mock_josm
    # First export poll empty, then valid XML for each of 3 ways + area = 4 successes
    # with one leading empty → 1 empty + 4 osm
    state.export_bodies = [
        "",
        MINIMAL_OSM,
        MINIMAL_OSM,
        MINIMAL_OSM,
        MINIMAL_OSM,
    ]
    sleeps: list[float] = []

    result = cf.run_capture(
        ways=[764390363, 757907237, 330233844],
        bbox=cf.parse_bbox("10.05,61.50,10.20,61.58"),
        host="127.0.0.1",
        port=port,
        out_dir=tmp_path,
        sleep=sleeps.append,
    )

    assert result.failed == []
    assert len(result.ok_paths) == 4
    paths = [p.name for p in result.ok_paths]
    assert paths == [
        "steep_way_764390363.osm",
        "steep_way_757907237.osm",
        "steep_way_330233844.osm",
        "steep_area.osm",
    ]
    for p in result.ok_paths:
        assert p.is_file() and p.stat().st_size > 0

    # /version first
    assert state.calls[0][0] == "/version"
    load_object_calls = [c for c in state.calls if c[0] == "/load_object"]
    assert len(load_object_calls) == 3
    assert load_object_calls[0][1]["objects"] == ["w764390363"]
    assert load_object_calls[1][1]["objects"] == ["w757907237"]
    assert load_object_calls[2][1]["objects"] == ["w330233844"]

    zoom_calls = [c for c in state.calls if c[0] == "/load_and_zoom"]
    assert len(zoom_calls) == 1
    assert zoom_calls[0][1]["left"] == ["10.05"]
    assert zoom_calls[0][1]["bottom"] == ["61.5"]
    assert zoom_calls[0][1]["right"] == ["10.2"]
    assert zoom_calls[0][1]["top"] == ["61.58"]
    assert zoom_calls[0][1]["new_layer"] == ["true"]
    assert zoom_calls[0][1]["layer_name"] == ["steep_area"]

    export_calls = [c for c in state.calls if c[0] == "/export"]
    assert len(export_calls) >= 4
    assert sleeps  # emptied body caused a poll delay


def test_export_empty_keeps_polling_then_fails(mock_josm, tmp_path):
    state, port = mock_josm
    state.export_bodies = ["", "", ""]  # never valid

    path, err, unsupported = cf.capture_way(
        "127.0.0.1",
        port,
        764390363,
        tmp_path,
        sleep=lambda _s: None,
    )
    # Override retries by calling poll_export directly for clearer assert
    assert path is None
    assert unsupported is False
    assert err is not None
    assert "export failed" in err


def test_poll_export_distinguishes_empty_vs_ok(mock_josm):
    state, port = mock_josm
    state.export_bodies = ["", MINIMAL_OSM]
    outcome = cf.poll_export(
        "127.0.0.1",
        port,
        retries=5,
        delay_s=0,
        sleep=lambda _s: None,
    )
    assert outcome.kind == "ok"
    assert "<osm" in outcome.body


def test_pre_r19425_export_404_fallback(mock_josm, tmp_path, capsys):
    state, port = mock_josm
    state.export_status = 404

    result = cf.run_capture(
        ways=[764390363],
        bbox=cf.parse_bbox("10.05,61.50,10.20,61.58"),
        host="127.0.0.1",
        port=port,
        out_dir=tmp_path,
        sleep=lambda _s: None,
    )
    assert result.export_unsupported is True
    assert result.ok_paths == []
    assert any("404" in f or "r19425" in f for f in result.failed)
    # Loads still attempted
    assert any(c[0] == "/load_object" for c in state.calls)
    assert any(c[0] == "/load_and_zoom" for c in state.calls)
    out = capsys.readouterr().out
    assert "r19425" in out
    assert "Save As" in out


def test_preflight_connection_refused_clear_error():
    with pytest.raises(cf.CaptureError) as exc:
        cf.preflight_version(
            "127.0.0.1",
            1,  # almost certainly closed
            retries=2,
            delay_s=0,
            timeout_s=0.2,
            sleep=lambda _s: None,
        )
    msg = str(exc.value)
    assert "Remote Control" in msg
    assert "Preferences" in msg


def test_main_non_zero_on_unreachable(tmp_path):
    code = cf.main(
        [
            "--ways",
            "1",
            "--bbox",
            "10.05,61.50,10.20,61.58",
            "--host",
            "127.0.0.1",
            "--port",
            "1",
            "--out-dir",
            str(tmp_path),
        ]
    )
    assert code == 1


def test_partial_failure_continues_other_ways(mock_josm, tmp_path):
    state, port = mock_josm
    import requests

    real_get = requests.get

    def flaky_get(url, timeout=None):
        if "/load_object" in url and "w111" in url:
            from requests import Response

            bad = Response()
            bad.status_code = 500
            bad._content = b"boom"
            return bad
        return real_get(url, timeout=timeout)

    state.export_bodies = [MINIMAL_OSM, MINIMAL_OSM]
    result = cf.run_capture(
        ways=[111, 222],
        bbox=cf.parse_bbox("10.05,61.50,10.20,61.58"),
        host="127.0.0.1",
        port=port,
        out_dir=tmp_path,
        http_get=flaky_get,
        sleep=lambda _s: None,
    )
    assert any("way 111" in f for f in result.failed)
    assert any(p.name == "steep_way_222.osm" for p in result.ok_paths)
    assert any(p.name == "steep_area.osm" for p in result.ok_paths)
