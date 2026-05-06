"""Single Kotlin entry point for the BowlTrack analysis pipeline.

Milestone 6 ships only the *single-frame* entry point. The full video
orchestrator (``analyze_video``) is added in Milestone 8 once the YOLO
verification tier is in place. The single-frame variant is sufficient
to prove the end-to-end Kotlin → Python → OpenCV path on a real device,
which is what the Milestone 6 acceptance criteria call for.

The function signature is deliberately friendly to Chaquopy:
* RGBA bytes are accepted as a Python ``bytes`` object,
* width and height are plain Python ints,
* the return value is a ``dict`` of plain types, ready for unwrapping
  on the Kotlin side without any custom converters.
"""

from __future__ import annotations

import os
from time import perf_counter
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np

from analysis import FallDetector
from detection import ClassicalDetector
from detection.classical_detector import DetectorConfig
from detection.yolo_detector import (
    CAR_PROXY_CLASSES,
    FALLEN_PIN_CLASS_ID,
    PIN_PROXY_CLASSES,
    STANDING_PIN_CLASS_ID,
    YoloDetector,
    detections_to_dicts,
)
from models.pin_state import PinStatus
from utils.geometry import iou as _bbox_iou
from tracking import PathTracker, PinTracker
from utils import HsvRange


# Module-level singleton so the YOLO interpreter is built once per
# process, not once per frame. The first call to
# :func:`_get_yolo_detector` lazily constructs it; subsequent calls
# reuse the same instance. The orchestrator can call
# :func:`reset_yolo_detector` if the model file changes (e.g. a new
# checkpoint shipped via OTA, hypothetical).
_YOLO_INSTANCE: Optional[YoloDetector] = None
_YOLO_INSTANCE_PATH: Optional[str] = None


def _get_yolo_detector(model_path: str) -> YoloDetector:
    """Returns a cached :class:`YoloDetector` for ``model_path``."""
    global _YOLO_INSTANCE, _YOLO_INSTANCE_PATH
    if _YOLO_INSTANCE is None or _YOLO_INSTANCE_PATH != model_path:
        # Restrict raw outputs to the COCO classes we actually map to
        # pins or cars; everything else is noise for our purposes and
        # would just inflate the post-NMS list.
        _YOLO_INSTANCE = YoloDetector(
            model_path=model_path,
            class_filter=PIN_PROXY_CLASSES + CAR_PROXY_CLASSES,
        )
        _YOLO_INSTANCE_PATH = model_path
    return _YOLO_INSTANCE


def reset_yolo_detector() -> None:
    """Drops the cached YOLO interpreter. Used by tests and by the
    Kotlin layer when the user changes detection settings."""
    global _YOLO_INSTANCE, _YOLO_INSTANCE_PATH
    _YOLO_INSTANCE = None
    _YOLO_INSTANCE_PATH = None


def hello() -> str:
    """Diagnostic ping used by Kotlin to verify the runtime is up.

    Returns the OpenCV and NumPy versions so the device-side smoke
    test stays self-describing.
    """
    return f"BowlTrack pipeline | NumPy {np.__version__} | OpenCV {cv2.__version__}"


# ----------------------------------------------------------------------
# HSV mask preview — Milestone 12.
#
# The calibration screen passes a bitmap and a candidate HSV range; we
# return the binary mask as PNG bytes so Compose can decode it with
# BitmapFactory and overlay it as a live "this is what your colour
# range matches" preview. PNG keeps the wire format dependency-free
# on the Kotlin side and gives us alpha cheaply.
# ----------------------------------------------------------------------
def render_hsv_mask_preview(
    rgba_bytes: bytes,
    width: int,
    height: int,
    ranges: List[List[int]],
) -> bytes:
    """Returns a PNG-encoded ARGB image: input frame with the HSV mask
    drawn over it in mint.

    Args:
        rgba_bytes: Tightly packed RGBA bytes, length ``width*height*4``.
        width, height: Frame dimensions in pixels.
        ranges: One or more six-int HSV ranges in the canonical
            ``[H_lo, S_lo, V_lo, H_hi, S_hi, V_hi]`` order. Ranges
            union; pass two ranges to handle hue wrap-around (red).

    Returns:
        PNG bytes ready for ``BitmapFactory.decodeByteArray``.
    """
    if width <= 0 or height <= 0:
        raise ValueError(f"width/height must be positive, got {width}x{height}")
    expected = width * height * 4
    if len(rgba_bytes) != expected:
        raise ValueError(
            f"rgba_bytes length {len(rgba_bytes)} does not match {width}x{height}*4 = {expected}"
        )

    flat = np.frombuffer(rgba_bytes, dtype=np.uint8).copy()
    rgba = flat.reshape((height, width, 4))
    bgr = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)

    mask = None
    for r in ranges:
        if len(r) != 6:
            raise ValueError(
                f"each range must be 6 ints; got {r}"
            )
        lower = np.array(r[:3], dtype=np.uint8)
        upper = np.array(r[3:], dtype=np.uint8)
        m = cv2.inRange(hsv, lower, upper)
        mask = m if mask is None else cv2.bitwise_or(mask, m)
    assert mask is not None

    # Composite: original frame, with the masked pixels tinted mint.
    # We dim the unmasked pixels slightly so the matched region pops.
    dimmed = (bgr * 0.55).astype(np.uint8)
    mint = np.zeros_like(bgr)
    mint[:] = (163, 217, 0)  # BGR for #00D9A3
    masked_blend = cv2.addWeighted(bgr, 0.4, mint, 0.6, 0)
    composite = np.where(mask[:, :, None] > 0, masked_blend, dimmed)

    rgb = cv2.cvtColor(composite, cv2.COLOR_BGR2RGB)
    success, png = cv2.imencode(".png", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    if not success:
        raise RuntimeError("PNG encode failed")
    return png.tobytes()


# ----------------------------------------------------------------------
# Single-frame entry point — Milestone 6.
# ----------------------------------------------------------------------
def analyze_single_frame(
    rgba_bytes: bytes,
    width: int,
    height: int,
    pin_hsv: Optional[List[List[int]]] = None,
    car_hsv: Optional[List[List[int]]] = None,
) -> Dict[str, Any]:
    """Runs the classical detector on one ARGB / RGBA frame.

    Args:
        rgba_bytes: Tightly packed RGBA bytes of length
            ``width * height * 4`` produced by
            :class:`com.bowltrack.video.VideoFrameExtractor`.
        width: Frame width in pixels.
        height: Frame height in pixels.
        pin_hsv: Optional ``[[H_lo, S_lo, V_lo, H_hi, S_hi, V_hi], ...]``
            HSV ranges for pin colour. Pass ``None`` to use the bundled
            default range tuned for white pins.
        car_hsv: Same shape, for car colour. Pass ``None`` to use the
            bundled default range tuned for coral red.

    Returns:
        Plain ``dict`` with the keys::

            {
              "width":    int,
              "height":   int,
              "pins":     [ {"x":int, "y":int, "w":int, "h":int,
                             "score":float, "label":"pin"}, ... ],
              "car":      [int, int] | None,
              "pin_count": int,
            }

        Lists/tuples are used rather than NumPy arrays so the Chaquopy
        bridge can hand them back to Kotlin without extra conversion.
    """
    if width <= 0 or height <= 0:
        raise ValueError(f"width/height must be positive, got {width}x{height}")
    expected = width * height * 4
    if len(rgba_bytes) != expected:
        raise ValueError(
            f"rgba_bytes length {len(rgba_bytes)} does not match {width}x{height}*4 = {expected}"
        )

    # ``frombuffer`` produces a read-only view; ``copy()`` so OpenCV can
    # write into it freely (e.g. cvtColor's destination buffer).
    flat = np.frombuffer(rgba_bytes, dtype=np.uint8).copy()
    rgba = flat.reshape((height, width, 4))
    bgr = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)

    config = _build_config(pin_hsv=pin_hsv, car_hsv=car_hsv)
    detector = ClassicalDetector(config)

    pins = detector.detect_pins(bgr)
    car = detector.detect_car(bgr)

    return {
        "width": width,
        "height": height,
        "pins": [
            {
                "x": int(d.bbox[0]),
                "y": int(d.bbox[1]),
                "w": int(d.bbox[2]),
                "h": int(d.bbox[3]),
                "score": float(d.score),
                "label": d.label,
            }
            for d in pins
        ],
        "car": list(car) if car is not None else None,
        "pin_count": len(pins),
    }


# ----------------------------------------------------------------------
# Combined classical + YOLO entry point — Milestone 7.
# ----------------------------------------------------------------------
def analyze_single_frame_combined(
    rgba_bytes: bytes,
    width: int,
    height: int,
    use_yolo: bool = True,
    yolo_model_path: Optional[str] = None,
    pin_hsv: Optional[List[List[int]]] = None,
    car_hsv: Optional[List[List[int]]] = None,
) -> Dict[str, Any]:
    """Runs the classical detector and (optionally) the YOLO tier.

    The two detectors run independently against the same frame so the
    Kotlin debug overlay can compare them side-by-side. Each tier
    contributes its own timing block; the function never raises if
    YOLO is unavailable — instead it returns ``yolo == None`` and a
    human-readable ``yolo_status`` string the UI can surface.

    Args:
        rgba_bytes: Tightly packed RGBA bytes of length ``width * height * 4``.
        width: Frame width in pixels.
        height: Frame height in pixels.
        use_yolo: When ``False``, the YOLO tier is skipped entirely.
            The returned dict still has a ``yolo`` key set to ``None``
            so callers can rely on a stable shape.
        yolo_model_path: Filesystem path to the bundled
            ``yolov8n_float16.tflite``. Required when ``use_yolo`` is
            ``True``; ignored otherwise.
        pin_hsv, car_hsv: Optional HSV range overrides; same shape as
            :func:`analyze_single_frame`.

    Returns:
        Plain ``dict`` with the keys::

            {
              "width":             int,
              "height":            int,
              "classical": {       # always present
                  "pins":          [{"x", "y", "w", "h", "score", "label"}, ...],
                  "car":           [int, int] | None,
                  "pin_count":     int,
              },
              "yolo": {            # None when disabled or unavailable
                  "detections":    [{"class_id", "class_name",
                                     "confidence", "bbox":[x1,y1,x2,y2]}, ...],
                  "pin_proxies":   [int, ...],   # indices into detections
                  "car_proxies":   [int, ...],
                  "stats": { "preprocess_ms": float, "model_ms": float,
                             "postprocess_ms": float, "total_ms": float },
              } | None,
              "yolo_status":          str,
              "timing_ms": {
                  "classical":     float,
                  "yolo":          float,
                  "total":         float,
              },
            }
    """
    if width <= 0 or height <= 0:
        raise ValueError(f"width/height must be positive, got {width}x{height}")
    expected = width * height * 4
    if len(rgba_bytes) != expected:
        raise ValueError(
            f"rgba_bytes length {len(rgba_bytes)} does not match {width}x{height}*4 = {expected}"
        )

    flat = np.frombuffer(rgba_bytes, dtype=np.uint8).copy()
    rgba = flat.reshape((height, width, 4))
    bgr = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)

    pipeline_started = perf_counter()

    # ---- Classical tier --------------------------------------------------
    classical_started = perf_counter()
    config = _build_config(pin_hsv=pin_hsv, car_hsv=car_hsv)
    classical = ClassicalDetector(config)
    classical_pins = classical.detect_pins(bgr)
    classical_car = classical.detect_car(bgr)
    classical_ms = (perf_counter() - classical_started) * 1000.0

    classical_payload: Dict[str, Any] = {
        "pins": [
            {
                "x": int(d.bbox[0]),
                "y": int(d.bbox[1]),
                "w": int(d.bbox[2]),
                "h": int(d.bbox[3]),
                "score": float(d.score),
                "label": d.label,
            }
            for d in classical_pins
        ],
        "car": list(classical_car) if classical_car is not None else None,
        "pin_count": len(classical_pins),
    }

    # ---- YOLO tier -------------------------------------------------------
    yolo_payload: Optional[Dict[str, Any]] = None
    yolo_status: str
    yolo_ms: float = 0.0

    if not use_yolo:
        yolo_status = "disabled"
    elif not yolo_model_path:
        yolo_status = "missing_path"
    elif not os.path.isfile(yolo_model_path):
        yolo_status = "missing_model"
    else:
        try:
            detector = _get_yolo_detector(yolo_model_path)
            # YOLO consumes RGB; cvtColor here costs ~1 ms on 720p.
            rgb = cv2.cvtColor(rgba, cv2.COLOR_RGBA2RGB)
            yolo_started = perf_counter()
            detections, stats = detector.detect(rgb)
            yolo_ms = (perf_counter() - yolo_started) * 1000.0

            detection_dicts = detections_to_dicts(detections)
            pin_proxies = [
                i for i, det in enumerate(detections)
                if det.class_id in PIN_PROXY_CLASSES
            ]
            car_proxies = [
                i for i, det in enumerate(detections)
                if det.class_id in CAR_PROXY_CLASSES
            ]
            yolo_payload = {
                "detections": detection_dicts,
                "pin_proxies": pin_proxies,
                "car_proxies": car_proxies,
                "stats": {
                    "preprocess_ms": float(stats.preprocess_ms),
                    "model_ms": float(stats.model_ms),
                    "postprocess_ms": float(stats.postprocess_ms),
                    "total_ms": float(stats.total_ms),
                },
            }
            yolo_status = "ok"
        except ImportError as exc:
            # tflite-runtime / tensorflow.lite are absent. The pipeline
            # carries on with the classical tier so the UI keeps working.
            yolo_status = "disabled (no on-device runtime)"
        except Exception as exc:  # pragma: no cover - defensive
            yolo_status = f"error: {exc}"

    total_ms = (perf_counter() - pipeline_started) * 1000.0

    return {
        "width": width,
        "height": height,
        "classical": classical_payload,
        "yolo": yolo_payload,
        "yolo_status": yolo_status,
        "timing_ms": {
            "classical": float(classical_ms),
            "yolo": float(yolo_ms),
            "total": float(total_ms),
        },
    }


# ----------------------------------------------------------------------
# Streaming orchestrator — Milestone 8.
#
# Tier 1 (classical CV) runs every frame.
# Tier 2 (YOLO TFLite) runs every ``yolo_every_n_frames`` frames as a
# verification + car-fallback step.
# Lucas-Kanade optical flow carries the car centroid forward across the
# frames where neither colour detection nor YOLO finds it.
# ----------------------------------------------------------------------
class PipelineOrchestrator:
    """Per-video analysis state.

    Kotlin instantiates one of these per video, pumps frames into
    :py:meth:`process_frame`, and finishes with :py:meth:`finalize` to
    receive the smoothed path, the fall-event log, and aggregate
    timing statistics.

    The orchestrator owns three independent state machines —
    classical detector + IoU pin tracker + fall detector for pins,
    Lucas-Kanade tracker for the car centroid, and the path tracker
    that buffers centroids for the smoothed replay overlay. Each is
    independently testable; the orchestrator exists only to compose
    them on a per-frame basis.

    Args:
        pin_hsv, car_hsv: Optional HSV range overrides as nested int
            lists. ``None`` keeps the bundled defaults.
        use_yolo: Master switch for the YOLO verification tier.
        yolo_model_path: Filesystem path to the bundled
            ``yolov8n_float16.tflite``. Required when ``use_yolo`` is
            ``True``; ignored otherwise.
        yolo_every_n_frames: How often (in frames) to re-run YOLO.
            5 matches the brief and is what the analysis screen
            reports as "every 5th frame". Tighter values (e.g. 1) run
            YOLO on every frame but pay the per-frame inference cost.
    """

    def __init__(
        self,
        pin_hsv: Optional[List[List[int]]] = None,
        car_hsv: Optional[List[List[int]]] = None,
        use_yolo: bool = True,
        yolo_model_path: Optional[str] = None,
        yolo_every_n_frames: int = 5,
    ) -> None:
        self.detector = ClassicalDetector(_build_config(pin_hsv, car_hsv))
        self.pin_tracker = PinTracker(iou_threshold=0.5)
        self.fall_detector = FallDetector()
        self.path_tracker = PathTracker()

        self.use_yolo = bool(use_yolo)
        self.yolo_model_path = yolo_model_path
        self.yolo_every_n_frames = max(1, int(yolo_every_n_frames))
        self.yolo_status: str = "disabled"
        if self.use_yolo and yolo_model_path and os.path.isfile(yolo_model_path):
            try:
                # Triggers interpreter allocation up-front so the cost
                # is paid before the first frame instead of mid-stream.
                _get_yolo_detector(yolo_model_path)
                self.yolo_status = "ok"
            except ImportError as exc:
                self.yolo_status = "disabled (no on-device runtime)"
            except Exception as exc:  # pragma: no cover - defensive
                self.yolo_status = f"error: {exc}"
        elif self.use_yolo and not yolo_model_path:
            self.yolo_status = "missing_path"
        elif self.use_yolo:
            self.yolo_status = "missing_model"

        # Lucas-Kanade state.
        self._prev_gray: Optional[np.ndarray] = None
        self._last_known_car: Optional[Tuple[float, float]] = None

        # Most recent analysis-frame size, surfaced from finalize() so
        # the Kotlin Results overlay can scale path coordinates back
        # into the video preview without re-probing the file.
        self._frame_width: int = 0
        self._frame_height: int = 0

        # Aggregates surfaced via :py:meth:`finalize`.
        self._frames_processed = 0
        self._classical_total_ms = 0.0
        self._yolo_total_ms = 0.0
        self._lk_total_ms = 0.0
        self._yolo_invocations = 0
        self._car_via_color = 0
        self._car_via_lk = 0
        self._car_via_yolo = 0
        self._car_missing = 0

    # ------------------------------------------------------------------
    @property
    def frames_processed(self) -> int:
        return self._frames_processed

    # ------------------------------------------------------------------
    def process_frame(
        self,
        rgba_bytes: bytes,
        width: int,
        height: int,
        frame_index: int,
        timestamp_seconds: float,
    ) -> Dict[str, Any]:
        """Folds a single decoded frame into the running analysis.

        The return value is a small dict the caller can stream directly
        to a Kotlin ``StateFlow`` for live UI feedback. Heavy data
        (full pin map, smoothed path) is accessed via :py:meth:`finalize`
        once the stream completes.
        """
        if width <= 0 or height <= 0:
            raise ValueError(f"width/height must be positive, got {width}x{height}")
        expected = width * height * 4
        if len(rgba_bytes) != expected:
            raise ValueError(
                f"rgba_bytes length {len(rgba_bytes)} does not match {width}x{height}*4 = {expected}"
            )

        flat = np.frombuffer(rgba_bytes, dtype=np.uint8).copy()
        rgba = flat.reshape((height, width, 4))
        bgr = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)

        # Capture the most recent analysis-frame size; surfaced via
        # finalize() so the Kotlin overlay can scale path coordinates.
        self._frame_width = width
        self._frame_height = height

        # ---- Tier 1: classical CV ---------------------------------------
        classical_started = perf_counter()
        pin_detections = self.detector.detect_pins(bgr)
        car_color = self.detector.detect_car(bgr)
        classical_ms = (perf_counter() - classical_started) * 1000.0
        self._classical_total_ms += classical_ms

        # ---- Tier 2: YOLO every Nth frame -------------------------------
        yolo_ms = 0.0
        yolo_pin_boxes: List[Tuple[int, int, int, int]] = []
        yolo_fallen_boxes: List[Tuple[int, int, int, int]] = []
        yolo_car_centroid: Optional[Tuple[float, float]] = None
        ran_yolo = False
        if (
            self.yolo_status == "ok"
            and frame_index % self.yolo_every_n_frames == 0
        ):
            try:
                detector = _get_yolo_detector(self.yolo_model_path)  # type: ignore[arg-type]
                rgb = cv2.cvtColor(rgba, cv2.COLOR_RGBA2RGB)
                yolo_started = perf_counter()
                detections, _stats = detector.detect(rgb)
                yolo_ms = (perf_counter() - yolo_started) * 1000.0
                self._yolo_total_ms += yolo_ms
                self._yolo_invocations += 1
                ran_yolo = True

                # Pin candidates include both standing and fallen
                # classes so the IoU tracker keeps a stable identity
                # whatever the pin's current state.
                yolo_pin_boxes = [
                    _xyxy_to_xywh(d.bbox)
                    for d in detections
                    if d.class_id in PIN_PROXY_CLASSES
                ]
                # The fallen-pins class is what makes this fine-tuned
                # model worth its weight: it gives us an authoritative
                # fall signal we can use to short-circuit the 5-frame
                # debounce.
                yolo_fallen_boxes = [
                    _xyxy_to_xywh(d.bbox)
                    for d in detections
                    if d.class_id == FALLEN_PIN_CLASS_ID
                ]
                # If we have any YOLO car proxies, take the highest
                # confidence one and use its centre as the car centroid.
                car_proxies = [d for d in detections if d.class_id in CAR_PROXY_CLASSES]
                if car_proxies:
                    car_proxies.sort(key=lambda d: d.confidence, reverse=True)
                    bbox = car_proxies[0].bbox
                    yolo_car_centroid = (
                        (bbox[0] + bbox[2]) / 2.0,
                        (bbox[1] + bbox[3]) / 2.0,
                    )
            except Exception as exc:  # pragma: no cover
                self.yolo_status = f"error: {exc}"

        # ---- Pin tier merge --------------------------------------------
        # Classical contours dominate: they fire every frame and are
        # geometrically tight. YOLO adds candidates only when classical
        # missed something — its boxes are added to the tracker if no
        # classical box overlaps them above the IoU threshold. This is
        # the "verification + recovery" wiring the brief calls for.
        merged_pin_boxes: List[Tuple[int, int, int, int]] = [
            d.bbox for d in pin_detections
        ]
        if yolo_pin_boxes:
            merged_pin_boxes = _merge_pin_boxes(merged_pin_boxes, yolo_pin_boxes)

        self.pin_tracker.update(
            frame_index=frame_index,
            detections=merged_pin_boxes,
        )

        # YOLO fallen-pin short-circuit. When the fine-tuned model
        # confidently labels a region as fallen-pins, we flip the
        # tracked identity that overlaps it directly. The classical
        # aspect-ratio + top-edge rules still run on every frame as a
        # backup, so a YOLO miss is not a fall miss; this just lets us
        # *react faster* on the frames YOLO sees.
        new_falls: List[Any] = []
        if yolo_fallen_boxes:
            new_falls.extend(
                self._apply_yolo_fall_signals(
                    fallen_boxes=yolo_fallen_boxes,
                    frame_index=frame_index,
                    timestamp_seconds=timestamp_seconds,
                )
            )
        new_falls.extend(
            self.fall_detector.update(
                frame_index=frame_index,
                timestamp_seconds=timestamp_seconds,
                pins=self.pin_tracker.pins,
            )
        )

        # ---- Car centroid resolution -----------------------------------
        # Priority: classical colour > YOLO car proxy > Lucas-Kanade
        # forward-projection of the last known centroid.
        car_source: str
        car_centroid: Optional[Tuple[float, float]]
        lk_started = perf_counter()
        if car_color is not None:
            car_centroid = (float(car_color[0]), float(car_color[1]))
            car_source = "color"
            self._car_via_color += 1
        elif yolo_car_centroid is not None:
            car_centroid = yolo_car_centroid
            car_source = "yolo"
            self._car_via_yolo += 1
        elif self._last_known_car is not None and self._prev_gray is not None:
            car_centroid = self._lucas_kanade_step(prev_gray=self._prev_gray, gray=gray)
            if car_centroid is not None:
                car_source = "lk"
                self._car_via_lk += 1
            else:
                car_source = "missing"
                self._car_missing += 1
        else:
            car_centroid = None
            car_source = "missing"
            self._car_missing += 1
        lk_ms = (perf_counter() - lk_started) * 1000.0
        self._lk_total_ms += lk_ms

        if car_centroid is not None:
            self._last_known_car = car_centroid
        # Always store the gray frame so the next iteration's LK has a
        # previous reference, even if the current frame had no car.
        self._prev_gray = gray
        self.path_tracker.append(car_centroid)
        self._frames_processed += 1

        return {
            "frame_index": frame_index,
            "timestamp_seconds": timestamp_seconds,
            "pin_count": len(self.pin_tracker.pins),
            "fallen_count": self.fall_detector.fallen_count,
            "new_falls": [
                {
                    "order": e.order,
                    "pin_id": e.pin_id,
                    "frame_index": e.frame_index,
                    "timestamp_seconds": e.timestamp_seconds,
                }
                for e in new_falls
            ],
            "car": list(car_centroid) if car_centroid is not None else None,
            "car_source": car_source,
            "ran_yolo": ran_yolo,
            "timing_ms": {
                "classical": float(classical_ms),
                "yolo": float(yolo_ms),
                "lk": float(lk_ms),
            },
        }

    # ------------------------------------------------------------------
    def finalize(self) -> Dict[str, Any]:
        """Returns the pipeline's final aggregate output."""
        smoothed = self.path_tracker.smooth_path()
        return {
            "total_pins": len(self.pin_tracker.pins),
            "fallen_pins": [
                {
                    "order": e.order,
                    "pin_id": e.pin_id,
                    "frame_index": e.frame_index,
                    "timestamp_seconds": e.timestamp_seconds,
                }
                for e in self.fall_detector.events
            ],
            "car_path": [list(p) for p in smoothed],
            "frame_count": self._frames_processed,
            "analysis_width": int(self._frame_width),
            "analysis_height": int(self._frame_height),
            "yolo_status": self.yolo_status,
            "yolo_invocations": self._yolo_invocations,
            "car_sources": {
                "color": self._car_via_color,
                "yolo": self._car_via_yolo,
                "lk": self._car_via_lk,
                "missing": self._car_missing,
            },
            "timing_total_ms": {
                "classical": float(self._classical_total_ms),
                "yolo": float(self._yolo_total_ms),
                "lk": float(self._lk_total_ms),
            },
        }

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------
    def _apply_yolo_fall_signals(
        self,
        fallen_boxes: List[Tuple[int, int, int, int]],
        frame_index: int,
        timestamp_seconds: float,
    ) -> List[Any]:
        """Flips tracked pins to FALLEN when a YOLO fallen-pin box
        overlaps them above the same IoU threshold the IoU tracker
        uses to match identities.

        Mirrors :class:`analysis.fall_detector.FallDetector`'s event
        emission so consumers cannot tell whether a fall came from
        the YOLO short-circuit or from the classical debounce.

        Returns the list of newly emitted events on this frame.
        """
        if not fallen_boxes:
            return []

        from analysis.fall_detector import FallEvent  # local to avoid cycle in type-checkers

        emitted: List[Any] = []
        threshold = self.pin_tracker.iou_threshold
        for pin in self.pin_tracker.pins.values():
            if pin.status == PinStatus.FALLEN:
                continue
            best = max(
                (_bbox_iou(pin.current_bbox, fb) for fb in fallen_boxes),
                default=0.0,
            )
            if best < threshold:
                continue

            pin.status = PinStatus.FALLEN
            pin.fall_frame_index = frame_index
            pin.fall_timestamp = timestamp_seconds
            self.fall_detector._fall_count += 1  # noqa: SLF001 — same project
            event = FallEvent(
                order=self.fall_detector._fall_count,
                pin_id=pin.id,
                frame_index=frame_index,
                timestamp_seconds=timestamp_seconds,
            )
            self.fall_detector._events.append(event)  # noqa: SLF001
            emitted.append(event)
        return emitted

    def _lucas_kanade_step(
        self,
        prev_gray: np.ndarray,
        gray: np.ndarray,
    ) -> Optional[Tuple[float, float]]:
        """Advances the last known car centroid via Lucas-Kanade flow.

        Uses a single point — the previously known centroid — rather
        than a dense corner set, because we only need to forward-
        project one location. ``calcOpticalFlowPyrLK`` returns a
        per-point status; we accept the new position only when status
        is 1 (matched) and the displacement is small enough to be
        plausible (no instantaneous teleports across the frame).
        """
        if self._last_known_car is None:
            return None
        prev_pts = np.array(
            [[self._last_known_car[0], self._last_known_car[1]]],
            dtype=np.float32,
        ).reshape(-1, 1, 2)
        next_pts, status, _err = cv2.calcOpticalFlowPyrLK(
            prev_gray,
            gray,
            prev_pts,
            None,
            winSize=(21, 21),
            maxLevel=2,
            criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03),
        )
        if next_pts is None or status is None or int(status[0][0]) != 1:
            return None
        nx, ny = float(next_pts[0][0][0]), float(next_pts[0][0][1])
        # Reject implausible jumps (>15 % of frame width per frame).
        h, w = gray.shape
        max_jump = max(w, h) * 0.15
        dx = nx - self._last_known_car[0]
        dy = ny - self._last_known_car[1]
        if (dx * dx + dy * dy) ** 0.5 > max_jump:
            return None
        return (nx, ny)


# ----------------------------------------------------------------------
# Public entry point — Milestone 8.
# ----------------------------------------------------------------------
def analyze_video(
    video_path: str,
    rgba_frame_provider: Any,
    progress_callback: Optional[Any] = None,
    pin_hsv: Optional[List[List[int]]] = None,
    car_hsv: Optional[List[List[int]]] = None,
    use_yolo: bool = True,
    yolo_model_path: Optional[str] = None,
    yolo_every_n_frames: int = 5,
) -> Dict[str, Any]:
    """End-to-end analysis driver.

    Frame decoding lives on the Kotlin side (the ``MediaCodec``-driven
    :class:`com.bowltrack.video.VideoFrameExtractor` is the canonical
    decoder for the project). This entry point therefore receives a
    Kotlin object that *iterates* RGBA frames; we pull from it until it
    returns ``None``. This avoids forcing the Python side to take a
    direct dependency on Android media APIs and keeps frame extraction
    and detection logic in their own layers, per the architecture rule.

    Args:
        video_path: Filesystem path of the source video. Carried in the
            return payload for diagnostics; the Python side never opens
            the file directly.
        rgba_frame_provider: Any object exposing
            ``next_frame() -> dict | None``. The dict shape is::

                {
                    "rgba": bytes,            # length width*height*4
                    "width": int,
                    "height": int,
                    "frame_index": int,
                    "timestamp_seconds": float,
                }

            Returning ``None`` ends the analysis. Kotlin code wraps
            :class:`com.bowltrack.video.VideoFrameExtractor` in such an
            iterator.
        progress_callback: Optional callable invoked every frame with
            ``(frame_index, fraction_complete_or_-1)``. The fraction is
            reported as ``-1`` when the total frame count is unknown,
            so the UI can fall back to an indeterminate spinner.
        pin_hsv, car_hsv: HSV overrides; same shape as the single-frame
            entry points.
        use_yolo, yolo_model_path, yolo_every_n_frames: see
            :class:`PipelineOrchestrator`.

    Returns:
        ``{ "video_path": str, ..., **orchestrator.finalize() }``.
    """
    orchestrator = PipelineOrchestrator(
        pin_hsv=pin_hsv,
        car_hsv=car_hsv,
        use_yolo=use_yolo,
        yolo_model_path=yolo_model_path,
        yolo_every_n_frames=yolo_every_n_frames,
    )

    while True:
        try:
            frame = rgba_frame_provider.next_frame()
        except Exception as exc:  # pragma: no cover - bridge defence
            return {
                "video_path": video_path,
                "error": f"frame_provider_error: {exc}",
                **orchestrator.finalize(),
            }
        if frame is None:
            break

        # Java LinkedHashMap proxies (delivered by Chaquopy from
        # Kotlin's mapOf(...)) don't always implement __getitem__, so
        # use .get() — present on both java.util.Map and dict.
        rgba = frame.get("rgba")
        width = int(frame.get("width"))
        height = int(frame.get("height"))
        frame_index = int(frame.get("frame_index"))
        timestamp_seconds = float(frame.get("timestamp_seconds"))

        per_frame = orchestrator.process_frame(
            rgba_bytes=rgba,
            width=width,
            height=height,
            frame_index=frame_index,
            timestamp_seconds=timestamp_seconds,
        )

        if progress_callback is not None:
            try:
                # The Kotlin layer accepts a snapshot dict so the live
                # Analysis overlay has every box it needs to render
                # without round-tripping through finalize().
                snapshot = _build_snapshot(
                    width=width,
                    height=height,
                    per_frame=per_frame,
                    pins=orchestrator.pin_tracker.pins,
                )
                progress_callback(frame_index, -1.0, snapshot)
            except Exception:  # pragma: no cover
                # A misbehaving callback must never abort an analysis;
                # we swallow and carry on so the user still gets the
                # final result.
                pass

    finalized = orchestrator.finalize()
    return {"video_path": video_path, **finalized}


def _build_snapshot(
    width: int,
    height: int,
    per_frame: Dict[str, Any],
    pins: Dict[int, Any],
) -> Dict[str, Any]:
    """Constructs the live snapshot pushed to the progress callback.

    Kept narrow on purpose: the Kotlin overlay only needs current pin
    bounding boxes (with a fallen flag), the latest car centroid, and
    the running fallen count. Everything else lives in the final
    aggregate result.
    """
    pin_payload = []
    for pin_id, pin in pins.items():
        x, y, w, h = pin.current_bbox
        pin_payload.append({
            "id": int(pin_id),
            "x": int(x),
            "y": int(y),
            "w": int(w),
            "h": int(h),
            "fallen": pin.is_fallen,
        })
    return {
        "width": width,
        "height": height,
        "frame_index": per_frame["frame_index"],
        "timestamp_seconds": per_frame["timestamp_seconds"],
        "pin_count": per_frame["pin_count"],
        "fallen_count": per_frame["fallen_count"],
        "car": per_frame["car"],
        "car_source": per_frame["car_source"],
        "pins": pin_payload,
    }


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
def _xyxy_to_xywh(bbox: Tuple[float, float, float, float]) -> Tuple[int, int, int, int]:
    """Converts a YOLO ``(x1, y1, x2, y2)`` corner box to the
    ``(x, y, w, h)`` form the rest of the pipeline uses internally."""
    x1, y1, x2, y2 = bbox
    return (
        int(round(x1)),
        int(round(y1)),
        max(0, int(round(x2 - x1))),
        max(0, int(round(y2 - y1))),
    )


def _merge_pin_boxes(
    classical: List[Tuple[int, int, int, int]],
    yolo: List[Tuple[int, int, int, int]],
) -> List[Tuple[int, int, int, int]]:
    """Adds YOLO pin boxes that no classical box overlaps.

    A YOLO box is treated as a "recovery" detection only when no
    classical box covers it above ``OVERLAP_IOU``. This keeps the pin
    list dominated by the tighter classical contours when both tiers
    agree, while still pulling in pins the classical pass missed.
    """
    OVERLAP_IOU = 0.4
    merged = list(classical)
    for yolo_box in yolo:
        if any(_iou_xywh(yolo_box, c) >= OVERLAP_IOU for c in classical):
            continue
        merged.append(yolo_box)
    return merged


def _iou_xywh(a: Tuple[int, int, int, int], b: Tuple[int, int, int, int]) -> float:
    """IoU for ``(x, y, w, h)`` boxes — duplicated from utils.geometry to
    keep this module's import surface minimal at orchestration time."""
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    inter_left = max(ax, bx)
    inter_top = max(ay, by)
    inter_right = min(ax + aw, bx + bw)
    inter_bottom = min(ay + ah, by + bh)
    inter_w = max(0, inter_right - inter_left)
    inter_h = max(0, inter_bottom - inter_top)
    inter = inter_w * inter_h
    if inter == 0:
        return 0.0
    union = aw * ah + bw * bh - inter
    if union <= 0:
        return 0.0
    return inter / float(union)


# ----------------------------------------------------------------------
# Configuration helpers
# ----------------------------------------------------------------------
def _build_config(
    pin_hsv: Optional[List[List[int]]],
    car_hsv: Optional[List[List[int]]],
) -> DetectorConfig:
    """Maps the Kotlin-friendly nested-list input into a DetectorConfig.

    Each input list is interpreted as
    ``[H_lo, S_lo, V_lo, H_hi, S_hi, V_hi]``; passing more than one such
    list is supported (the H ring wrap-around case for red).

    When ``pin_hsv`` / ``car_hsv`` is ``None`` the corresponding field
    on the returned config stays ``None``, which selects the
    color-agnostic detection path inside ``ClassicalDetector``.
    """
    import dataclasses
    config = DetectorConfig()
    if pin_hsv is not None:
        config = dataclasses.replace(
            config,
            pin_hsv=tuple(_unpack_range(r) for r in _to_pylist(pin_hsv)),
        )
    if car_hsv is not None:
        config = dataclasses.replace(
            config,
            car_hsv=tuple(_unpack_range(r) for r in _to_pylist(car_hsv)),
        )
    return config


def _to_pylist(seq):
    # Java ArrayList proxies (delivered by Chaquopy) don't always
    # satisfy Python's iter protocol. Use indexed access — Java List
    # exposes .size()/.get(i); native Python sequences fall back to
    # len()/[].
    if hasattr(seq, "size") and hasattr(seq, "get"):
        return [seq.get(i) for i in range(seq.size())]
    return [seq[i] for i in range(len(seq))]


def _unpack_range(values) -> HsvRange:
    values = _to_pylist(values)
    if len(values) != 6:
        raise ValueError(
            f"HSV range expects 6 ints [H_lo,S_lo,V_lo,H_hi,S_hi,V_hi]; got {values}"
        )
    h_lo = int(values[0]); s_lo = int(values[1]); v_lo = int(values[2])
    h_hi = int(values[3]); s_hi = int(values[4]); v_hi = int(values[5])
    return HsvRange(lower=(h_lo, s_lo, v_lo), upper=(h_hi, s_hi, v_hi))
