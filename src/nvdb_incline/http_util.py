"""Rate-limited, disk-cached HTTP for Overpass and NVDB (read-only).

OpenStreetMap hosts are blocked. Only Overpass mirrors and NVDB are allowed.
"""

from __future__ import annotations

import hashlib
import json
import logging
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlparse

import requests

from nvdb_incline.config import NVDB_X_CLIENT, USER_AGENT, Settings

log = logging.getLogger(__name__)

BLOCKED_HOST_MARKERS = (
    "openstreetmap.org",
    "osm.org",
)
ALLOWED_NVDB_HOST = "nvdbapiles.atlas.vegvesen.no"


class HttpError(RuntimeError):
    pass


class ForbiddenHostError(HttpError):
    pass


def _hostname(url: str) -> str:
    return (urlparse(url).hostname or "").lower()


def assert_url_allowed(url: str, settings: Settings | None = None) -> None:
    """Refuse OSM.org and any host outside Overpass/NVDB allow-list."""
    settings = settings or Settings()
    host = _hostname(url)
    if not host:
        raise ForbiddenHostError(f"refusing URL without host: {url}")
    if any(marker in host for marker in BLOCKED_HOST_MARKERS):
        raise ForbiddenHostError(
            f"refusing OpenStreetMap host {host!r}; this tool never talks to osm.org"
        )
    overpass_host = _hostname(settings.overpass_url)
    if host == ALLOWED_NVDB_HOST or host.endswith("." + ALLOWED_NVDB_HOST):
        return
    if overpass_host and (host == overpass_host or host.endswith("." + overpass_host)):
        return
    if host in settings.extra_overpass_hosts:
        return
    raise ForbiddenHostError(
        f"refusing host {host!r}; only Overpass mirrors and NVDB are allowed"
    )


class RateLimitedSession:
    """Shared GET/POST with per-host pacing and optional disk cache."""

    def __init__(
        self,
        *,
        interval_s: float = 0.5,
        cache_dir: str | Path | None = None,
        fixture_dir: str | Path | None = None,
        timeout: float = 120.0,
        session: requests.Session | None = None,
        settings: Settings | None = None,
    ) -> None:
        self.interval_s = interval_s
        self.timeout = timeout
        self.settings = settings or Settings()
        self._session = session or requests.Session()
        self._session.headers.update({"User-Agent": USER_AGENT})
        self._last_call = 0.0
        self.cache_dir = Path(cache_dir) if cache_dir else None
        self.fixture_dir = Path(fixture_dir) if fixture_dir else None
        if self.cache_dir:
            self.cache_dir.mkdir(parents=True, exist_ok=True)

    def get_json(
        self,
        url: str,
        params: dict[str, Any] | None = None,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any:
        assert_url_allowed(url, self.settings)
        key = cache_key or self._key("GET", url, params, None)
        cached = self._load_cache(key)
        if cached is not None:
            return cached
        fixture = self._load_fixture(key)
        if fixture is not None:
            return fixture
        self._pace()
        resp = self._session.get(
            url, params=params, headers=headers, timeout=self.timeout
        )
        if resp.status_code >= 400:
            raise HttpError(f"GET {url} -> {resp.status_code}: {resp.text[:500]}")
        data = resp.json()
        self._save_cache(key, data)
        return data

    def post_text(
        self,
        url: str,
        body: str,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any:
        assert_url_allowed(url, self.settings)
        key = cache_key or self._key("POST", url, None, body)
        cached = self._load_cache(key)
        if cached is not None:
            return cached
        fixture = self._load_fixture(key)
        if fixture is not None:
            return fixture
        self._pace()
        hdrs = {"Content-Type": "application/x-www-form-urlencoded"}
        if headers:
            hdrs.update(headers)
        resp = self._session.post(
            url, data={"data": body}, headers=hdrs, timeout=self.timeout
        )
        if resp.status_code >= 400:
            raise HttpError(f"POST {url} -> {resp.status_code}: {resp.text[:500]}")
        ctype = resp.headers.get("Content-Type", "")
        if "json" in ctype or resp.text.lstrip().startswith("{"):
            data = resp.json()
        else:
            data = {"_raw": resp.text}
        self._save_cache(key, data)
        return data

    def _pace(self) -> None:
        now = time.monotonic()
        wait = self.interval_s - (now - self._last_call)
        if wait > 0:
            time.sleep(wait)
        self._last_call = time.monotonic()

    def _key(
        self,
        method: str,
        url: str,
        params: dict[str, Any] | None,
        body: str | None,
    ) -> str:
        payload = method + "\n" + url + "\n"
        if params:
            payload += urlencode(sorted((str(k), str(v)) for k, v in params.items()))
        if body:
            payload += "\n" + body
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def _cache_path(self, key: str) -> Path | None:
        if not self.cache_dir:
            return None
        return self.cache_dir / f"{key}.json"

    def _load_cache(self, key: str) -> Any | None:
        path = self._cache_path(key)
        if path is None or not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            log.warning("corrupt cache entry %s", path)
            return None

    def _save_cache(self, key: str, data: Any) -> None:
        path = self._cache_path(key)
        if path is None:
            return
        path.write_text(json.dumps(data), encoding="utf-8")

    def _load_fixture(self, key: str) -> Any | None:
        if not self.fixture_dir:
            return None
        for candidate in self.fixture_dir.glob(f"*{key}*"):
            return json.loads(candidate.read_text(encoding="utf-8"))
        named = self.fixture_dir / f"{key}.json"
        if named.exists():
            return json.loads(named.read_text(encoding="utf-8"))
        return None


class FixtureHttp:
    """Offline HTTP stand-in that only serves named JSON fixtures."""

    def __init__(
        self, fixture_map: dict[str, Path], settings: Settings | None = None
    ) -> None:
        self.fixture_map = fixture_map
        self.settings = settings or Settings()

    def get_json(
        self,
        url: str,
        params: dict[str, Any] | None = None,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any:
        return self._load(cache_key or url)

    def post_text(
        self,
        url: str,
        body: str,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any:
        return self._load(cache_key or url)

    def _load(self, key: str) -> Any:
        if key not in self.fixture_map:
            raise HttpError(
                f"offline fixture missing for key={key!r}; "
                "tests must not hit the network"
            )
        return json.loads(self.fixture_map[key].read_text(encoding="utf-8"))


def nvdb_headers() -> dict[str, str]:
    return {
        "Accept": "application/json",
        "X-Client": NVDB_X_CLIENT,
        "User-Agent": USER_AGENT,
    }


def build_sessions(settings: Settings) -> tuple[RateLimitedSession, RateLimitedSession]:
    overpass = RateLimitedSession(
        interval_s=settings.overpass_interval_s,
        cache_dir=Path(settings.cache_dir) / "overpass" if settings.cache_dir else None,
        fixture_dir=settings.fixture_dir,
        settings=settings,
    )
    nvdb = RateLimitedSession(
        interval_s=settings.nvdb_interval_s,
        cache_dir=Path(settings.cache_dir) / "nvdb" if settings.cache_dir else None,
        fixture_dir=settings.fixture_dir,
        settings=settings,
    )
    nvdb._session.headers.update(nvdb_headers())
    return overpass, nvdb
