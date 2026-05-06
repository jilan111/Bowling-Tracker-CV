# BowlTrack — Python pipeline guide

This is a guided tour of the BowlTrack Python tier — the part you (the student) will spend
most of your thesis defence explaining. Read it once top-to-bottom; afterwards each module
has its own header docstring with the full detail.

Everything under `app/src/main/python/` is a regular Python package. Chaquopy bundles a
CPython 3.11 interpreter into the APK and makes the package importable at runtime. There is
no compilation step on the Python side beyond the standard `.pyc` caching the interpreter
already does.

## Top-level layout

```
python/
├── pipeline.py                # Single Chaquopy entry module
├── detection/
│   ├── classical_detector.py  # Tier 1: HSV + contours + moments
│   ├── yolo_detector.py       # Tier 2: YOLOv8n TFLite + NMS
│   └── nms.py                 # Pure-NumPy non-maximum suppression
├── tracking/
│   ├── pin_tracker.py         # IoU-based identity tracker
│   └── path_tracker.py        # Catmull-Rom path smoothing
├── analysis/
│   └── fall_detector.py       # Aspect inversion + top-edge-drop rules
├── models/
│   ├── pin_state.py           # PinState dataclass + PinStatus enum
│   └── detection_result.py    # Detection + FrameDetections records
└── utils/
    ├── color_utils.py         # HsvRange + threshold_hsv()
    └── geometry.py            # bbox area, centre, aspect, IoU
```

## Data flow on a single frame

1. Kotlin's `VideoFrameExtractor` decodes one frame through `MediaCodec` + `ImageReader`,
   converts YUV → RGBA, and hands a tightly packed byte array plus `(width, height)` to
   Python via the bridge.
2. `pipeline.PipelineOrchestrator.process_frame()` reshapes the bytes into a
   `(height, width, 4)` NumPy array and converts it to BGR (OpenCV convention) plus a gray
   copy used by Lucas–Kanade.
3. **Tier 1 — classical CV** runs unconditionally: pin candidates from contour filtering
   plus a car centroid from the largest car-colour contour's moments.
4. **Tier 2 — YOLO** runs every Nth frame (default N=5). Pin proxies that overlap a
   classical box (IoU ≥ 0.4) are dropped; the rest are appended as recovery detections.
5. The merged pin-bbox list is fed into the IoU pin tracker, which reuses existing IDs for
   matches above IoU 0.5 and mints new IDs for unmatched detections.
6. The fall detector inspects every standing pin: if its aspect ratio inverted (< 1.0)
   *or* its top edge descended ≥ 40 % of its initial height, and that condition has held
   for at least 5 consecutive frames, the pin transitions to `FALLEN` and an event is
   logged with order, frame index, and timestamp.
7. The car centroid is resolved with a priority chain: **colour → YOLO car proxy →
   Lucas–Kanade forward-projection**. The chosen value (or `None`) is appended to the path
   tracker, which keeps a per-frame sample list.
8. The orchestrator emits a small per-frame snapshot dict (current pin boxes with `fallen`
   flags, car centroid, car source, fallen count) through the progress callback so the
   Kotlin overlay can paint live updates.

When the frame stream ends, `finalize()` runs Catmull–Rom smoothing on the path tracker's
samples and bundles the score, fall log, smoothed path, and diagnostic timings into the
return value.

## Module-by-module

### `pipeline.py`
Single Kotlin entry module. Public functions:

- `hello()` — returns OpenCV / NumPy versions; used by smoke tests.
- `analyze_single_frame(rgba_bytes, width, height, ...)` — debug entry that runs the
  classical detector on one frame.
- `analyze_single_frame_combined(...)` — the M7 debug entry running classical + YOLO
  side-by-side and reporting a timing breakdown.
- `analyze_video(video_path, frame_provider, progress_callback, ...)` — the production
  entry point. `frame_provider` is any object exposing `next_frame() -> dict | None`; the
  Kotlin layer wraps `VideoFrameExtractor` in such an iterator.
- `render_hsv_mask_preview(...)` — used by the calibration screen.

`PipelineOrchestrator` holds the per-video state. Its `process_frame()` is the heart of the
hybrid pipeline; `finalize()` collapses the running state into the aggregate result.

### `detection/classical_detector.py`
- `DetectorConfig` — dataclass of all knobs (HSV ranges, area / aspect thresholds,
  morphology kernel size, top-fraction quantile).
- `ClassicalDetector` — stateless, despite the class form, so the morphology kernel is
  allocated once. `detect_pins(frame_bgr)` and `detect_car(frame_bgr)` are the two API
  functions.

The interesting decisions are documented in the file's docstrings: elliptical kernel over
rectangular (rounded contours), score = sigmoid(area) × sigmoid(aspect), top-quantile cut
to bound the false-positive volume on bad lighting.

### `detection/yolo_detector.py`
- `YoloDetector` — wraps a TFLite interpreter (tflite-runtime preferred, full TF
  fallback). Letterboxes the input, runs inference, then transposes / argmaxes the
  `(1, 84, 8400)` output back into `[cx, cy, w, h]` boxes.
- Pre-process is pure NumPy — bilinear resize via `np.ix_` fancy indexing, then a
  grey-114 padded canvas. Post-process undoes the letterbox before NMS.
- `detect(frame_rgb)` returns `(detections, stats)` where `stats` carries per-step
  wall-clock for the debug overlay.

### `detection/nms.py`
`non_max_suppression(boxes, scores, iou_threshold, score_threshold, top_k)` — plain
NumPy implementation, O(n²) on the post-confidence-cut list. Returns indices, not rows,
so the caller can carry per-box metadata along.

### `tracking/pin_tracker.py`
Greedy IoU assignment. Pairs are scored, sorted descending, then claimed in order while
respecting "each existing pin and each detection can be claimed at most once per frame".
IDs are never recycled — important for the fall-order log to remain meaningful through
brief pin disappearances.

### `tracking/path_tracker.py`
Buffers per-frame centroids (allowing `None` gaps). `smooth_path(samples_per_segment)`
constructs phantom endpoints by reflecting across the second / penultimate point, then
runs centripetal Catmull–Rom (α = 0.5) on the resulting control list. Two-control-point
input falls back to linear interpolation; single-point input returns the input verbatim.

### `analysis/fall_detector.py`
The two-rule fall detector. Each pin owns a `consecutive_fallen_frames` counter; the
detector increments it while either rule fires and resets it otherwise. When the counter
crosses `required_consecutive`, the pin's status flips, the global fall counter increments,
and a `FallEvent(order, pin_id, frame_index, timestamp_seconds)` lands on the events log.

### `models/pin_state.py` and `models/detection_result.py`
Plain dataclasses. Worth a read so you know what types each layer hands the next; nothing
clever happens here.

### `utils/color_utils.py`
`HsvRange` is a frozen dataclass of `(lower, upper)` bounds. `threshold_hsv()` unions one
or more ranges into a single mask — that's how the pipeline handles red, which wraps the
hue boundary.

### `utils/geometry.py`
`bbox_area`, `bbox_center` (sub-pixel float), `aspect_ratio` (h/w convention so standing
pins > 1, fallen < 1), `iou`. The fall detector's "aspect inverted" check is one line of
code thanks to the convention chosen here.

## Things to be ready to defend in the oral exam

- Why classical *and* YOLO? — Classical is fast and tight (~3 ms) but brittle to lighting
  and overlap; YOLO is robust but slower (60–120 ms) and produces looser boxes. Running
  classical every frame plus YOLO every Nth gives us the best of both: fall detection
  reacts on every frame, and YOLO recovers misses without ballooning runtime.
- Why centripetal Catmull–Rom? — Avoids the cusps and self-intersections that uniform
  Catmull–Rom can produce when control points are unevenly spaced (unequal frame
  intervals).
- Why greedy IoU rather than the Hungarian algorithm? — Greedy is `O(n²)`, easy to reason
  about, and at our scale (≤ 12 candidate pins per frame) the difference is sub-millisecond.
- Why store the path as a child Room table instead of a JSON column? — A few hundred path
  points per video would make the JSON column unwieldy for queries; per-row storage gives
  Room cheap ordered reads via the `position` index and lets the foreign-key cascade clean
  up on delete without us doing anything.
- Why is `tflite-runtime` allowed to be missing at runtime? — Capstone defence demos may
  not always have it (export pipeline failures, OEM TFLite quirks); the pipeline degrades
  to classical + LK gracefully, which is the right behaviour for "the analysis still
  produces *something*".
