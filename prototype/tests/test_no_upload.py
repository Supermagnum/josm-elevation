"""Safety regression: no OSM write / upload / OAuth code paths."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src" / "nvdb_incline"

FORBIDDEN_PATTERNS = [
    re.compile(r"api\.openstreetmap\.org", re.I),
    re.compile(r"master\.apis\.dev\.openstreetmap\.org", re.I),
    re.compile(r"/api/0\.6/changeset", re.I),
    re.compile(r"changeset/create", re.I),
    re.compile(r"oauthlib|OAuth2Session|osm_auth|/oauth/", re.I),
    re.compile(r"access_token\s*=", re.I),
    re.compile(r"consumer_secret", re.I),
    re.compile(r"request_token", re.I),
    re.compile(r"""['"]--force-upload['"]""", re.I),
    re.compile(r"requests\.(put|post)\([^)]*openstreetmap", re.I),
]


def test_no_osm_write_or_oauth_in_source():
    offenders: list[str] = []
    for path in ROOT.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for pat in FORBIDDEN_PATTERNS:
            if pat.search(text):
                offenders.append(f"{path.relative_to(ROOT.parent.parent)}: {pat.pattern}")
    assert offenders == [], "Forbidden OSM write/OAuth patterns:\n" + "\n".join(offenders)


def test_upload_attribute_false_in_osm_writer():
    text = (ROOT / "osm_writer.py").read_text(encoding="utf-8")
    assert 'upload": "false"' in text or "upload='false'" in text or '"upload": "false"' in text


def test_osm_hosts_are_blocked():
    from nvdb_incline.config import Settings
    from nvdb_incline.http_util import ForbiddenHostError, assert_url_allowed
    import pytest

    with pytest.raises(ForbiddenHostError):
        assert_url_allowed("https://api.openstreetmap.org/api/0.6/map", Settings())
    with pytest.raises(ForbiddenHostError):
        assert_url_allowed("https://www.openstreetmap.org/api/0.6/changeset/create", Settings())
    # Allowed hosts must pass.
    assert_url_allowed("https://overpass-api.de/api/interpreter", Settings())
    assert_url_allowed(
        "https://nvdbapiles.atlas.vegvesen.no/vegnett/api/v4/veglenkesekvenser/segmentert",
        Settings(),
    )
