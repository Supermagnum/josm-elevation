"""Snow-chain advisory heuristic tests against synthetic profiles."""

from __future__ import annotations

from nvdb_incline.chain_advisory import advise_chain_points, cluster_points
from nvdb_incline.config import Settings
from nvdb_incline.models import ChainPoint, NvdbLink
from shapely.geometry import LineString
from tests.helpers import constant_slope, flat_profile, profile_from_dz


def test_flat_profile_triggers_nothing():
    p = flat_profile(400.0)
    pts = advise_chain_points(p, way_id=1, links=[], winter_objects=[], settings=Settings())
    # Pass detector and sustained grade should both stay quiet.
    assert pts == []


def test_mountain_pass_triggers_fit_and_remove():
    # Climb 12% for 250m, descend 12% for 250m.
    distances = list(range(0, 501, 25))
    elevations = []
    for d in distances:
        if d <= 250:
            elevations.append(200 + d * 0.12)
        else:
            elevations.append(200 + 30 - (d - 250) * 0.12)
    p = profile_from_dz(distances, elevations)
    settings = Settings(chain_gradient_pct=6.0, chain_min_distance_m=200.0)
    pts = advise_chain_points(p, way_id=42, links=[], winter_objects=[], settings=settings)
    kinds = {pt.kind for pt in pts}
    assert "fit" in kinds
    assert "remove" in kinds
    # Expect points near both ends (approaches).
    xs = sorted(pt.x for pt in pts)
    assert xs[0] <= distances[0] + 1
    assert xs[-1] >= distances[-1] - 1


def test_sustained_climb_fit_at_start():
    p = constant_slope(300.0, 8.0, step_m=10.0)
    settings = Settings(chain_gradient_pct=6.0, chain_min_distance_m=200.0)
    pts = advise_chain_points(p, way_id=7, links=[], winter_objects=[], settings=settings)
    assert any(pt.kind == "fit" for pt in pts)


def test_cluster_deduplicates_nearby_points():
    pts = [
        ChainPoint(0, 0, "fit", "a"),
        ChainPoint(10, 0, "fit", "b"),
        ChainPoint(200, 0, "remove", "c"),
    ]
    clustered = cluster_points(pts, cluster_m=50.0)
    assert len(clustered) == 2


def test_tunnel_portal_points():
    line = LineString([(0, 0, 10), (100, 0, 10)])
    link = NvdbLink(
        veglenkesekvensid=1,
        kortform="0-1@1",
        type="HOVED",
        type_veg="Tunnel",
        medium="T",
        kommune=1,
        lengde=100.0,
        wkt="LINESTRING Z (0 0 10, 100 0 10)",
        srid=5973,
        line_utm=line,
    )
    p = flat_profile(100.0)
    pts = advise_chain_points(p, way_id=1, links=[link], winter_objects=[], settings=Settings())
    assert any("tunnel" in pt.reason.lower() for pt in pts)
