"""Centroid path tracker with Catmull-Rom smoothing.

The orchestrator pushes a centroid per analysed frame; ``smooth_path``
returns an interpolated polyline that the Kotlin layer renders as the
glowing replay overlay.

Catmull-Rom is hand-implemented (no SciPy on Chaquopy by default) and
configured with the canonical centripetal parameter ``alpha = 0.5`` —
that variant is the one most commonly recommended for path overlays
because it avoids the cusps and self-intersections that uniform
Catmull-Rom can introduce when control points are unevenly spaced.
"""

from __future__ import annotations

from typing import List, Optional, Sequence, Tuple


PathPoint = Tuple[float, float]


class PathTracker:
    """Buffers car centroids per frame and exposes a smoothed path.

    Centroids may be ``None`` when neither colour detection nor optical
    flow could find the car. The tracker stores the missing samples in
    place — that lets the smoother stitch the path across short gaps by
    interpolating between the last and the next known point, but it
    also lets the orchestrator compute "% of frames the car was
    detected" diagnostics later.
    """

    def __init__(self) -> None:
        self._raw: List[Optional[PathPoint]] = []

    @property
    def raw(self) -> List[Optional[PathPoint]]:
        """Per-frame centroid samples, including ``None`` gaps."""
        return self._raw

    def append(self, point: Optional[PathPoint]) -> None:
        """Adds a single frame's centroid sample (``None`` for missed)."""
        if point is None:
            self._raw.append(None)
        else:
            self._raw.append((float(point[0]), float(point[1])))

    def known_points(self) -> List[PathPoint]:
        """Returns the non-``None`` samples in their original order."""
        return [p for p in self._raw if p is not None]

    def reset(self) -> None:
        self._raw.clear()

    def smooth_path(self, samples_per_segment: int = 12) -> List[PathPoint]:
        """Returns a smoothed polyline of the recorded centroid path.

        Args:
            samples_per_segment: Number of interpolated points produced
                per pair of control points. 12 is dense enough for a
                glowing line at 1080p without flooding the renderer.

        Returns:
            ``[(x, y), ...]`` in pixel coordinates of the analysis
            frame. Empty when fewer than two known points are available
            (Catmull-Rom needs at least four; we synthesise virtual
            endpoints to handle the boundary, but at least two real
            samples are required for the spline to mean anything).
        """
        known = self.known_points()
        if len(known) < 2:
            return list(known)
        if len(known) == 2:
            # With only two control points, interpolate linearly. This
            # is the same shape Catmull-Rom would produce in the limit.
            return _linear_interp(known[0], known[1], samples_per_segment)

        # Construct virtual endpoints by reflecting the second point
        # across the first (and similarly at the tail). This is the
        # standard "phantom point" trick to give Catmull-Rom valid
        # neighbours for the very first and last segments.
        p_first = (
            2 * known[0][0] - known[1][0],
            2 * known[0][1] - known[1][1],
        )
        p_last = (
            2 * known[-1][0] - known[-2][0],
            2 * known[-1][1] - known[-2][1],
        )
        controls = [p_first, *known, p_last]

        out: List[PathPoint] = []
        for i in range(len(controls) - 3):
            segment = _centripetal_catmull_rom(
                controls[i],
                controls[i + 1],
                controls[i + 2],
                controls[i + 3],
                samples_per_segment,
            )
            # Drop the last point of every segment (except the final
            # one) so consecutive segments share a single endpoint.
            if i < len(controls) - 4:
                segment = segment[:-1]
            out.extend(segment)
        return out


def _linear_interp(a: PathPoint, b: PathPoint, count: int) -> List[PathPoint]:
    if count <= 1:
        return [a, b]
    return [
        (
            a[0] + (b[0] - a[0]) * (i / (count - 1)),
            a[1] + (b[1] - a[1]) * (i / (count - 1)),
        )
        for i in range(count)
    ]


def _centripetal_catmull_rom(
    p0: PathPoint,
    p1: PathPoint,
    p2: PathPoint,
    p3: PathPoint,
    samples: int,
    alpha: float = 0.5,
) -> List[PathPoint]:
    """Catmull-Rom spline segment between p1 and p2.

    The derivation follows the standard formulation: parameterise each
    pair of control points by their distance raised to ``alpha``, then
    blend the four points with the canonical basis to produce points on
    the curve interior. ``alpha = 0.5`` is the centripetal variant that
    avoids self-intersections.
    """
    if samples < 2:
        return [p1, p2]

    t0 = 0.0
    t1 = t0 + _segment_t(p0, p1, alpha)
    t2 = t1 + _segment_t(p1, p2, alpha)
    t3 = t2 + _segment_t(p2, p3, alpha)

    # Degenerate input: two adjacent points coincide. Fall back to a
    # straight line so we do not divide by zero below.
    if t1 == t0 or t2 == t1 or t3 == t2:
        return _linear_interp(p1, p2, samples)

    points: List[PathPoint] = []
    for i in range(samples):
        t = t1 + (t2 - t1) * (i / (samples - 1))
        a1 = _lerp(p0, p1, (t1 - t) / (t1 - t0), (t - t0) / (t1 - t0))
        a2 = _lerp(p1, p2, (t2 - t) / (t2 - t1), (t - t1) / (t2 - t1))
        a3 = _lerp(p2, p3, (t3 - t) / (t3 - t2), (t - t2) / (t3 - t2))
        b1 = _lerp(a1, a2, (t2 - t) / (t2 - t0), (t - t0) / (t2 - t0))
        b2 = _lerp(a2, a3, (t3 - t) / (t3 - t1), (t - t1) / (t3 - t1))
        c = _lerp(b1, b2, (t2 - t) / (t2 - t1), (t - t1) / (t2 - t1))
        points.append(c)
    return points


def _segment_t(a: PathPoint, b: PathPoint, alpha: float) -> float:
    dx = b[0] - a[0]
    dy = b[1] - a[1]
    distance = (dx * dx + dy * dy) ** 0.5
    return distance ** alpha


def _lerp(a: PathPoint, b: PathPoint, wa: float, wb: float) -> PathPoint:
    return (a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb)


# Re-exported so the orchestrator can call a free function instead of
# constructing a tracker for one-shot smoothing — handy for unit tests.
def smooth_points(
    points: Sequence[PathPoint],
    samples_per_segment: int = 12,
) -> List[PathPoint]:
    """Convenience wrapper that smooths a static point list."""
    tracker = PathTracker()
    for point in points:
        tracker.append(point)
    return tracker.smooth_path(samples_per_segment)
