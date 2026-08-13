"""Unit tests for gradient calculation and incline=* formatting."""

from __future__ import annotations

from nvdb_incline.gradient import (
    SplitConfig,
    average_gradient_pct,
    format_incline,
    gradient_stats,
    max_sustained_gradient_pct,
    round_incline_pct,
    suggest_segments,
)
from tests.helpers import constant_slope, flat_profile, profile_from_dz


def test_flat_profile_zero_incline():
    p = flat_profile(200.0)
    assert abs(average_gradient_pct(p)) < 1e-9
    assert format_incline(average_gradient_pct(p)) == "0%"


def test_constant_positive_slope():
    p = constant_slope(200.0, 10.0)
    assert abs(average_gradient_pct(p) - 10.0) < 0.05
    assert format_incline(average_gradient_pct(p)) == "10%"


def test_constant_negative_slope_respects_way_direction():
    p = constant_slope(200.0, -8.0)
    assert abs(average_gradient_pct(p) + 8.0) < 0.05
    assert format_incline(average_gradient_pct(p)) == "-8%"


def test_single_spike_does_not_dominate_average_but_shows_in_window():
    # Mostly flat with a short 20% pitch over 20m in the middle.
    distances = [0, 40, 60, 80, 200]
    elevations = [100, 100, 104, 104, 104]  # 20% over 20m, then flat
    p = profile_from_dz(distances, elevations)
    avg = average_gradient_pct(p)
    assert abs(avg - 2.0) < 0.2  # 4m / 200m
    max_s = max_sustained_gradient_pct(p, window_m=20.0)
    assert max_s >= 15.0


def test_varying_slope_max_sustained():
    # 5% for 100m then 12% for 100m
    distances = [0, 100, 200]
    elevations = [0, 5, 5 + 12]
    p = profile_from_dz(distances, elevations)
    stats = gradient_stats(p, window_m=50.0)
    assert abs(stats.average_pct - 8.5) < 0.2
    assert stats.max_sustained_pct >= 10.0


def test_rounding_edge_cases():
    assert round_incline_pct(0.0) == 0
    assert round_incline_pct(0.4) == 0
    assert round_incline_pct(0.5) == 1
    assert round_incline_pct(-0.5) == -1
    assert round_incline_pct(20.4) == 20
    assert round_incline_pct(20.6) == 21
    assert format_incline(-3.2) == "-3%"
    assert format_incline(25.0) == "25%"


def test_split_when_spread_exceeds_threshold():
    # Flat then steep: windows differ by >4 pp
    distances = list(range(0, 301, 10))
    elevations = [100.0 + (0.0 if d < 150 else (d - 150) * 0.10) for d in distances]
    p = profile_from_dz(distances, elevations)
    segs, split = suggest_segments(
        p, SplitConfig(window_m=50.0, spread_pp=4.0, min_segment_m=40.0, merge_pp=2.0)
    )
    assert split is True
    assert len(segs) >= 2
    # Later segment should be steeper on average (max-sustained can
    # briefly touch the grade change inside the early windows).
    assert abs(segs[-1].average_pct) > abs(segs[0].average_pct) + 2.0


def test_no_split_for_uniform_slope():
    p = constant_slope(300.0, 7.0)
    segs, split = suggest_segments(p, SplitConfig(spread_pp=4.0))
    assert split is False
    assert len(segs) == 1
    assert segs[0].incline_tag == "7%"
