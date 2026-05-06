"""Unit tests for the pure-NumPy non-maximum suppression."""

import numpy as np

from detection.nms import non_max_suppression


def test_disjoint_boxes_all_survive():
    boxes = np.array([
        [0, 0, 10, 10],
        [100, 100, 110, 110],
    ], dtype=np.float32)
    scores = np.array([0.9, 0.8], dtype=np.float32)
    survivors = non_max_suppression(boxes, scores)
    assert sorted(survivors) == [0, 1]


def test_overlapping_boxes_collapse_to_highest_score():
    boxes = np.array([
        [0, 0, 100, 100],
        [10, 10, 110, 110],   # heavy overlap with the first
        [200, 200, 220, 220], # disjoint
    ], dtype=np.float32)
    scores = np.array([0.4, 0.95, 0.6], dtype=np.float32)
    survivors = non_max_suppression(boxes, scores, iou_threshold=0.5)
    # The high-score 1, then the disjoint 2; box 0 is suppressed by 1.
    assert survivors[0] == 1
    assert 2 in survivors
    assert 0 not in survivors


def test_score_threshold_drops_low_confidence_predictions():
    boxes = np.array([[0, 0, 10, 10], [20, 20, 30, 30]], dtype=np.float32)
    scores = np.array([0.10, 0.40], dtype=np.float32)
    survivors = non_max_suppression(boxes, scores, score_threshold=0.25)
    assert survivors == [1]


def test_top_k_caps_processed_candidates():
    boxes = np.tile(np.array([[0, 0, 10, 10]], dtype=np.float32), (10, 1))
    scores = np.linspace(0.99, 0.50, num=10, dtype=np.float32)
    survivors = non_max_suppression(boxes, scores, iou_threshold=0.5, top_k=3)
    # All boxes overlap perfectly; only the highest-score box survives.
    assert survivors == [0]


def test_invalid_shapes_raise():
    try:
        non_max_suppression(np.zeros((4,), dtype=np.float32), np.zeros((4,), dtype=np.float32))
    except ValueError:
        pass
    else:
        raise AssertionError("non_max_suppression should reject non-2D box arrays")
