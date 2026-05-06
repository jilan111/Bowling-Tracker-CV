"""Unit tests for the color-agnostic ClassicalDetector.

These tests build tiny synthetic frames so the detector's two paths
(color-agnostic + HSV-calibrated) can be exercised without an actual
bowling clip. The frames are deliberately minimal: a uniform floor with
a pin-shaped rectangle for pin tests, and a small moving square for car
tests.
"""

from __future__ import annotations

import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from detection.classical_detector import ClassicalDetector, DetectorConfig
from utils import HsvRange


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
def _floor(width: int, height: int, color_bgr: tuple = (200, 200, 200)) -> np.ndarray:
    """Returns a uniform BGR background frame."""
    frame = np.zeros((height, width, 3), dtype=np.uint8)
    frame[:] = color_bgr
    return frame


def _draw_pin(
    frame: np.ndarray,
    x: int,
    y: int,
    w: int = 20,
    h: int = 60,
    color_bgr: tuple = (40, 30, 220),
) -> None:
    """Draws a tall, narrow rectangle that should pass the pin filter."""
    frame[y : y + h, x : x + w] = color_bgr


# ----------------------------------------------------------------------
# Pin detection — color-agnostic path
# ----------------------------------------------------------------------
def test_color_agnostic_pin_finds_red_pin_on_grey_floor():
    frame = _floor(200, 200, color_bgr=(180, 180, 180))
    _draw_pin(frame, x=80, y=60, color_bgr=(40, 30, 220))  # red pin

    detector = ClassicalDetector(DetectorConfig(min_pin_area=200))
    pins = detector.detect_pins(frame)

    assert pins, "expected at least one pin on a colour-distinct rectangle"
    x, y, w, h = pins[0].bbox
    # Detected box must roughly enclose the painted region.
    assert 60 <= x <= 90
    assert 40 <= y <= 70
    assert h > w  # taller than wide → standing pin


def test_color_agnostic_pin_finds_white_pin_via_edges():
    """Low-saturation pin: saturation Otsu won't help, edges must."""
    frame = _floor(200, 200, color_bgr=(80, 80, 80))  # dark grey floor
    _draw_pin(frame, x=80, y=60, color_bgr=(245, 245, 245))  # white pin

    detector = ClassicalDetector(DetectorConfig(min_pin_area=200))
    pins = detector.detect_pins(frame)

    assert pins, "edge-based pass should fire on a high-contrast white pin"


def test_color_agnostic_pin_returns_empty_on_uniform_frame():
    frame = _floor(200, 200, color_bgr=(200, 200, 200))
    detector = ClassicalDetector(DetectorConfig())
    assert detector.detect_pins(frame) == []


def test_color_agnostic_pin_accepts_horizontal_fallen_pin():
    """A wide, short rectangle = a fallen pin viewed top-down. The new
    orientation-agnostic elongation filter keeps it; the old h/w
    filter would have rejected it."""
    frame = _floor(200, 200)
    frame[100:115, 50:150] = (40, 200, 40)  # 100x15 wide blob

    detector = ClassicalDetector(DetectorConfig(min_pin_area=200))
    pins = detector.detect_pins(frame)
    assert pins, "fallen pin (lying horizontally) must still be detected"
    x, y, w, h = pins[0].bbox
    assert w > h  # detected box is wider than tall, as expected


def test_color_agnostic_pin_rejects_blob_larger_than_max_area():
    """A blob bigger than ``max_pin_area`` is the car, not a pin."""
    frame = _floor(400, 400, color_bgr=(180, 180, 180))
    frame[50:300, 50:300] = (40, 200, 40)  # 250x250 = 62500 px

    detector = ClassicalDetector(DetectorConfig(min_pin_area=200, max_pin_area=4000))
    pins = detector.detect_pins(frame)
    assert pins == []


def test_color_agnostic_pin_rejects_near_circular_blob():
    """A roughly square / circular small blob has elongation ≈ 1.0,
    below the threshold — pure noise / dust dot, not a pin."""
    frame = _floor(200, 200)
    # 22x22 nearly-square blob → elongation 1.0
    frame[80:102, 80:102] = (40, 200, 40)

    detector = ClassicalDetector(
        DetectorConfig(min_pin_area=200, min_pin_aspect=1.3),
    )
    pins = detector.detect_pins(frame)
    assert pins == []


# ----------------------------------------------------------------------
# Pin detection — HSV-calibrated fallback (preserves backward compat)
# ----------------------------------------------------------------------
def test_pin_hsv_path_still_works_when_configured():
    frame = _floor(200, 200, color_bgr=(200, 200, 200))
    # BGR (30, 40, 220): R-dominant with G slightly > B so OpenCV maps
    # the hue to ~2 (low end of the red ring), inside the HSV window
    # below. (40, 30, 220) would wrap to ~178 instead.
    _draw_pin(frame, x=80, y=60, color_bgr=(30, 40, 220))

    # HSV range covering red.
    cfg = DetectorConfig(
        pin_hsv=(HsvRange(lower=(0, 100, 100), upper=(10, 255, 255)),),
        min_pin_area=200,
    )
    detector = ClassicalDetector(cfg)
    pins = detector.detect_pins(frame)
    assert pins, "HSV-calibrated path should still match a colour the user picked"


def test_pin_detection_returns_list_never_none():
    """Contract: detect_pins always returns a list, never None."""
    detector = ClassicalDetector(DetectorConfig())
    blank = _floor(50, 50)
    result = detector.detect_pins(blank)
    assert isinstance(result, list)


# ----------------------------------------------------------------------
# Car detection — motion path
# ----------------------------------------------------------------------
def test_motion_path_returns_none_on_first_frame():
    detector = ClassicalDetector(DetectorConfig())
    f0 = _floor(200, 200)
    # No prior frame → motion diff is undefined → None.
    assert detector.detect_car(f0) is None


def test_motion_path_detects_moving_blob():
    detector = ClassicalDetector(DetectorConfig(min_car_area=50))

    # Frame 0: blank floor.
    f0 = _floor(200, 200, color_bgr=(180, 180, 180))
    detector.detect_car(f0)  # primes _prev_gray

    # Frame 1: a small green square at (100, 100).
    f1 = _floor(200, 200, color_bgr=(180, 180, 180))
    f1[95:115, 95:115] = (40, 220, 40)

    centroid = detector.detect_car(f1)
    assert centroid is not None
    cx, cy = centroid
    # Centroid should be near the painted square's centre (105, 105).
    assert 95 <= cx <= 115
    assert 95 <= cy <= 115


def test_motion_path_returns_none_on_static_scene():
    detector = ClassicalDetector(DetectorConfig())
    f = _floor(200, 200, color_bgr=(120, 120, 120))
    detector.detect_car(f)
    # No change between two identical frames → no motion blob.
    assert detector.detect_car(f) is None


def test_motion_path_skips_pin_shaped_blob_when_other_blob_present():
    """A wide moving blob (the car) must beat a tall narrow moving blob (a tipping pin)."""
    detector = ClassicalDetector(DetectorConfig(min_car_area=50))
    f0 = _floor(300, 200, color_bgr=(160, 160, 160))
    detector.detect_car(f0)

    f1 = _floor(300, 200, color_bgr=(160, 160, 160))
    # Tall narrow "pin" on the left — should be rejected as pin-like.
    # Black so the grayscale diff is unambiguously above threshold.
    f1[40:120, 40:55] = (0, 0, 0)
    # Wide square "car" on the right — preferred.
    f1[60:100, 200:240] = (0, 0, 0)

    centroid = detector.detect_car(f1)
    assert centroid is not None
    cx, _ = centroid
    # The car blob's centre is around x=220; the pin's around x=47.
    assert cx > 150


# ----------------------------------------------------------------------
# Car detection — HSV path (backward compat)
# ----------------------------------------------------------------------
def test_car_hsv_path_returns_centroid_when_configured():
    cfg = DetectorConfig(
        car_hsv=(
            HsvRange(lower=(0, 100, 100), upper=(10, 255, 255)),
            HsvRange(lower=(170, 100, 100), upper=(179, 255, 255)),
        ),
    )
    detector = ClassicalDetector(cfg)

    frame = _floor(200, 200, color_bgr=(160, 160, 160))
    frame[80:120, 80:120] = (40, 30, 220)  # red square

    centroid = detector.detect_car(frame)
    assert centroid is not None
    cx, cy = centroid
    assert 90 <= cx <= 110
    assert 90 <= cy <= 110


def test_car_hsv_path_falls_back_to_motion_when_color_misses():
    """If the user's calibration excludes the actual car colour, motion still wins."""
    cfg = DetectorConfig(
        # Calibrated for red — but the moving blob will be green.
        car_hsv=(HsvRange(lower=(0, 100, 100), upper=(10, 255, 255)),),
        min_car_area=50,
    )
    detector = ClassicalDetector(cfg)

    f0 = _floor(200, 200, color_bgr=(170, 170, 170))
    detector.detect_car(f0)

    f1 = _floor(200, 200, color_bgr=(170, 170, 170))
    # Black square — high contrast against grey so motion threshold
    # fires regardless of colour calibration. (40,220,40) has too
    # similar a luminance to the floor.
    f1[80:120, 80:120] = (0, 0, 0)

    centroid = detector.detect_car(f1)
    assert centroid is not None  # motion path saved the day


# ----------------------------------------------------------------------
# Backward compatibility / contract checks
# ----------------------------------------------------------------------
def test_default_config_has_no_hsv_ranges():
    """New default = colour-agnostic. Both HSV slots are None."""
    cfg = DetectorConfig()
    assert cfg.pin_hsv is None
    assert cfg.car_hsv is None


def test_detect_car_returns_int_centroid():
    """Whatever path was taken, the centroid must be a plain (int, int)."""
    detector = ClassicalDetector(DetectorConfig(min_car_area=50))
    f0 = _floor(200, 200)
    detector.detect_car(f0)

    f1 = _floor(200, 200)
    f1[90:120, 90:120] = (50, 200, 50)
    centroid = detector.detect_car(f1)
    assert centroid is not None
    cx, cy = centroid
    assert isinstance(cx, int)
    assert isinstance(cy, int)
