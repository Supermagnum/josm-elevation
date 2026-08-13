"""NVDB API v4 read client (vegnett + datakatalog). Never writes."""

from __future__ import annotations

import logging
from typing import Any, Protocol

from shapely.geometry import LineString, Point

from nvdb_incline.config import Settings
from nvdb_incline.geo import parse_wkt_line, lonlat_to_utm
from nvdb_incline.http_util import nvdb_headers
from nvdb_incline.models import AreaOfInterest, NvdbLink, WinterObject

log = logging.getLogger(__name__)


class HttpClient(Protocol):
    def get_json(
        self,
        url: str,
        params: dict[str, Any] | None = None,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any: ...


SEGMENTERT_PATH = "/vegnett/api/v4/veglenkesekvenser/segmentert"
DATAKATALOG_PATH = "/datakatalog/api/v1/vegobjekttyper"
OMRADER_KOMMUNE_PATH = "/omrader/api/v4/kommuner"


def fetch_segmented_links(
    http: HttpClient,
    area: AreaOfInterest,
    settings: Settings | None = None,
    *,
    cache_key_prefix: str = "nvdb_segmentert",
) -> list[NvdbLink]:
    settings = settings or Settings()
    base = settings.nvdb_base.rstrip("/") + SEGMENTERT_PATH
    params: dict[str, Any] = {
        "srid": 5973,
        "antall": 1000,
        "inkluderAntall": "false",
    }
    if area.kind == "kommune" and area.kommune is not None:
        params["kommune"] = area.kommune
    else:
        # kartutsnitt in WGS84: lonMin,latMin,lonMax,latMax with srid=4326
        # But NVDB returns geometry in the same srid as kartutsnitt.
        # Prefer UTM33 bbox for metres precision.
        from nvdb_incline.geo import lonlat_to_utm

        x0, y0 = lonlat_to_utm(area.min_lon, area.min_lat)
        x1, y1 = lonlat_to_utm(area.max_lon, area.max_lat)
        params["kartutsnitt"] = f"{min(x0, x1)},{min(y0, y1)},{max(x0, x1)},{max(y0, y1)}"
        params["srid"] = 5973

    objects: list[dict[str, Any]] = []
    start: str | None = None
    page = 0
    while True:
        page_params = dict(params)
        if start:
            page_params["start"] = start
        data = http.get_json(
            base,
            page_params,
            headers=nvdb_headers(),
            cache_key=f"{cache_key_prefix}_p{page}",
        )
        batch = data.get("objekter") or []
        objects.extend(batch)
        meta = data.get("metadata") or {}
        neste = meta.get("neste") or {}
        start = neste.get("start")
        if not batch or not start:
            break
        page += 1
        if page > 500:
            log.warning("NVDB pagination safety stop after 500 pages")
            break

    links = [parse_nvdb_link(obj) for obj in objects]
    links = [lk for lk in links if lk is not None]
    log.info("NVDB: %d segmented links", len(links))
    return links


def parse_nvdb_link(obj: dict[str, Any]) -> NvdbLink | None:
    geom = obj.get("geometri") or {}
    wkt = geom.get("wkt")
    if not wkt:
        return None
    srid = int(geom.get("srid") or 5973)
    try:
        line = parse_wkt_line(wkt, srid)
    except Exception:
        log.debug("failed to parse WKT for %s", obj.get("kortform"), exc_info=True)
        return None
    if line.is_empty or len(line.coords) < 2:
        return None
    return NvdbLink(
        veglenkesekvensid=int(obj.get("veglenkesekvensid") or 0),
        kortform=str(obj.get("kortform") or ""),
        type=str(obj.get("type") or ""),
        type_veg=str(obj.get("typeVeg") or ""),
        medium=(geom.get("medium") or obj.get("medium")),
        kommune=obj.get("kommune"),
        lengde=float(obj.get("lengde") or geom.get("lengde") or line.length),
        wkt=wkt,
        srid=srid,
        line_utm=line,
        startposisjon=float(obj.get("startposisjon") or 0.0),
        sluttposisjon=float(obj.get("sluttposisjon") or 1.0),
    )


def fetch_kommune_bbox(
    http: HttpClient,
    kommune: int,
    settings: Settings | None = None,
) -> tuple[float, float, float, float] | None:
    """Return (min_lon, min_lat, max_lon, max_lat) if Områder API provides geometry."""
    settings = settings or Settings()
    url = settings.nvdb_base.rstrip("/") + f"{OMRADER_KOMMUNE_PATH}/{kommune}"
    try:
        data = http.get_json(
            url,
            {"srid": 4326},
            headers=nvdb_headers(),
            cache_key=f"nvdb_kommune_{kommune}",
        )
    except Exception:
        log.warning("could not resolve kommune %s bbox", kommune)
        return None
    return _bbox_from_omrade(data)


def _bbox_from_omrade(data: dict[str, Any]) -> tuple[float, float, float, float] | None:
    geom = data.get("geometri") or data.get("geometry") or {}
    wkt = geom.get("wkt")
    if not wkt:
        return None
    from shapely import wkt as shapely_wkt

    g = shapely_wkt.loads(wkt)
    minx, miny, maxx, maxy = g.bounds
    srid = int(geom.get("srid") or 4326)
    if srid in {5973, 25833, 32633, 6173}:
        from nvdb_incline.geo import utm_to_lonlat

        lon0, lat0 = utm_to_lonlat(minx, miny)
        lon1, lat1 = utm_to_lonlat(maxx, maxy)
        return (min(lon0, lon1), min(lat0, lat1), max(lon0, lon1), max(lat0, lat1))
    return (minx, miny, maxx, maxy)


WINTER_KEYWORDS = ("kjetting", "vinterstengt", "vinterstenging", "kolonnekjøring")


def search_winter_object_types(
    http: HttpClient,
    settings: Settings | None = None,
    *,
    cache_key: str = "nvdb_datakatalog_types",
) -> list[dict[str, Any]]:
    settings = settings or Settings()
    url = settings.nvdb_base.rstrip("/") + DATAKATALOG_PATH
    data = http.get_json(url, headers=nvdb_headers(), cache_key=cache_key)
    types = data if isinstance(data, list) else data.get("vegobjekttyper") or data.get("objekter") or []
    hits: list[dict[str, Any]] = []
    for t in types:
        name = str(t.get("navn") or t.get("name") or "").lower()
        if any(k in name for k in WINTER_KEYWORDS):
            hits.append(t)
    log.info("datakatalog winter-related types: %d", len(hits))
    return hits


def fetch_winter_objects(
    http: HttpClient,
    area: AreaOfInterest,
    type_ids: list[int],
    settings: Settings | None = None,
) -> list[WinterObject]:
    """Best-effort fetch of winter-related vegobjekter for advisory context.

    If the vegobjekter endpoint is unavailable or empty, returns [].
    """
    settings = settings or Settings()
    out: list[WinterObject] = []
    for tid in type_ids:
        url = settings.nvdb_base.rstrip("/") + f"/vegobjekter/api/v4/{tid}"
        params: dict[str, Any] = {
            "srid": 5973,
            "inkluder": "geometri",
            "antall": 500,
            "inkluderAntall": "false",
        }
        if area.kind == "kommune" and area.kommune is not None:
            params["kommune"] = area.kommune
        else:
            x0, y0 = lonlat_to_utm(area.min_lon, area.min_lat)
            x1, y1 = lonlat_to_utm(area.max_lon, area.max_lat)
            params["kartutsnitt"] = (
                f"{min(x0, x1)},{min(y0, y1)},{max(x0, x1)},{max(y0, y1)}"
            )
        try:
            data = http.get_json(
                url,
                params,
                headers=nvdb_headers(),
                cache_key=f"nvdb_vegobjekt_{tid}",
            )
        except Exception:
            log.info("vegobjekter type %s not fetched", tid)
            continue
        for obj in data.get("objekter") or []:
            wo = _parse_winter_object(obj, tid)
            if wo:
                out.append(wo)
    return out


def _parse_winter_object(obj: dict[str, Any], type_id: int) -> WinterObject | None:
    geom = obj.get("geometri") or {}
    wkt = geom.get("wkt")
    point = None
    line = None
    if wkt:
        try:
            line_or_pt = parse_wkt_line(wkt, int(geom.get("srid") or 5973))
            if line_or_pt.geom_type == "LineString":
                line = line_or_pt
            else:
                c = list(line_or_pt.coords)[0]
                point = Point(c[0], c[1])
        except Exception:
            # Might be a POINT; try shapely directly.
            from shapely import wkt as shapely_wkt

            g = shapely_wkt.loads(wkt)
            if g.geom_type == "Point":
                if int(geom.get("srid") or 5973) == 4326:
                    x, y = lonlat_to_utm(g.x, g.y)
                    point = Point(x, y)
                else:
                    point = Point(g.x, g.y)
            elif g.geom_type == "LineString":
                line = parse_wkt_line(wkt, int(geom.get("srid") or 5973))
    props = obj.get("egenskaper") or []
    name = None
    for p in props:
        if str(p.get("navn") or "").lower() in {"navn", "name"}:
            name = str(p.get("verdi"))
    type_name = str(obj.get("metadata", {}).get("type", {}).get("navn") or f"type-{type_id}")
    if point is None and line is None:
        return None
    return WinterObject(
        type_id=type_id,
        type_name=type_name,
        point=point,
        line=line,
        name=name,
    )
