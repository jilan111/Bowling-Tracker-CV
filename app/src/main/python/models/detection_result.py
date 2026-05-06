"""Per-frame detector outputs.

The detection layer returns these structures regardless of whether the
underlying detector is the classical CV path or the YOLO fallback. This
gives the tracker and orchestrator a single shape to reason about.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from .pin_state import BBox


@dataclass
class Detection:
    """A single detection — either a pin or the car.

    Attributes:
        bbox: Tight bounding box in down-scaled frame coordinates.
        score: Confidence in [0, 1]. Classical CV produces a heuristic
            score from contour quality (area + aspect-ratio match);
            YOLO contributes a real probability.
        label: ``"pin"`` or ``"car"``. The orchestrator uses this to
            split the detection list before handing it to the
            respective tracker.
    """

    bbox: BBox
    score: float
    label: str


@dataclass
class FrameDetections:
    """Detector output for a single frame.

    Attributes:
        frame_index: Position of this frame in the analysis stream
            (post-decimation).
        timestamp_seconds: Source-video presentation time, in seconds.
        pins: Pin detections, ordered most-confident first.
        car_centroid: ``(x, y)`` of the RC car centroid if found, else
            ``None``. We deliberately collapse the car to a point on
            the detector output because every downstream consumer
            (path tracker, overlay renderer) wants a centroid; carrying
            the car's bbox would push that flattening into every
            caller.
    """

    frame_index: int
    timestamp_seconds: float
    pins: List[Detection] = field(default_factory=list)
    car_centroid: Optional[Tuple[int, int]] = None

    @property
    def pin_bboxes(self) -> List[BBox]:
        """Convenience for callers that only care about boxes."""
        return [det.bbox for det in self.pins]
