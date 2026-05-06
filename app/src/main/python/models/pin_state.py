"""Tracker state for a single pin across the lifetime of a video.

The tracker emits one ``PinState`` per stable identity. The fall detector
mutates ``status`` and the timestamp fields when it observes a pin
transitioning from standing to fallen; everything else is filled in at
construction time and never touched.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Tuple


class PinStatus(str, Enum):
    """Whether a tracked pin is currently standing or has fallen.

    String-valued so it round-trips cleanly across the Chaquopy bridge
    (Java has no native sum type for the Kotlin layer to consume).
    """

    STANDING = "STANDING"
    FALLEN = "FALLEN"


# Bounding box convention used throughout the pipeline:
#   (x, y, width, height) in pixel coordinates of the down-scaled
#   analysis frame. The origin is the top-left of the frame.
BBox = Tuple[int, int, int, int]


@dataclass
class PinState:
    """State carried by the tracker for one identified pin.

    Attributes:
        id: Stable integer assigned the first time this pin was seen.
            Ids are unique per video — they are never recycled, even if a
            pin disappears and a similar contour shows up later.
        current_bbox: Bounding box from the most recent matched frame.
        initial_bbox: Bounding box from the frame this id was assigned.
            Used by the fall detector to reason about *change* rather
            than absolute geometry, which sidesteps lighting variance.
        status: Standing or fallen. Mutated only by the fall detector.
        last_seen_frame: Index of the most recent frame in which the
            tracker matched this id. The orchestrator can use this to
            prune stale identities.
        fall_timestamp: Wall-clock time (in seconds since the video
            start) when the pin fell, or ``None`` if it has not fallen.
        fall_frame_index: Frame index of the fall transition, or
            ``None``. Carried alongside the timestamp so the UI can
            jump to the exact frame in the replay player.
        consecutive_fallen_frames: Internal counter used by the fall
            detector to require N consecutive frames of "looks fallen"
            before transitioning. Exposed on the dataclass rather than
            kept in a side dict so the tracker remains the single
            source of truth for a pin.
    """

    id: int
    current_bbox: BBox
    initial_bbox: BBox = field(default_factory=lambda: (0, 0, 0, 0))
    status: PinStatus = PinStatus.STANDING
    last_seen_frame: int = 0
    fall_timestamp: Optional[float] = None
    fall_frame_index: Optional[int] = None
    consecutive_fallen_frames: int = 0

    def __post_init__(self) -> None:
        # When a pin is first constructed it has no recorded "initial"
        # bbox; mirror the current bbox so the fall detector has a
        # baseline to compare against from frame zero.
        if self.initial_bbox == (0, 0, 0, 0):
            self.initial_bbox = self.current_bbox

    @property
    def is_standing(self) -> bool:
        return self.status == PinStatus.STANDING

    @property
    def is_fallen(self) -> bool:
        return self.status == PinStatus.FALLEN
