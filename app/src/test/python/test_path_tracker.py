"""Unit tests for the Catmull-Rom path tracker."""

from tracking.path_tracker import PathTracker, smooth_points


def test_smooth_path_passes_through_known_points():
    samples = [(0.0, 0.0), (10.0, 0.0), (20.0, 5.0), (30.0, 5.0)]
    smoothed = smooth_points(samples, samples_per_segment=8)

    # First and last points must coincide with the input control
    # points; that is what the centripetal formulation guarantees.
    # Compare with a small tolerance because the spline math
    # accumulates the usual IEEE-754 round-off (~1e-14) at the
    # endpoints.
    eps = 1e-6
    assert abs(smoothed[0][0] - samples[0][0]) < eps
    assert abs(smoothed[0][1] - samples[0][1]) < eps
    assert abs(smoothed[-1][0] - samples[-1][0]) < eps
    assert abs(smoothed[-1][1] - samples[-1][1]) < eps


def test_smooth_path_handles_two_known_samples_via_linear_interpolation():
    smoothed = smooth_points([(0.0, 0.0), (10.0, 0.0)], samples_per_segment=4)
    assert len(smoothed) == 4
    # Midpoint should land on the straight line.
    midpoint = smoothed[len(smoothed) // 2]
    assert 0.0 < midpoint[0] < 10.0
    assert midpoint[1] == 0.0


def test_smooth_path_returns_known_points_when_under_two_samples():
    assert smooth_points([], samples_per_segment=8) == []
    assert smooth_points([(1.0, 2.0)], samples_per_segment=8) == [(1.0, 2.0)]


def test_path_tracker_skips_none_samples_when_smoothing():
    tracker = PathTracker()
    tracker.append((0.0, 0.0))
    tracker.append(None)
    tracker.append((10.0, 0.0))
    tracker.append((20.0, 0.0))

    smoothed = tracker.smooth_path(samples_per_segment=4)
    assert smoothed[0] == (0.0, 0.0)
    assert smoothed[-1] == (20.0, 0.0)
    # raw still records the gap so callers can compute coverage stats.
    assert tracker.raw[1] is None
