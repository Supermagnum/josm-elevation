"""Gradient calculation, OSM incline=* rounding, and way-split suggestions.

OSM incline=* is a signed percentage relative to the way's node order:
positive means uphill in the direction of the way, negative downhill.
See https://wiki.openstreetmap.org/wiki/Key:incline
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from nvdb_incline.geo import xy_at_profile_distance
from nvdb_incline.models import ElevationSample, GradientStats, SegmentSuggestion


def round_incline_pct(value: float) -> int:
    """Round to nearest integer percent (half away from zero)."""
    if value >= 0:
        return int(math.floor(value + 0.5))
    return int(math.ceil(value - 0.5))


def format_incline(value: float) -> str:
    """OSM incline=* percentage string, e.g. '8%', '-3%', '0%'."""
    return f"{round_incline_pct(value)}%"


def average_gradient_pct(profile: list[ElevationSample]) -> float:
    if len(profile) < 2:
        return 0.0
    run = profile[-1].distance_m - profile[0].distance_m
    if run <= 1e-9:
        return 0.0
    rise = profile[-1].elevation_m - profile[0].elevation_m
    return 100.0 * rise / run


def window_gradients_pct(
    profile: list[ElevationSample], window_m: float
) -> list[tuple[float, float, float]]:
    """Return (start_m, end_m, gradient_pct) for rolling windows along the profile."""
    if len(profile) < 2 or window_m <= 0:
        return []
    out: list[tuple[float, float, float]] = []
    n = len(profile)
    j = 0
    for i in range(n):
        target = profile[i].distance_m + window_m
        while j + 1 < n and profile[j].distance_m < target:
            j += 1
        if profile[j].distance_m < target - 1e-6:
            # Not enough remaining length; use the tail if it is still useful.
            if profile[-1].distance_m - profile[i].distance_m < min(10.0, window_m * 0.4):
                continue
            z1 = profile[i].elevation_m
            z2 = profile[-1].elevation_m
            run = profile[-1].distance_m - profile[i].distance_m
        else:
            z1 = profile[i].elevation_m
            z2 = _elevation_at(profile, target)
            run = window_m
        if run <= 1e-9:
            continue
        pct = 100.0 * (z2 - z1) / run
        end_m = min(profile[i].distance_m + run, profile[-1].distance_m)
        out.append((profile[i].distance_m, end_m, pct))
    return out


def max_sustained_gradient_pct(
    profile: list[ElevationSample], window_m: float = 50.0
) -> float:
    """Steepest rolling-window grade, signed (the window with largest |grade|)."""
    windows = window_gradients_pct(profile, window_m)
    if not windows:
        return average_gradient_pct(profile)
    best = windows[0][2]
    for _, _, pct in windows:
        if abs(pct) > abs(best):
            best = pct
    return best


def gradient_stats(
    profile: list[ElevationSample], window_m: float = 50.0
) -> GradientStats:
    avg = average_gradient_pct(profile)
    windows = window_gradients_pct(profile, window_m)
    if windows:
        vals = [w[2] for w in windows]
        min_w, max_w = min(vals), max(vals)
        max_sust = max_sustained_gradient_pct(profile, window_m)
    else:
        min_w = max_w = max_sust = avg
    length = (
        profile[-1].distance_m - profile[0].distance_m if len(profile) >= 2 else 0.0
    )
    return GradientStats(
        average_pct=avg,
        max_sustained_pct=max_sust,
        min_window_pct=min_w,
        max_window_pct=max_w,
        spread_pp=max_w - min_w,
        length_m=length,
    )


def _elevation_at(profile: list[ElevationSample], distance_m: float) -> float:
    if distance_m <= profile[0].distance_m:
        return profile[0].elevation_m
    for a, b in zip(profile, profile[1:]):
        if b.distance_m >= distance_m:
            span = b.distance_m - a.distance_m
            t = 0.0 if span <= 1e-9 else (distance_m - a.distance_m) / span
            return a.elevation_m + t * (b.elevation_m - a.elevation_m)
    return profile[-1].elevation_m


@dataclass
class SplitConfig:
    window_m: float = 50.0
    spread_pp: float = 4.0
    min_segment_m: float = 50.0
    merge_pp: float = 2.0


def suggest_segments(
    profile: list[ElevationSample],
    cfg: SplitConfig | None = None,
) -> tuple[list[SegmentSuggestion], bool]:
    """Partition a profile when rolling-window spread exceeds the threshold.

    Returns (segments, split_recommended). If no split is needed, a single
    segment covering the whole profile is returned.
    """
    cfg = cfg or SplitConfig()
    if len(profile) < 2:
        return [], False
    stats = gradient_stats(profile, cfg.window_m)
    length = stats.length_m
    if length <= 0:
        return [], False

    regions = _gradient_regions(profile, cfg)
    spread = max(r[3] for r in regions) - min(r[3] for r in regions) if regions else 0.0
    should_split = spread > cfg.spread_pp and len(regions) > 1

    if not should_split:
        start_xy = (profile[0].x, profile[0].y)
        end_xy = (profile[-1].x, profile[-1].y)
        tag_value = stats.max_sustained_pct
        seg = SegmentSuggestion(
            start_m=profile[0].distance_m,
            end_m=profile[-1].distance_m,
            average_pct=stats.average_pct,
            max_sustained_pct=stats.max_sustained_pct,
            incline_tag=format_incline(tag_value),
            start_xy=start_xy,
            end_xy=end_xy,
        )
        return [seg], False

    segments: list[SegmentSuggestion] = []
    for start_m, end_m, _, _avg in regions:
        part = _slice_profile(profile, start_m, end_m)
        st = gradient_stats(part, cfg.window_m)
        segments.append(
            SegmentSuggestion(
                start_m=start_m,
                end_m=end_m,
                average_pct=st.average_pct,
                max_sustained_pct=st.max_sustained_pct,
                incline_tag=format_incline(st.max_sustained_pct),
                start_xy=xy_at_profile_distance(profile, start_m),
                end_xy=xy_at_profile_distance(profile, end_m),
            )
        )
    return segments, True


def _slice_profile(
    profile: list[ElevationSample], start_m: float, end_m: float
) -> list[ElevationSample]:
    pts = [p for p in profile if start_m - 1e-6 <= p.distance_m <= end_m + 1e-6]
    if len(pts) >= 2:
        return pts
    # Synthesize endpoints so stats still work.
    z0 = _elevation_at(profile, start_m)
    z1 = _elevation_at(profile, end_m)
    x0, y0 = xy_at_profile_distance(profile, start_m)
    x1, y1 = xy_at_profile_distance(profile, end_m)
    return [
        ElevationSample(start_m, z0, x0, y0),
        ElevationSample(end_m, z1, x1, y1),
    ]


def _gradient_regions(
    profile: list[ElevationSample], cfg: SplitConfig
) -> list[tuple[float, float, float, float]]:
    """Merge adjacent windows into regions of similar grade.

    Each tuple is (start_m, end_m, representative_pct, average_pct).
    """
    windows = window_gradients_pct(profile, cfg.window_m)
    if not windows:
        avg = average_gradient_pct(profile)
        return [(profile[0].distance_m, profile[-1].distance_m, avg, avg)]

    # Seed regions from window midpoints, then merge similar neighbours.
    raw: list[list[float]] = []  # [start, end, sum_pct, count]
    for start_m, end_m, pct in windows:
        if not raw:
            raw.append([start_m, end_m, pct, 1.0])
            continue
        prev_avg = raw[-1][2] / raw[-1][3]
        if abs(pct - prev_avg) <= cfg.merge_pp:
            raw[-1][1] = end_m
            raw[-1][2] += pct
            raw[-1][3] += 1.0
        else:
            raw.append([start_m, end_m, pct, 1.0])

    # Enforce minimum segment length by merging short regions into neighbours.
    merged = [list(r) for r in raw]
    changed = True
    while changed and len(merged) > 1:
        changed = False
        i = 0
        while i < len(merged):
            length = merged[i][1] - merged[i][0]
            if length < cfg.min_segment_m and len(merged) > 1:
                if i == 0:
                    target = 1
                elif i == len(merged) - 1:
                    target = i - 1
                else:
                    # Merge into the closer-grade neighbour.
                    avg_i = merged[i][2] / merged[i][3]
                    avg_l = merged[i - 1][2] / merged[i - 1][3]
                    avg_r = merged[i + 1][2] / merged[i + 1][3]
                    target = i - 1 if abs(avg_i - avg_l) <= abs(avg_i - avg_r) else i + 1
                lo, hi = (i, target) if i < target else (target, i)
                merged[lo][1] = max(merged[lo][1], merged[hi][1])
                merged[lo][0] = min(merged[lo][0], merged[hi][0])
                merged[lo][2] += merged[hi][2]
                merged[lo][3] += merged[hi][3]
                del merged[hi]
                changed = True
                break
            i += 1

    # Snap first/last to profile ends.
    if merged:
        merged[0][0] = profile[0].distance_m
        merged[-1][1] = profile[-1].distance_m

    out = []
    for start_m, end_m, s, c in merged:
        avg = s / c if c else 0.0
        out.append((start_m, end_m, avg, avg))
    return out
