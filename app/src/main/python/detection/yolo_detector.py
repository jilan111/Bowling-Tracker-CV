"""YOLOv8n TFLite detector.

Designed for the Android side of BowlTrack: runs on CPU through
``tflite-runtime`` (or the equivalent ``tensorflow.lite`` interpreter
shipped with Chaquopy), with NumPy-only pre- and post-processing. The
detector is optional — the orchestrator only invokes it when the user
opts in. When the model file is missing we degrade gracefully by
returning an empty detection list rather than crashing the pipeline.

Model contract
--------------
The bundled checkpoint is ``yolov8n_float16.tflite`` exported with
Ultralytics' ``yolo export ... format=tflite half=True``. That export
produces a single-output graph::

    input  : float32, shape  (1, 640, 640, 3), values in [0, 1]
    output : float32, shape  (1, 84, 8400)

Output channels are layout (cx, cy, w, h, c0, c1, ..., c79) per
prediction; the last 80 channels are class scores (sigmoid already
applied by the export tool). 8400 is the number of anchors at
640×640. The decoder transposes to (8400, 84), picks the best class
per anchor, applies confidence + NMS, and rescales the surviving
boxes back to the source frame.

If a particular Ultralytics export emits the alternative ``(1, 8400,
84)`` layout, :func:`_extract_predictions` detects it from the shape
and adapts. Anything else raises ``ValueError`` early so we don't
silently produce nonsense.
"""

from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter
from typing import Any, List, Optional, Sequence, Tuple

import numpy as np

from .nms import non_max_suppression


# BowlTrack-specific classes from the fine-tuned model.
#
# Order MUST match the dataset's ``data.yaml`` ``names`` list. If you
# retrain with a different class ordering, update this tuple accordingly.
COCO_CLASSES: Tuple[str, ...] = (
    "ball",
    "car",
    "fallen-pins",
    "standing-pins",
)


# Class ids the orchestrator slices for.  Both standing and fallen
# pin classes count as "pin candidates" for the tracker; the fall
# detector then trusts the classifier rather than relying solely on
# the aspect-ratio heuristic.
PIN_PROXY_CLASSES: Tuple[int, ...] = (2, 3)   # fallen-pins, standing-pins
CAR_PROXY_CLASSES: Tuple[int, ...] = (1,)     # car
STANDING_PIN_CLASS_ID: int = 3
FALLEN_PIN_CLASS_ID: int = 2
BALL_CLASS_ID: int = 0


@dataclass
class YoloDetection:
    """Single detection emitted by the YOLO tier.

    Boxes are in ``[x1, y1, x2, y2]`` corner form, scaled back to the
    source frame's pixel coordinates. We do NOT round to ints here —
    the detector layer keeps sub-pixel precision so the orchestrator
    can decide (per consumer) whether to round or not.
    """

    class_id: int
    class_name: str
    confidence: float
    bbox: Tuple[float, float, float, float]


@dataclass
class YoloInferenceStats:
    """Wall-clock breakdown of one inference call. Surfaced verbatim
    so the UI's debug overlay can show preprocess / model / postprocess
    times separately."""

    preprocess_ms: float
    model_ms: float
    postprocess_ms: float

    @property
    def total_ms(self) -> float:
        return self.preprocess_ms + self.model_ms + self.postprocess_ms


def _load_interpreter(model_path: str):
    """Builds a TFLite interpreter without committing to one runtime.

    Tries ``tflite_runtime.interpreter`` first because it is what we
    declare in ``app/build.gradle.kts``. Falls back to
    ``tensorflow.lite`` for environments (e.g. local pytest on the
    developer's laptop) where only full TF is installed. If neither is
    available a clear ``ImportError`` is raised so the orchestrator can
    log it and disable the YOLO tier rather than crashing the whole
    analysis.
    """
    try:  # pragma: no cover - import path covered indirectly
        from tflite_runtime.interpreter import Interpreter
        return Interpreter(model_path=model_path)
    except ImportError:
        pass
    try:  # pragma: no cover
        from tensorflow.lite.python.interpreter import Interpreter as TfInterpreter
        return TfInterpreter(model_path=model_path)
    except ImportError as exc:  # pragma: no cover
        raise ImportError(
            "Neither tflite-runtime nor tensorflow.lite is available. "
            "Install one of them or disable the YOLO tier."
        ) from exc


class YoloDetector:
    """YOLOv8n TFLite detector.

    Construct once per analysis (the interpreter holds a non-trivial
    amount of state and reallocates tensors every time it is built).
    Threading: the interpreter is *not* thread-safe; the orchestrator
    only ever calls :meth:`detect` from the single-threaded Chaquopy
    pipeline, which honours that contract.

    Args:
        model_path: Filesystem path to the TFLite checkpoint. Usually
            ``${context.filesDir}/yolov8n_float16.tflite``; the
            Application bootstrap copies the asset out of the APK on
            first launch (Milestone 8).
        confidence_threshold: Score below which raw predictions are
            discarded before NMS. The Ultralytics default is 0.25; we
            keep that.
        iou_threshold: IoU threshold passed to NMS.
        num_threads: Interpreter thread count. ``2`` keeps the UI
            responsive on a 4-core phone without leaving headroom on
            the table — we never go above that for safety.
        class_filter: Optional whitelist of class ids the caller cares
            about. Detections with class ids outside the set are
            dropped before NMS to keep the survivor list short.
    """

    INPUT_SIZE: int = 640

    def __init__(
        self,
        model_path: str,
        confidence_threshold: float = 0.25,
        iou_threshold: float = 0.45,
        num_threads: int = 2,
        class_filter: Optional[Sequence[int]] = None,
    ) -> None:
        self.model_path = model_path
        self.confidence_threshold = float(confidence_threshold)
        self.iou_threshold = float(iou_threshold)
        self.class_filter = (
            None if class_filter is None else tuple(int(c) for c in class_filter)
        )

        interpreter = _load_interpreter(model_path)
        # Allocating tensors after setting num_threads keeps the
        # initial allocation aware of the threading config.
        if hasattr(interpreter, "set_num_threads"):
            interpreter.set_num_threads(int(num_threads))
        interpreter.allocate_tensors()

        self._interpreter = interpreter
        self._input_details = interpreter.get_input_details()[0]
        self._output_details = interpreter.get_output_details()[0]

        # Cache shape so the per-frame hot path does not poke into the
        # interpreter for it. The export pin guarantees (1, 640, 640, 3).
        self._input_shape = tuple(int(d) for d in self._input_details["shape"])
        if len(self._input_shape) != 4 or self._input_shape[1] != self.INPUT_SIZE:
            # The detector is single-purpose; refuse to run if the
            # model someone bundled doesn't match expectations.
            raise ValueError(
                f"Unexpected YOLO input shape {self._input_shape}; expected "
                f"(1, {self.INPUT_SIZE}, {self.INPUT_SIZE}, 3)"
            )

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def detect(
        self,
        frame_rgb: np.ndarray,
    ) -> Tuple[List[YoloDetection], YoloInferenceStats]:
        """Runs the model on ``frame_rgb`` and returns NMS'd detections.

        Args:
            frame_rgb: ``(H, W, 3)`` uint8 array in **RGB** order.
                The orchestrator hands us RGB rather than BGR so the
                YOLO tier does not need to know that the rest of the
                pipeline lives in OpenCV BGR conventions.

        Returns:
            ``(detections, stats)`` — see :class:`YoloDetection` and
            :class:`YoloInferenceStats`.
        """
        if frame_rgb.ndim != 3 or frame_rgb.shape[2] != 3:
            raise ValueError(
                f"frame_rgb must be (H, W, 3); got shape {frame_rgb.shape}"
            )

        h_src, w_src, _ = frame_rgb.shape

        t0 = perf_counter()
        net_input, scale, pad_x, pad_y = _letterbox(frame_rgb, self.INPUT_SIZE)
        t1 = perf_counter()

        self._interpreter.set_tensor(self._input_details["index"], net_input)
        self._interpreter.invoke()
        raw = self._interpreter.get_tensor(self._output_details["index"])
        t2 = perf_counter()

        detections = self._postprocess(
            raw=raw,
            scale=scale,
            pad_x=pad_x,
            pad_y=pad_y,
            src_w=w_src,
            src_h=h_src,
        )
        t3 = perf_counter()

        stats = YoloInferenceStats(
            preprocess_ms=(t1 - t0) * 1000.0,
            model_ms=(t2 - t1) * 1000.0,
            postprocess_ms=(t3 - t2) * 1000.0,
        )
        return detections, stats

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _postprocess(
        self,
        raw: np.ndarray,
        scale: float,
        pad_x: float,
        pad_y: float,
        src_w: int,
        src_h: int,
    ) -> List[YoloDetection]:
        boxes_xywh, class_ids, confidences = _extract_predictions(
            raw, score_threshold=self.confidence_threshold,
        )
        if boxes_xywh.shape[0] == 0:
            return []

        if self.class_filter is not None:
            keep = np.isin(class_ids, np.asarray(self.class_filter, dtype=np.int64))
            boxes_xywh = boxes_xywh[keep]
            class_ids = class_ids[keep]
            confidences = confidences[keep]
            if boxes_xywh.shape[0] == 0:
                return []

        # YOLOv8 emits center-xy + size in the 640x640 letterboxed
        # space. Convert to corners, undo the letterbox, and clamp to
        # the source frame.
        boxes_xyxy = _xywh_to_xyxy(boxes_xywh)
        boxes_xyxy[:, [0, 2]] = (boxes_xyxy[:, [0, 2]] - pad_x) / scale
        boxes_xyxy[:, [1, 3]] = (boxes_xyxy[:, [1, 3]] - pad_y) / scale
        boxes_xyxy[:, 0::2] = np.clip(boxes_xyxy[:, 0::2], 0, src_w - 1)
        boxes_xyxy[:, 1::2] = np.clip(boxes_xyxy[:, 1::2], 0, src_h - 1)

        survivors = non_max_suppression(
            boxes=boxes_xyxy,
            scores=confidences,
            iou_threshold=self.iou_threshold,
            score_threshold=self.confidence_threshold,
        )

        detections: List[YoloDetection] = []
        for idx in survivors:
            cls = int(class_ids[idx])
            x1, y1, x2, y2 = boxes_xyxy[idx]
            detections.append(
                YoloDetection(
                    class_id=cls,
                    class_name=COCO_CLASSES[cls] if 0 <= cls < len(COCO_CLASSES) else str(cls),
                    confidence=float(confidences[idx]),
                    bbox=(float(x1), float(y1), float(x2), float(y2)),
                )
            )
        return detections


# ----------------------------------------------------------------------
# Pre- and post-processing helpers (kept module-level for easy testing).
# ----------------------------------------------------------------------
def _letterbox(
    frame_rgb: np.ndarray,
    target: int,
) -> Tuple[np.ndarray, float, float, float]:
    """Resizes ``frame_rgb`` into ``target × target`` while preserving
    aspect ratio, padding the unused pixels with neutral grey.

    Returns:
        ``(net_input, scale, pad_x, pad_y)`` where ``net_input`` has
        shape ``(1, target, target, 3)`` float32 in ``[0, 1]``, and the
        scale/pad terms describe how to undo the transform when
        decoding model outputs back to source-frame coordinates.

    Implementation note: we use bilinear resize implemented through a
    NumPy index trick rather than pulling in ``cv2.resize`` here. This
    keeps the module's dependency surface minimal and matches the
    "no OpenCV inside the YOLO tier" guidance — OpenCV is fine, but
    isolating the model code means a future swap to e.g. NPU-only
    runtime is one file away.
    """
    h, w = frame_rgb.shape[:2]
    scale = min(target / w, target / h)
    new_w = max(1, int(round(w * scale)))
    new_h = max(1, int(round(h * scale)))

    resized = _bilinear_resize(frame_rgb, new_w, new_h)

    canvas = np.full((target, target, 3), 114, dtype=np.uint8)  # YOLO grey
    pad_x = (target - new_w) / 2.0
    pad_y = (target - new_h) / 2.0
    x_offset = int(round(pad_x))
    y_offset = int(round(pad_y))
    canvas[y_offset : y_offset + new_h, x_offset : x_offset + new_w] = resized

    net_input = canvas.astype(np.float32) / 255.0
    net_input = np.expand_dims(net_input, axis=0)  # (1, H, W, 3)
    return net_input, scale, pad_x, pad_y


def _bilinear_resize(image: np.ndarray, new_w: int, new_h: int) -> np.ndarray:
    """Vectorised bilinear resize using NumPy fancy indexing.

    Fast enough for 720p input on mobile (~2 ms in the worst case);
    keeps us off OpenCV inside the YOLO tier.
    """
    h, w = image.shape[:2]
    if (w, h) == (new_w, new_h):
        return image

    x = (np.arange(new_w, dtype=np.float32) + 0.5) * w / new_w - 0.5
    y = (np.arange(new_h, dtype=np.float32) + 0.5) * h / new_h - 0.5

    x0 = np.clip(np.floor(x).astype(np.int32), 0, w - 1)
    x1 = np.clip(x0 + 1, 0, w - 1)
    y0 = np.clip(np.floor(y).astype(np.int32), 0, h - 1)
    y1 = np.clip(y0 + 1, 0, h - 1)

    wx = (x - x0).astype(np.float32)
    wy = (y - y0).astype(np.float32)

    img = image.astype(np.float32)
    top_left = img[np.ix_(y0, x0)]
    top_right = img[np.ix_(y0, x1)]
    bottom_left = img[np.ix_(y1, x0)]
    bottom_right = img[np.ix_(y1, x1)]

    top = top_left * (1 - wx)[None, :, None] + top_right * wx[None, :, None]
    bottom = bottom_left * (1 - wx)[None, :, None] + bottom_right * wx[None, :, None]
    out = top * (1 - wy)[:, None, None] + bottom * wy[:, None, None]
    return np.clip(out, 0.0, 255.0).astype(np.uint8)


def _extract_predictions(
    raw: np.ndarray,
    score_threshold: float,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Decodes the raw YOLO output tensor into separate arrays.

    Returns:
        ``(boxes_xywh, class_ids, confidences)`` where ``boxes_xywh`` is
        ``(M, 4)`` in ``[cx, cy, w, h]`` form *in 640×640 letterbox
        space*, ``class_ids`` is ``(M,)`` int, and ``confidences`` is
        ``(M,)`` float. ``M`` is post-confidence-threshold.
    """
    if raw.ndim == 3 and raw.shape[0] == 1:
        # Squeeze the batch dimension.
        raw = raw[0]
    if raw.ndim != 2:
        raise ValueError(
            f"Unexpected YOLO output shape {raw.shape}; expected 2-D after squeeze"
        )

    # YOLOv8's detection head emits ``(4 + num_classes, num_anchors)``
    # by default; some exporters transpose to ``(num_anchors,
    # 4 + num_classes)``. We deduce ``num_classes`` from the smaller
    # axis: anchors typically run into the thousands, classes do not.
    expected_channels = 4 + len(COCO_CLASSES)
    if raw.shape[0] == expected_channels and raw.shape[1] != expected_channels:
        predictions = raw.T  # (anchors, 4 + num_classes)
    elif raw.shape[1] == expected_channels:
        predictions = raw
    else:
        raise ValueError(
            f"YOLO output {raw.shape} does not match either "
            f"({expected_channels}, anchors) or (anchors, {expected_channels}) layout. "
            f"Did the model retrain with a different number of classes?"
        )

    boxes_xywh = predictions[:, :4]
    class_scores = predictions[:, 4:]
    class_ids = np.argmax(class_scores, axis=1).astype(np.int64)
    confidences = class_scores[np.arange(class_scores.shape[0]), class_ids]

    keep = confidences >= score_threshold
    return boxes_xywh[keep], class_ids[keep], confidences[keep]


def _xywh_to_xyxy(boxes_xywh: np.ndarray) -> np.ndarray:
    """Converts ``[cx, cy, w, h]`` rows into ``[x1, y1, x2, y2]``."""
    cx = boxes_xywh[:, 0]
    cy = boxes_xywh[:, 1]
    w = boxes_xywh[:, 2]
    h = boxes_xywh[:, 3]
    out = np.empty_like(boxes_xywh)
    out[:, 0] = cx - w / 2.0
    out[:, 1] = cy - h / 2.0
    out[:, 2] = cx + w / 2.0
    out[:, 3] = cy + h / 2.0
    return out


# Convenience export so the orchestrator can carry the dataclass list
# across the bridge without re-importing this module.
def detections_to_dicts(detections: Sequence[YoloDetection]) -> List[dict]:
    """Serialises a list of detections into Chaquopy-friendly dicts."""
    return [
        {
            "class_id": int(d.class_id),
            "class_name": d.class_name,
            "confidence": float(d.confidence),
            "bbox": [float(d.bbox[0]), float(d.bbox[1]), float(d.bbox[2]), float(d.bbox[3])],
        }
        for d in detections
    ]
