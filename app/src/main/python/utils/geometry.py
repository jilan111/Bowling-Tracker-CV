"""Tiny geometry helpers used by the tracker and the fall detector.

Kept as plain functions so they are trivial to unit test from pytest
without dragging the rest of the pipeline along.
"""

from __future__ import annotations

from typing import Tuple

BBox = Tuple[int, int, int, int]


def bbox_area(bbox: BBox) -> int:
    """Returns the pixel area of a (x, y, w, h) bounding box."""
    _, _, w, h = bbox
    return max(0, w) * max(0, h)


def bbox_center(bbox: BBox) -> tuple[float, float]:
    """Returns the centroid of a bounding box.

    Floats are returned because the centroid is genuinely sub-pixel for
    odd width or height; rounding it to int would bias every centroid
    half a pixel toward the origin.
    """
    x, y, w, h = bbox
    return (x + w / 2.0, y + h / 2.0)


def aspect_ratio(bbox: BBox) -> float:
    """Returns ``height / width`` for a bounding box.

    Choosing height/width (rather than width/height) means standing pins
    yield values > 1 and fallen pins yield values < 1, which matches the
    intuition encoded in the fall detector's threshold.

    Defensive: if the bbox has zero width we return ``+inf`` rather than
    raising; that way the caller can compare against a finite threshold
    without an explicit None-check.
    """
    _, _, w, h = bbox
    if w <= 0:
        return float("inf")
    return float(h) / float(w)


def iou(a: BBox, b: BBox) -> float:
    """Intersection-over-union for two ``(x, y, w, h)`` boxes.

    Returns 0.0 if the boxes do not overlap or if either has zero area.
    """
    ax, ay, aw, ah = a
    bx, by, bw, bh = b

    # Convert to (left, top, right, bottom).
    a_left, a_top, a_right, a_bottom = ax, ay, ax + aw, ay + ah
    b_left, b_top, b_right, b_bottom = bx, by, bx + bw, by + bh

    inter_left = max(a_left, b_left)
    inter_top = max(a_top, b_top)
    inter_right = min(a_right, b_right)
    inter_bottom = min(a_bottom, b_bottom)

    inter_w = max(0, inter_right - inter_left)
    inter_h = max(0, inter_bottom - inter_top)
    inter_area = inter_w * inter_h
    if inter_area == 0:
        return 0.0

    union_area = bbox_area(a) + bbox_area(b) - inter_area
    if union_area <= 0:
        return 0.0
    return inter_area / float(union_area)
