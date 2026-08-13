"""Shared synthetic profile helpers."""

from __future__ import annotations

from nvdb_incline.models import ElevationSample


def profile_from_dz(
    distances: list[float], elevations: list[float], x0: float = 0.0, y0: float = 0.0
) -> list[ElevationSample]:
    out = []
    for d, z in zip(distances, elevations):
        out.append(ElevationSample(distance_m=d, elevation_m=z, x=x0 + d, y=y0))
    return out


def constant_slope(length_m: float, grade_pct: float, step_m: float = 10.0) -> list[ElevationSample]:
    n = max(2, int(length_m / step_m) + 1)
    distances = [i * (length_m / (n - 1)) for i in range(n)]
    elevations = [0.0 + d * grade_pct / 100.0 for d in distances]
    return profile_from_dz(distances, elevations)


def flat_profile(length_m: float = 200.0, z: float = 100.0) -> list[ElevationSample]:
    return profile_from_dz([0.0, length_m / 2, length_m], [z, z, z])
