# BowlTrack — Architecture & Bridge Pattern

This document explains the seam between Kotlin and Python in BowlTrack — what it does, why
it looks the way it does, and how each layer's responsibilities are kept clean. It is the
companion document to `PYTHON_GUIDE.md`; together they cover the whole engineering surface
of the project.

## High-level layering

```
┌─────────────────────────────────────────────────────────────┐
│ UI shell (Compose)                                          │
│   • Home / History / Settings / Analysis / Results          │
│   • Debug screens for each milestone                        │
└──────────────────────────────┬──────────────────────────────┘
                               │ collect / call suspend funs
┌──────────────────────────────▼──────────────────────────────┐
│ Domain orchestration (Kotlin)                               │
│   • AnalysisViewModel                                       │
│   • SessionRepository (Room)                                │
│   • DetectionPreferences (DataStore)                        │
│   • VideoCaptureController (CameraX)                        │
│   • VideoFrameExtractor (MediaCodec)                        │
└──────────────────────────────┬──────────────────────────────┘
                               │ PythonBridge
┌──────────────────────────────▼──────────────────────────────┐
│ Chaquopy bridge (Kotlin ⇄ Python)                           │
│   • PythonBridge.kt — single seam                           │
│   • FrameProvider, ProgressCallback                         │
│   • Result unwrappers (PyObject → data class)               │
└──────────────────────────────┬──────────────────────────────┘
                               │ Python entry: analyze_video / …
┌──────────────────────────────▼──────────────────────────────┐
│ AI / CV pipeline (Python)                                   │
│   • pipeline.py orchestrator                                │
│   • detection / tracking / analysis sub-packages            │
│   • OpenCV + NumPy + tflite-runtime                         │
└─────────────────────────────────────────────────────────────┘
```

The architectural rule: **each layer can only call inward**. UI code never reaches Python
directly; Python never touches Android APIs. The bridge is the only place that knows about
both worlds.

## Why Chaquopy?

Three reasons:

1. **Author the CV in Python.** OpenCV's Python bindings are clean, well-documented, and
   the natural environment to teach / defend a CV pipeline. Re-implementing the same
   detection + tracking + smoothing logic in Kotlin / OpenCV-Java would be slower to write
   and noticeably less readable for a thesis defence.
2. **Single deployable artifact.** Chaquopy bundles CPython + the listed pip packages
   (NumPy, OpenCV, tflite-runtime) into the APK. There is no companion server, no on-device
   `pip install`, and no "make sure Python is installed" step for the user.
3. **The on-device offline contract is preserved.** No network is needed at runtime; the
   interpreter runs in-process inside the app's sandbox.

## Bridge contract

`PythonBridge.kt` is the single seam. Two design rules apply:

1. **Every Python entry point is exposed as a Kotlin `suspend` function.** Callers never
   touch `PyObject` and never block the main thread. The bridge dispatches all calls to
   `Dispatchers.IO` because Python's GIL makes parallelisation across threads pointless,
   and IO is the right pool for "this is going to take a while".
2. **Return values are unwrapped to plain Kotlin data classes at the bridge.** No
   `PyObject` ever escapes. The unwrapper helpers (`toStringKeyMap`, `toPyList`, etc.)
   convert the dynamic Chaquopy types into typed Kotlin shapes once, at the seam.

### Calling Python from Kotlin

A typical bridge call looks like:

```kotlin
suspend fun analyzeSingleFrame(
    frame: VideoFrame,
    pinHsvRanges: List<IntArray>? = null,
    carHsvRanges: List<IntArray>? = null,
): SingleFrameResult = withContext(Dispatchers.IO) {
    val py = pipelineModule.callAttr(
        "analyze_single_frame",
        frame.rgba,
        frame.width,
        frame.height,
        pinHsvRanges?.toPythonList(),
        carHsvRanges?.toPythonList(),
    )
    py.toSingleFrameResult()
}
```

Important: the byte array `frame.rgba` is sent across the bridge **by reference** — Chaquopy
hands Python a buffer that wraps the same memory the Kotlin layer just allocated. There is
no extra copy. Python uses `np.frombuffer(...).copy()` to take a writeable view it can hand
to OpenCV. This is what keeps the per-frame round-trip cheap.

### Calling Kotlin from Python

For long-running calls like `analyze_video`, Python pulls frames from a *Kotlin object* and
fires a *Kotlin callback* per frame. The bridge wraps these in two helper classes:

- **`FrameProvider`** wraps `VideoFrameExtractor`'s cold `Flow<VideoFrame>` in a blocking
  `next_frame()` method. Internally we collect the flow on a background coroutine and
  serve frames through a buffered `Channel`. Python's `analyze_video` pulls until it gets
  `None`; the iterator is cancelled in a `finally` block so cancellation is honoured even
  when Python aborts mid-stream.
- **`ProgressCallback`** is a Kotlin object Python calls with `(frame_index, fraction,
  snapshot)`. The callback funnels the value into a `MutableStateFlow<VideoAnalysisProgress>`
  the Compose `AnalysisScreen` collects. Two `__call__` overloads exist (two-arg and
  three-arg) because Chaquopy resolves Java methods by name + arity.

### Conversion helpers

Chaquopy hands Kotlin a `Map<PyObject, PyObject>` for any Python dict. We translate up
front:

```kotlin
private fun PyObject.toStringKeyMap(): Map<String, PyObject> {
    @Suppress("UNCHECKED_CAST")
    val raw = this.asMap() as Map<PyObject, PyObject>
    return raw.entries.associate { (key, value) -> key.toString() to value }
}
```

Same idea for `toPyList()` (lists), and small `getInt`/`getFloat` helpers over the
key-string-keyed map keep the unwrappers terse:

```kotlin
private fun Map<String, PyObject>.getInt(key: String): Int =
    requireNotNull(this[key]) { "missing key '$key' in Python result" }
        .toJava(Int::class.javaPrimitiveType!!) as Int
```

## Frame extraction stays in Kotlin

It would be tempting to push frame decoding into Python via `cv2.VideoCapture`. We don't,
for three reasons:

1. **Hardware decode.** Android's `MediaCodec` can hardware-decode H.264 / HEVC; OpenCV's
   `VideoCapture` on Android falls back to software decoding via FFmpeg.
2. **Single pass instead of per-frame seek.** `MediaMetadataRetriever.getFrameAtTime` is
   too slow for our purposes and `cv2.VideoCapture` doesn't fit cleanly in the Chaquopy
   model.
3. **Backpressure semantics.** Kotlin's coroutine `Flow` gives us a cold, cancellable,
   buffered stream out of the box; we want the production code to use that abstraction
   end-to-end.

So `VideoFrameExtractor` decodes a frame, downscales (max width 720), decimates to the
target FPS (default 15), runs YUV → RGBA conversion, and emits a `VideoFrame` carrying
both the byte array (the wire format for Python) and an `ARGB_8888` `Bitmap` (cheap to
draw on the Compose canvas for debugging).

## Persistence keeps Python out

Room runs entirely on the Kotlin side. The repository converts between `VideoAnalysisResult`
(the bridge's typed shape) and the three Room entities (`BowlingSession`, `FallenPinRecord`,
`PathPointRecord`). Same idea on the way back: `SavedRunMapper` reverse-builds a
`VideoAnalysisResult` from a saved bundle so the Results screen renders identically for
live and saved runs.

This keeps Python orchestration deterministic — given the same input video and the same
HSV ranges, you get the same output. No DB-shaped surprises.

## Threading map

| Where the work happens         | Dispatcher / thread                                    |
|--------------------------------|--------------------------------------------------------|
| Compose recomposition          | `Dispatchers.Main`                                     |
| Python entry calls (bridge)    | `Dispatchers.IO` via `withContext` in `PythonBridge`   |
| Frame decoding                 | Dedicated `HandlerThread` in `VideoFrameExtractor`     |
| Room queries                   | Room's internal background pool                        |
| DataStore writes               | DataStore's internal pool (we just `launch` to it)     |
| ProgressCallback's flow update | Inherits from where Python invokes it (Bridge IO pool) |
| Python execution itself        | Single-threaded (CPython GIL); no parallelism attempted|

## Lifecycle

- `BowlTrackApplication.onCreate` starts Chaquopy eagerly so the first analysis call
  doesn't pay the interpreter cold-start cost on the user's screen.
- `BowlTrackDatabase.getInstance()` is a process-wide singleton; the repository singleton
  hangs off `BowlTrackApplication` and is reachable from any composable via
  `LocalContext.applicationContext as BowlTrackApplication`.
- `AnalysisViewModel` cancels its analysis Job in `onCleared()` so navigating away from
  the analysis screen mid-run aborts the Python coroutine cleanly. The `BlockingFrameIterator`
  serving Python's `next_frame()` cancels its channel + collector job on close.

## Where to add things

Some pointers for future contributors:

- **A new Python entry point.** Add it to `pipeline.py`. Add a suspending wrapper +
  unwrapper to `PythonBridge.kt`. Treat the bridge as the public interface; everything
  else is a downstream consumer.
- **A new persisted column.** Add it to `BowlingSession` (or a child entity), bump the DB
  version, write the migration. The repository's `saveAnalysis` and `SavedRunMapper`
  cover the conversion both ways.
- **A new screen.** Build it under `ui/<feature>/`. Wire a route in
  `Routes.kt` + `BowlTrackNavHost.kt`. Reach for the existing design-system components
  (`PrimaryButton`, `GlassCard`, `CircularProgress`, `AnimatedScoreFraction`) before
  dropping to raw Compose primitives.
