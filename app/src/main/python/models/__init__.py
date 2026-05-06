"""Plain dataclasses shared across the BowlTrack Python pipeline."""

from .pin_state import PinState, PinStatus
from .detection_result import Detection, FrameDetections

__all__ = ["PinState", "PinStatus", "Detection", "FrameDetections"]
