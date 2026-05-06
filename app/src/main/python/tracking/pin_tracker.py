"""Lightweight IoU-based pin tracker.

Each frame, the tracker greedy-matches the current detector outputs
against last-frame detections by Intersection-over-Union. Anything that
matches above a threshold inherits the existing pin's identity;
unmatched detections become new identities. Existing pins that go
unmatched keep their last bounding box and bump a "last seen" counter.

This is a deliberately small algorithm. Hungarian assignment would give
slightly better behaviour when many pins overlap, but greedy IoU is
easy to reason about, fast, and matches the depth this project is
aiming for as a senior capstone.
"""

from __future__ import annotations

from typing import Dict, List, Sequence

from models import PinState
from models.pin_state import BBox
from utils import iou


class PinTracker:
    """Stateful tracker that maintains pin identities across frames.

    The tracker owns a dict keyed by pin id; the orchestrator queries
    the same instance frame after frame. The fall detector mutates
    ``status`` directly on the dict's values, which is fine because
    Python's GIL keeps the read/modify/write coherent for our
    single-threaded use.

    Attributes:
        iou_threshold: Minimum IoU required to inherit an identity.
            0.5 is the value the brief specifies; we expose it on the
            constructor anyway because the unit tests explore the
            tracker's behaviour at lower thresholds too.
    """

    def __init__(self, iou_threshold: float = 0.5) -> None:
        if not 0.0 < iou_threshold <= 1.0:
            raise ValueError("iou_threshold must be in (0, 1]")
        self.iou_threshold = iou_threshold
        self._pins: Dict[int, PinState] = {}
        self._next_id: int = 1

    @property
    def pins(self) -> Dict[int, PinState]:
        """Live mapping of pin id -> ``PinState``. Mutating the
        returned dict mutates tracker state — callers should treat it
        as read-only outside of the fall detector that owns the
        ``status`` field by contract.
        """
        return self._pins

    def update(
        self,
        frame_index: int,
        detections: Sequence[BBox],
    ) -> List[PinState]:
        """Folds a single frame's pin detections into the tracker.

        Args:
            frame_index: Index of the current frame in the analysis
                stream.
            detections: Bounding boxes of pins detected this frame.

        Returns:
            List of ``PinState`` objects whose ``current_bbox`` was
            updated by this call. Useful for downstream consumers that
            want to react only to "actually moved" pins; everything
            else stays in the tracker's dict but is not re-emitted.
        """
        updated: List[PinState] = []

        # 1. Score each (existing-pin, detection) pairing by IoU.
        #    Sort descending so we match the strongest pairs first; that
        #    is what makes greedy assignment tolerable. We still bail
        #    out of a pairing if the existing pin or the detection has
        #    already been claimed in this frame.
        pairings: List[tuple[float, int, int]] = []  # (iou, pin_id, det_idx)
        existing_ids = list(self._pins.keys())
        for pin_id in existing_ids:
            existing_bbox = self._pins[pin_id].current_bbox
            for det_idx, det_bbox in enumerate(detections):
                score = iou(existing_bbox, det_bbox)
                if score >= self.iou_threshold:
                    pairings.append((score, pin_id, det_idx))
        pairings.sort(reverse=True)

        claimed_pin_ids: set[int] = set()
        claimed_det_indices: set[int] = set()
        for score, pin_id, det_idx in pairings:
            if pin_id in claimed_pin_ids or det_idx in claimed_det_indices:
                continue
            pin = self._pins[pin_id]
            pin.current_bbox = detections[det_idx]
            pin.last_seen_frame = frame_index
            claimed_pin_ids.add(pin_id)
            claimed_det_indices.add(det_idx)
            updated.append(pin)

        # 2. Detections that did not match any existing pin become new
        #    identities. The tracker never recycles ids; that property
        #    keeps the fall-order log meaningful even if a pin briefly
        #    disappears mid-tumble.
        for det_idx, det_bbox in enumerate(detections):
            if det_idx in claimed_det_indices:
                continue
            new_pin = PinState(
                id=self._next_id,
                current_bbox=det_bbox,
                last_seen_frame=frame_index,
            )
            self._next_id += 1
            self._pins[new_pin.id] = new_pin
            updated.append(new_pin)

        return updated

    def reset(self) -> None:
        """Forgets every tracked pin. Used between videos when the
        same orchestrator instance is reused for a new analysis."""
        self._pins.clear()
        self._next_id = 1
