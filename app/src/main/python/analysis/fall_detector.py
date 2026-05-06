"""Pin-fall detector.

A pin transitions from STANDING to FALLEN when *either* of the
following holds for at least ``required_consecutive`` frames:

1. **Aspect ratio inverts.** A standing pin has height/width > 1; a
   pin lying on its side has height/width < 1. We require the current
   aspect ratio to drop below ``aspect_invert_threshold`` (default 1.0)
   to count as inverted, and we require the persistence so a single
   noisy frame does not flip the state.

2. **Top edge drops.** The top of the pin's bounding box descends by
   more than ``top_drop_fraction`` of the *initial* pin height. This
   catches pins that are knocked over but happen to land in a way that
   keeps the bbox vaguely tall (e.g. propped against a neighbour).

The fall order is recorded the first time a pin transitions, and every
event is tagged with both the frame index and the wall-clock timestamp
so the UI can jump to the exact moment.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List

from models import PinState, PinStatus
from utils import aspect_ratio


@dataclass(frozen=True)
class FallEvent:
    """One pin transitioning to FALLEN.

    Attributes:
        order: 1-based fall order across the analysis. The first pin to
            fall has order 1, the second 2, and so on.
        pin_id: Stable identifier from :class:`PinTracker`.
        frame_index: Index of the frame on which the transition was
            confirmed (i.e. the *last* of the consecutive frames that
            met the criterion, not the first).
        timestamp_seconds: Source-video timestamp of ``frame_index``.
    """

    order: int
    pin_id: int
    frame_index: int
    timestamp_seconds: float


class FallDetector:
    """Stateful detector that watches a :class:`PinTracker` and emits
    :class:`FallEvent` objects in fall order.

    Args:
        required_consecutive: Number of consecutive frames a pin must
            satisfy a fall criterion before the transition is
            confirmed. Acts as a debounce against detection jitter.
        aspect_invert_threshold: Aspect ratios *strictly below* this
            value count toward the consecutive-frame counter. Default
            of 1.0 corresponds to "wider than tall".
        top_drop_fraction: Fraction of the initial pin height by which
            the top edge must descend to count toward the consecutive-
            frame counter.
    """

    def __init__(
        self,
        required_consecutive: int = 5,
        aspect_invert_threshold: float = 1.0,
        top_drop_fraction: float = 0.40,
    ) -> None:
        if required_consecutive < 1:
            raise ValueError("required_consecutive must be >= 1")
        if not 0.0 < top_drop_fraction <= 1.0:
            raise ValueError("top_drop_fraction must be in (0, 1]")
        self.required_consecutive = required_consecutive
        self.aspect_invert_threshold = aspect_invert_threshold
        self.top_drop_fraction = top_drop_fraction
        self._fall_count = 0
        self._events: List[FallEvent] = []

    @property
    def events(self) -> List[FallEvent]:
        """Fall events in the order they were confirmed."""
        return list(self._events)

    @property
    def fallen_count(self) -> int:
        """Number of distinct pins confirmed fallen so far."""
        return self._fall_count

    def update(
        self,
        frame_index: int,
        timestamp_seconds: float,
        pins: dict[int, PinState],
    ) -> List[FallEvent]:
        """Folds the tracker's current state into the fall log.

        Returns the events newly emitted on *this* call so the
        orchestrator can log them or surface them to the UI without
        diffing the cumulative list.
        """
        new_events: List[FallEvent] = []
        for pin in pins.values():
            if pin.status == PinStatus.FALLEN:
                continue
            if self._looks_fallen(pin):
                pin.consecutive_fallen_frames += 1
            else:
                pin.consecutive_fallen_frames = 0
                continue

            if pin.consecutive_fallen_frames >= self.required_consecutive:
                pin.status = PinStatus.FALLEN
                pin.fall_frame_index = frame_index
                pin.fall_timestamp = timestamp_seconds
                self._fall_count += 1
                event = FallEvent(
                    order=self._fall_count,
                    pin_id=pin.id,
                    frame_index=frame_index,
                    timestamp_seconds=timestamp_seconds,
                )
                self._events.append(event)
                new_events.append(event)
        return new_events

    def reset(self) -> None:
        """Forgets all confirmed events. Used between analyses."""
        self._fall_count = 0
        self._events.clear()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _looks_fallen(self, pin: PinState) -> bool:
        if self._aspect_inverted(pin):
            return True
        if self._top_edge_dropped(pin):
            return True
        return False

    def _aspect_inverted(self, pin: PinState) -> bool:
        return aspect_ratio(pin.current_bbox) < self.aspect_invert_threshold

    def _top_edge_dropped(self, pin: PinState) -> bool:
        _, init_y, _, init_h = pin.initial_bbox
        if init_h <= 0:
            return False
        _, current_y, _, _ = pin.current_bbox
        delta = current_y - init_y
        return delta >= init_h * self.top_drop_fraction
