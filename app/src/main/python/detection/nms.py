"""Non-Maximum Suppression in pure NumPy.

YOLOv8 emits hundreds of overlapping anchor predictions for each real
object; NMS is what collapses them into one box per object. Because we
target mobile CPUs, we keep the implementation tight and dependency-
free — only NumPy. The brief allows O(n^2) for YOLOv8n scale (typically
< 200 candidates after the confidence cut), and that is what we use.

Box convention throughout this module:
    [x1, y1, x2, y2]  with x2 > x1 and y2 > y1, in pixels.
"""

from __future__ import annotations

from typing import List

import numpy as np


def non_max_suppression(
    boxes: np.ndarray,
    scores: np.ndarray,
    iou_threshold: float = 0.45,
    score_threshold: float = 0.25,
    top_k: int = 300,
) -> List[int]:
    """Returns indices of boxes that survive NMS.

    Args:
        boxes: ``(N, 4)`` array of ``[x1, y1, x2, y2]`` corners.
        scores: ``(N,)`` array of confidences in ``[0, 1]``.
        iou_threshold: Boxes whose IoU with a kept box exceeds this
            value are suppressed.
        score_threshold: Boxes with confidence below this value are
            dropped before any IoU comparison runs.
        top_k: Hard cap on how many candidates we are willing to inspect
            after the confidence cut. Saves time on busy frames.

    Returns:
        List of indices into the *original* ``boxes`` / ``scores``
        arrays, ordered from highest confidence to lowest. Returning
        indices (rather than rows) lets the caller carry along extra
        per-box metadata (e.g. class ids) without us having to touch
        it here.
    """
    if boxes.ndim != 2 or boxes.shape[1] != 4:
        raise ValueError(f"boxes must be (N,4); got shape {boxes.shape}")
    if scores.ndim != 1 or scores.shape[0] != boxes.shape[0]:
        raise ValueError("scores must be a 1-D array matching boxes length")

    # 1. Score filter.
    keep_mask = scores >= score_threshold
    if not np.any(keep_mask):
        return []
    candidate_indices = np.where(keep_mask)[0]

    # 2. Sort candidates by score, descending. Cap to top_k to bound
    #    the inner loop on pathological frames.
    candidate_indices = candidate_indices[np.argsort(-scores[candidate_indices])][:top_k]

    # 3. Cache geometry once. Computing areas and corners on every
    #    iteration would dominate runtime at our scale.
    x1 = boxes[:, 0]
    y1 = boxes[:, 1]
    x2 = boxes[:, 2]
    y2 = boxes[:, 3]
    areas = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)

    survivors: List[int] = []
    while candidate_indices.size > 0:
        head = int(candidate_indices[0])
        survivors.append(head)
        if candidate_indices.size == 1:
            break
        rest = candidate_indices[1:]

        ious = _iou_one_to_many(
            head=head,
            rest=rest,
            x1=x1, y1=y1, x2=x2, y2=y2,
            areas=areas,
        )
        # Suppress everyone whose IoU with the head exceeds the
        # threshold; the survivors of this step proceed to the next
        # iteration. NumPy boolean indexing keeps this branch-free.
        keep = ious < iou_threshold
        candidate_indices = rest[keep]

    return survivors


def _iou_one_to_many(
    head: int,
    rest: np.ndarray,
    x1: np.ndarray, y1: np.ndarray, x2: np.ndarray, y2: np.ndarray,
    areas: np.ndarray,
) -> np.ndarray:
    """Vectorised IoU between one box (``head``) and a batch (``rest``)."""
    inter_x1 = np.maximum(x1[head], x1[rest])
    inter_y1 = np.maximum(y1[head], y1[rest])
    inter_x2 = np.minimum(x2[head], x2[rest])
    inter_y2 = np.minimum(y2[head], y2[rest])

    inter_w = np.clip(inter_x2 - inter_x1, a_min=0.0, a_max=None)
    inter_h = np.clip(inter_y2 - inter_y1, a_min=0.0, a_max=None)
    inter = inter_w * inter_h

    union = areas[head] + areas[rest] - inter
    # Where union is zero, IoU is undefined; force to 0 so the box is
    # never spuriously suppressed by a degenerate detection.
    return np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
