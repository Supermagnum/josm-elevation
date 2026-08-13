"""Snow-chain fit/remove candidate points from elevation and NVDB context.

Output tags are deliberately not established Norwegian OSM tagging:
chain_advisory=fit|remove plus a note asking for field verification.
"""

from __future__ import annotations

from math import hypot

from shapely.geometry import Point

from nvdb_incline.config import Settings
from nvdb_incline.geo import xy_at_profile_distance
from nvdb_incline.gradient import window_gradients_pct
from nvdb_incline.models import (
    ChainKind,
    ChainPoint,
    ElevationSample,
    NvdbLink,
    WinterObject,
)


def advise_chain_points(
    profile: list[ElevationSample],
    way_id: int | None,
    links: list[NvdbLink],
    winter_objects: list[WinterObject],
    settings: Settings | None = None,
) -> list[ChainPoint]:
    settings = settings or Settings()
    if len(profile) < 2:
        return []

    points: list[ChainPoint] = []
    points.extend(_from_sustained_grade(profile, way_id, settings))
    points.extend(_from_pass(profile, way_id, settings))
    points.extend(_from_tunnel_portals(links, way_id))
    points.extend(_from_winter_objects(winter_objects, way_id))
    return points


def cluster_points(
    points: list[ChainPoint], cluster_m: float = 75.0
) -> list[ChainPoint]:
    """Greedy clustering: one representative per cluster (centroid-nearest)."""
    remaining = list(points)
    clusters: list[list[ChainPoint]] = []
    while remaining:
        seed = remaining.pop(0)
        group = [seed]
        i = 0
        while i < len(remaining):
            if _dist(seed, remaining[i]) <= cluster_m:
                group.append(remaining.pop(i))
            else:
                i += 1
        # Absorb points close to any member (single-linkage within one pass).
        changed = True
        while changed:
            changed = False
            i = 0
            while i < len(remaining):
                if any(_dist(remaining[i], g) <= cluster_m for g in group):
                    group.append(remaining.pop(i))
                    changed = True
                else:
                    i += 1
        clusters.append(group)

    out: list[ChainPoint] = []
    for group in clusters:
        cx = sum(p.x for p in group) / len(group)
        cy = sum(p.y for p in group) / len(group)
        rep = min(group, key=lambda p: hypot(p.x - cx, p.y - cy))
        kinds = {p.kind for p in group}
        kind: ChainKind
        if kinds == {"fit"}:
            kind = "fit"
        elif kinds == {"remove"}:
            kind = "remove"
        else:
            kind = "fit;remove"
        reasons = "; ".join(sorted({p.reason for p in group}))
        out.append(
            ChainPoint(
                x=rep.x,
                y=rep.y,
                kind=kind,
                reason=reasons,
                way_id=rep.way_id,
            )
        )
    return out


def _from_sustained_grade(
    profile: list[ElevationSample],
    way_id: int | None,
    settings: Settings,
) -> list[ChainPoint]:
    stretches = _sustained_stretches(
        profile,
        min_abs_pct=settings.chain_gradient_pct,
        min_length_m=settings.chain_min_distance_m,
        window_m=settings.rolling_window_m,
    )
    points: list[ChainPoint] = []
    for start_m, end_m, mean_pct in stretches:
        if mean_pct >= settings.chain_gradient_pct:
            x, y = xy_at_profile_distance(profile, start_m)
            points.append(
                ChainPoint(
                    x=x,
                    y=y,
                    kind="fit",
                    reason=(
                        f"sustained climb {mean_pct:.1f}% over "
                        f"{end_m - start_m:.0f}m"
                    ),
                    way_id=way_id,
                )
            )
        elif mean_pct <= -settings.chain_gradient_pct:
            x, y = xy_at_profile_distance(profile, end_m)
            points.append(
                ChainPoint(
                    x=x,
                    y=y,
                    kind="remove",
                    reason=(
                        f"end of sustained descent {mean_pct:.1f}% over "
                        f"{end_m - start_m:.0f}m"
                    ),
                    way_id=way_id,
                )
            )
    return points


def _from_pass(
    profile: list[ElevationSample],
    way_id: int | None,
    settings: Settings,
) -> list[ChainPoint]:
    """Local elevation maximum with steep approaches: mountain-pass context."""
    if len(profile) < 3:
        return []
    z = [p.elevation_m for p in profile]
    zmax = max(z)
    zmin = min(z)
    if zmax - zmin < 15.0:
        return []
    idx = z.index(zmax)
    # Require the peak not to sit at an endpoint (that is just a one-sided climb).
    if idx <= 0 or idx >= len(profile) - 1:
        return []
    left = profile[idx].elevation_m - profile[0].elevation_m
    right = profile[idx].elevation_m - profile[-1].elevation_m
    if left < 8.0 or right < 8.0:
        return []
    # Approaches should themselves be steep enough to matter.
    left_run = profile[idx].distance_m - profile[0].distance_m
    right_run = profile[-1].distance_m - profile[idx].distance_m
    if left_run < settings.chain_min_distance_m * 0.5:
        return []
    if right_run < settings.chain_min_distance_m * 0.5:
        return []
    left_g = 100.0 * left / left_run if left_run else 0.0
    right_g = 100.0 * right / right_run if right_run else 0.0
    if left_g < settings.chain_gradient_pct * 0.7 and right_g < settings.chain_gradient_pct * 0.7:
        return []
    # Fit at both bottoms (start of each climb toward the pass).
    return [
        ChainPoint(
            x=profile[0].x,
            y=profile[0].y,
            kind="fit",
            reason="approach to local elevation maximum (pass)",
            way_id=way_id,
        ),
        ChainPoint(
            x=profile[-1].x,
            y=profile[-1].y,
            kind="remove",
            reason="descent from local elevation maximum (pass)",
            way_id=way_id,
        ),
        ChainPoint(
            x=profile[-1].x,
            y=profile[-1].y,
            kind="fit",
            reason="opposite-direction approach to pass",
            way_id=way_id,
        ),
        ChainPoint(
            x=profile[0].x,
            y=profile[0].y,
            kind="remove",
            reason="opposite-direction descent from pass",
            way_id=way_id,
        ),
    ]


def _from_tunnel_portals(links: list[NvdbLink], way_id: int | None) -> list[ChainPoint]:
    points: list[ChainPoint] = []
    ordered = sorted(links, key=lambda lk: (lk.veglenkesekvensid, lk.startposisjon))
    for i, lk in enumerate(ordered):
        if not lk.is_tunnel:
            continue
        coords = list(lk.line_utm.coords)
        if len(coords) < 2:
            continue
        prev_t = ordered[i - 1].is_tunnel if i > 0 else False
        next_t = ordered[i + 1].is_tunnel if i + 1 < len(ordered) else False
        if not prev_t:
            points.append(
                ChainPoint(
                    x=float(coords[0][0]),
                    y=float(coords[0][1]),
                    kind="fit",
                    reason="NVDB tunnel portal",
                    way_id=way_id,
                )
            )
        if not next_t:
            points.append(
                ChainPoint(
                    x=float(coords[-1][0]),
                    y=float(coords[-1][1]),
                    kind="remove",
                    reason="NVDB tunnel portal",
                    way_id=way_id,
                )
            )
    return points


def _from_winter_objects(
    winter_objects: list[WinterObject], way_id: int | None
) -> list[ChainPoint]:
    points: list[ChainPoint] = []
    for obj in winter_objects:
        kind: ChainKind = "fit"
        reason = f"NVDB {obj.type_name}"
        if obj.point is not None:
            points.append(
                ChainPoint(
                    x=float(obj.point.x),
                    y=float(obj.point.y),
                    kind=kind,
                    reason=reason,
                    way_id=way_id,
                )
            )
            continue
        if obj.line is not None and not obj.line.is_empty:
            coords = list(obj.line.coords)
            points.append(
                ChainPoint(
                    x=float(coords[0][0]),
                    y=float(coords[0][1]),
                    kind="fit",
                    reason=reason + " start",
                    way_id=way_id,
                )
            )
            points.append(
                ChainPoint(
                    x=float(coords[-1][0]),
                    y=float(coords[-1][1]),
                    kind="remove",
                    reason=reason + " end",
                    way_id=way_id,
                )
            )
    return points


def _sustained_stretches(
    profile: list[ElevationSample],
    min_abs_pct: float,
    min_length_m: float,
    window_m: float,
) -> list[tuple[float, float, float]]:
    windows = window_gradients_pct(profile, window_m)
    if not windows:
        return []
    stretches: list[tuple[float, float, float]] = []
    cur_start: float | None = None
    cur_end: float | None = None
    cur_sign = 0
    acc = 0.0
    n = 0

    def flush() -> None:
        nonlocal cur_start, cur_end, acc, n, cur_sign
        if cur_start is None or cur_end is None or n == 0:
            cur_start = cur_end = None
            acc = 0.0
            n = 0
            cur_sign = 0
            return
        mean = acc / n
        if cur_end - cur_start >= min_length_m and abs(mean) >= min_abs_pct:
            stretches.append((cur_start, cur_end, mean))
        cur_start = cur_end = None
        acc = 0.0
        n = 0
        cur_sign = 0

    for start_m, end_m, pct in windows:
        sign = 1 if pct >= min_abs_pct else (-1 if pct <= -min_abs_pct else 0)
        if sign == 0:
            flush()
            continue
        if cur_start is None:
            cur_start, cur_end, cur_sign = start_m, end_m, sign
            acc, n = pct, 1
            continue
        if sign != cur_sign:
            flush()
            cur_start, cur_end, cur_sign = start_m, end_m, sign
            acc, n = pct, 1
            continue
        cur_end = end_m
        acc += pct
        n += 1
    flush()
    return stretches


def _dist(a: ChainPoint, b: ChainPoint) -> float:
    return hypot(a.x - b.x, a.y - b.y)
