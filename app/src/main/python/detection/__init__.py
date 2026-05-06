"""Frame-level detection.

Two tiers will live here:

* :mod:`classical_detector` — pure OpenCV: HSV threshold + morphology +
  contour filter for pins, HSV threshold + largest contour for the car.

* :mod:`yolo_detector` (Milestone 7) — YOLOv8n TFLite as a verification
  pass and as a fallback when the user has not configured a colour for
  the car.
"""

from .classical_detector import ClassicalDetector
from .yolo_detector import (
    CAR_PROXY_CLASSES,
    PIN_PROXY_CLASSES,
    YoloDetection,
    YoloDetector,
    YoloInferenceStats,
)
from .nms import non_max_suppression

__all__ = [
    "ClassicalDetector",
    "YoloDetector",
    "YoloDetection",
    "YoloInferenceStats",
    "PIN_PROXY_CLASSES",
    "CAR_PROXY_CLASSES",
    "non_max_suppression",
]
