package com.bowltrack.python

import com.bowltrack.video.VideoFrame
import com.bowltrack.video.VideoFrameExtractor
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single, narrow seam between the Kotlin UI layer and the embedded
 * Python pipeline. Every Python entry point is exposed here as a
 * Kotlin function so the rest of the app never touches [PyObject]
 * directly.
 *
 * Heavy calls are wrapped in `suspend` functions that hop to
 * [Dispatchers.IO] on entry — Python's GIL makes parallelising
 * pointless from a single Kotlin process, so IO is the right pool: we
 * just need to keep these calls off the main thread.
 */
object PythonBridge {

    private val python: Python by lazy { Python.getInstance() }
    private val pipelineModule: PyObject by lazy { python.getModule("pipeline") }

    /**
     * Diagnostic ping. Returns the runtime versions reported by the
     * embedded interpreter; used by the M1 hello smoke test and as a
     * "is Python alive" probe by the analysis screens.
     */
    suspend fun helloVersions(): String = withContext(Dispatchers.IO) {
        pipelineModule.callAttr("hello").toString()
    }

    /**
     * Runs the classical-CV detector on a single frame.
     *
     * @param frame Decoded frame from [com.bowltrack.video.VideoFrameExtractor].
     *              The bridge sends the [VideoFrame.rgba] byte array
     *              and the width/height pair into Python verbatim;
     *              Python reshapes it into a NumPy array on its side.
     * @param pinHsvRanges Optional override for the pin colour ranges.
     *                     Each range is six ints
     *                     `[H_lo, S_lo, V_lo, H_hi, S_hi, V_hi]`.
     *                     Pass `null` to use the bundled defaults.
     * @param carHsvRanges Same shape, for the car colour.
     *
     * @return Decoded [SingleFrameResult]; never `null`.
     */
    /**
     * Returns a PNG-encoded preview where pixels matching the given
     * HSV [ranges] are tinted mint over a dimmed copy of [frame].
     * Used by the calibration screen to give the user immediate
     * "this is what your colour range matches" feedback.
     */
    suspend fun renderHsvMaskPreview(
        frame: VideoFrame,
        ranges: List<IntArray>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val py = pipelineModule.callAttr(
            "render_hsv_mask_preview",
            frame.rgba,
            frame.width,
            frame.height,
            ranges.toPythonList(),
        )
        py.toJava(ByteArray::class.java) as ByteArray
    }

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

    /**
     * Runs the classical detector and (optionally) the YOLO tier in
     * one round-trip. The two tiers operate independently so the UI
     * can compare them side-by-side; YOLO is automatically skipped
     * when [yoloModelPath] is null or the file does not exist on disk.
     *
     * @param frame Decoded frame from the video extractor.
     * @param useYolo Master switch. When `false`, YOLO is bypassed
     *                even if the model is bundled.
     * @param yoloModelPath On-disk path the Python tier opens via the
     *                      TFLite interpreter. Use [YoloModelAsset]
     *                      to obtain the path lazily.
     * @param pinHsvRanges Optional override for the pin colour ranges.
     * @param carHsvRanges Optional override for the car colour ranges.
     */
    suspend fun analyzeSingleFrameCombined(
        frame: VideoFrame,
        useYolo: Boolean,
        yoloModelPath: String?,
        pinHsvRanges: List<IntArray>? = null,
        carHsvRanges: List<IntArray>? = null,
    ): CombinedFrameResult = withContext(Dispatchers.IO) {
        val py = pipelineModule.callAttr(
            "analyze_single_frame_combined",
            frame.rgba,
            frame.width,
            frame.height,
            useYolo,
            yoloModelPath,
            pinHsvRanges?.toPythonList(),
            carHsvRanges?.toPythonList(),
        )
        py.toCombinedFrameResult()
    }

    /**
     * Drives the full hybrid pipeline against [video] from start to
     * finish. Python pulls frames from a Kotlin-owned provider, runs
     * the orchestrator (classical detector every frame, YOLO every
     * `yoloEveryNFrames`, Lucas-Kanade car fallback) and returns the
     * aggregate result.
     *
     * Live progress is exposed through [progress] — observe it from
     * Compose with `collectAsState()` while this suspend call is in
     * flight. The flow emits a [VideoAnalysisProgress] for each frame
     * the Python tier finishes processing.
     *
     * The function does not enforce any GIL semantics on the caller's
     * side: as long as no other Python entry is invoked in parallel,
     * the orchestrator is correct. Practically this means:
     *  - run from `Dispatchers.IO`,
     *  - do not run two analyses concurrently from the same process.
     */
    suspend fun analyzeVideo(
        video: File,
        useYolo: Boolean,
        yoloModelPath: String?,
        progress: MutableStateFlow<VideoAnalysisProgress>,
        targetFps: Int = 15,
        maxWidth: Int = 720,
        yoloEveryNFrames: Int = 5,
        pinHsvRanges: List<IntArray>? = null,
        carHsvRanges: List<IntArray>? = null,
    ): VideoAnalysisResult = withContext(Dispatchers.IO) {
        val extractor = VideoFrameExtractor(
            source = video,
            targetFps = targetFps,
            maxWidth = maxWidth,
        )
        val provider = FrameProvider(extractor)
        val callback = ProgressCallback(progress)

        try {
            val py = pipelineModule.callAttr(
                "analyze_video",
                video.absolutePath,
                provider,
                callback,
                pinHsvRanges?.toPythonList(),
                carHsvRanges?.toPythonList(),
                useYolo,
                yoloModelPath,
                yoloEveryNFrames,
            )
            py.toVideoAnalysisResult()
        } finally {
            provider.close()
        }
    }
}

/**
 * Per-frame progress snapshot driven by Python through the callback
 * passed to `analyze_video`. The frame index is authoritative; the
 * fraction is a convenience computed by the bridge from the most
 * recent extractor probe and may be `null` when total length is not
 * yet known.
 *
 * @property snapshot Optional rich snapshot of the current pipeline
 *                    state. The streaming entry point fills this in;
 *                    other callers may leave it `null`.
 */
data class VideoAnalysisProgress(
    val frameIndex: Int = -1,
    val fraction: Float? = null,
    val finished: Boolean = false,
    val snapshot: AnalysisSnapshot? = null,
)

/**
 * Live snapshot of the orchestrator's state on a single frame.
 *
 * Coordinates are in *analysis-frame* pixel space (i.e. after the
 * extractor's downscale). The Compose overlay scales them to the
 * preview's render size at draw time.
 */
data class AnalysisSnapshot(
    val width: Int,
    val height: Int,
    val frameIndex: Int,
    val timestampSeconds: Float,
    val pinCount: Int,
    val fallenCount: Int,
    val pins: List<TrackedPin>,
    val car: Pair<Int, Int>?,
    val carSource: String,
)

/** One tracked pin on the live overlay. */
data class TrackedPin(
    val id: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val fallen: Boolean,
)

/**
 * Aggregate result from `analyze_video`. Mirrors the dict the Python
 * orchestrator's `finalize()` returns.
 */
data class VideoAnalysisResult(
    val videoPath: String,
    val totalPins: Int,
    val fallenPins: List<FallenPinRecord>,
    val carPath: List<Pair<Float, Float>>,
    val frameCount: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val yoloStatus: String,
    val yoloInvocations: Int,
    val carSources: CarSources,
    val timingTotalMs: TimingBreakdown,
    val error: String? = null,
)

data class FallenPinRecord(
    val order: Int,
    val pinId: Int,
    val frameIndex: Int,
    val timestampSeconds: Float,
)

data class CarSources(
    val color: Int,
    val yolo: Int,
    val lk: Int,
    val missing: Int,
)

/**
 * Plain Kotlin mirror of the dictionary returned by
 * `pipeline.analyze_single_frame`.
 */
data class SingleFrameResult(
    val width: Int,
    val height: Int,
    val pinCount: Int,
    val pins: List<PinDetection>,
    val car: Pair<Int, Int>?,
)

/** One pin detection emitted by the classical detector. */
data class PinDetection(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val score: Float,
    val label: String,
)

/**
 * Combined classical + YOLO output from
 * [PythonBridge.analyzeSingleFrameCombined].
 *
 * @property classical Classical-detector output. Always populated.
 * @property yolo YOLO tier output, or `null` when YOLO was disabled or
 *                unavailable (in which case [yoloStatus] explains why).
 * @property yoloStatus One of `"ok"`, `"disabled"`, `"missing_path"`,
 *                      `"missing_model"`, `"runtime_unavailable: ..."`,
 *                      `"error: ..."` — surfaced to the UI verbatim.
 * @property timing Per-tier wall-clock breakdown.
 */
data class CombinedFrameResult(
    val width: Int,
    val height: Int,
    val classical: ClassicalPayload,
    val yolo: YoloPayload?,
    val yoloStatus: String,
    val timing: TimingBreakdown,
)

data class ClassicalPayload(
    val pinCount: Int,
    val pins: List<PinDetection>,
    val car: Pair<Int, Int>?,
)

data class YoloPayload(
    val detections: List<YoloBox>,
    val pinProxyIndices: List<Int>,
    val carProxyIndices: List<Int>,
    val stats: YoloStats,
)

/** Single YOLO detection — coordinates are corner form `(x1,y1,x2,y2)`. */
data class YoloBox(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

data class YoloStats(
    val preprocessMs: Float,
    val modelMs: Float,
    val postprocessMs: Float,
    val totalMs: Float,
)

data class TimingBreakdown(
    val classicalMs: Float,
    val yoloMs: Float,
    val totalMs: Float,
)

// ---------------------------------------------------------------------
// Frame provider + progress callback — Python pulls frames out of these
// objects through Chaquopy's duck-typed bridge.
// ---------------------------------------------------------------------

/**
 * Iterator-style adapter over [VideoFrameExtractor].
 *
 * Python's `analyze_video` calls `next_frame()` until it returns
 * `null`, at which point we treat the stream as exhausted. Internally
 * we eagerly pull frames from the extractor's flow on the IO dispatcher
 * via `runBlocking`, which is fine here because:
 *   - we're already on `Dispatchers.IO` (the bridge enforces it),
 *   - the alternative — exposing a real coroutine to Python — would
 *     require Chaquopy to know about `suspend`, which it doesn't.
 *
 * The class is `public` because Chaquopy generates a Java proxy that
 * the Python interpreter calls into; that requires the class to be
 * accessible from the platform classloader.
 */
class FrameProvider(extractor: VideoFrameExtractor) {

    private val iterator = extractor.frames().asBlockingIterator()

    /**
     * Returns a Python-friendly map for the next frame, or `null` when
     * the stream is exhausted. Map keys match the contract documented
     * on `pipeline.analyze_video`.
     */
    fun next_frame(): Map<String, Any>? {
        if (!iterator.hasNext()) return null
        val frame = iterator.next()
        return mapOf(
            "rgba" to frame.rgba,
            "width" to frame.width,
            "height" to frame.height,
            "frame_index" to frame.index,
            "timestamp_seconds" to frame.timestampSeconds,
        )
    }

    fun close() {
        iterator.cancel()
    }
}

/**
 * Kotlin object Python invokes once per frame via its
 * `progress_callback(frame_index, fraction, snapshot)` signature. We
 * funnel the value into a [MutableStateFlow] the UI observes.
 *
 * The third argument (the snapshot dict) is optional from Python's
 * perspective — when older entry points call us with only two
 * arguments we keep working. Chaquopy's reflection layer matches
 * methods by name + arity, so we expose two `__call__` overloads.
 */
class ProgressCallback(private val sink: MutableStateFlow<VideoAnalysisProgress>) {

    fun __call__(frameIndex: Int, fraction: Double) {
        val frac = if (fraction < 0.0) null else fraction.toFloat()
        sink.value = VideoAnalysisProgress(
            frameIndex = frameIndex,
            fraction = frac,
            finished = false,
            snapshot = null,
        )
    }

    fun __call__(frameIndex: Int, fraction: Double, snapshot: PyObject?) {
        val frac = if (fraction < 0.0) null else fraction.toFloat()
        val parsed = snapshot?.toAnalysisSnapshot()
        sink.value = VideoAnalysisProgress(
            frameIndex = frameIndex,
            fraction = frac,
            finished = false,
            snapshot = parsed,
        )
    }
}

/**
 * Pulls a `Flow` synchronously inside a blocking iterator. Python
 * cannot await a `suspend fun`, but it can call a regular method on a
 * Java object — so we collect the flow on a background thread we
 * control and expose `hasNext` / `next` over a thread-safe channel.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun Flow<VideoFrame>.asBlockingIterator(): BlockingFrameIterator =
    BlockingFrameIterator(this, GlobalScope)

private class BlockingFrameIterator(
    flow: Flow<VideoFrame>,
    scope: CoroutineScope,
) {

    private val channel = kotlinx.coroutines.channels.Channel<VideoFrame>(
        capacity = kotlinx.coroutines.channels.Channel.BUFFERED,
    )
    private val job = scope.launch(Dispatchers.IO) {
        try {
            flow.collect { channel.send(it) }
        } catch (cause: Throwable) {
            channel.close(cause)
            return@launch
        }
        channel.close()
    }

    private var nextFrame: VideoFrame? = null

    fun hasNext(): Boolean {
        if (nextFrame != null) return true
        return runBlocking {
            val received = channel.receiveCatching()
            if (received.isClosed) {
                received.exceptionOrNull()?.let { throw it }
                false
            } else {
                nextFrame = received.getOrThrow()
                true
            }
        }
    }

    fun next(): VideoFrame {
        if (nextFrame == null && !hasNext()) {
            throw NoSuchElementException("FrameProvider exhausted")
        }
        val out = nextFrame!!
        nextFrame = null
        return out
    }

    fun cancel() {
        runCatching { channel.cancel() }
        runCatching { job.cancel() }
    }
}

// ---------------------------------------------------------------------
// Conversion helpers — Chaquopy's PyObject is just dynamic enough that
// keeping the unwrapping in one place avoids sprinkling `.callAttr` and
// `.toJava(Int::class.java)` over the codebase.
// ---------------------------------------------------------------------

private fun List<IntArray>.toPythonList(): List<List<Int>> = map { it.toList() }

private fun PyObject.toSingleFrameResult(): SingleFrameResult {
    val map = this.toStringKeyMap()
    val width = map.getInt("width")
    val height = map.getInt("height")
    val pinCount = map.getInt("pin_count")
    val pinsPy = map["pins"] ?: error("pipeline result missing 'pins'")
    val pins = pinsPy.toPyList().map { it.toPinDetection() }
    val carPy = map["car"]
    val car = if (carPy == null || carPy.toString() == "None") {
        null
    } else {
        val list = carPy.toPyList()
        list[0].toJava(Int::class.javaPrimitiveType!!) as Int to
            list[1].toJava(Int::class.javaPrimitiveType!!) as Int
    }
    return SingleFrameResult(
        width = width,
        height = height,
        pinCount = pinCount,
        pins = pins,
        car = car,
    )
}

private fun PyObject.toPinDetection(): PinDetection {
    val map = this.toStringKeyMap()
    return PinDetection(
        x = map.getInt("x"),
        y = map.getInt("y"),
        w = map.getInt("w"),
        h = map.getInt("h"),
        score = map.getFloat("score"),
        label = map["label"]?.toString() ?: "pin",
    )
}

private fun PyObject.toCombinedFrameResult(): CombinedFrameResult {
    val map = this.toStringKeyMap()
    val width = map.getInt("width")
    val height = map.getInt("height")

    val classicalMap = (map["classical"] ?: error("missing 'classical'")).toStringKeyMap()
    val classicalPins = (classicalMap["pins"] ?: error("missing classical.pins"))
        .toPyList()
        .map { it.toPinDetection() }
    val classicalCarPy = classicalMap["car"]
    val classicalCar = if (classicalCarPy == null || classicalCarPy.toString() == "None") {
        null
    } else {
        val list = classicalCarPy.toPyList()
        list[0].toJava(Int::class.javaPrimitiveType!!) as Int to
            list[1].toJava(Int::class.javaPrimitiveType!!) as Int
    }

    val yoloPy = map["yolo"]
    val yolo = if (yoloPy == null || yoloPy.toString() == "None") {
        null
    } else {
        val yoloMap = yoloPy.toStringKeyMap()
        val detections = (yoloMap["detections"] ?: error("missing yolo.detections"))
            .toPyList()
            .map { it.toYoloBox() }
        val pinProxies = (yoloMap["pin_proxies"] ?: error("missing yolo.pin_proxies"))
            .toPyList()
            .map { it.toJava(Int::class.javaPrimitiveType!!) as Int }
        val carProxies = (yoloMap["car_proxies"] ?: error("missing yolo.car_proxies"))
            .toPyList()
            .map { it.toJava(Int::class.javaPrimitiveType!!) as Int }
        val statsMap = (yoloMap["stats"] ?: error("missing yolo.stats")).toStringKeyMap()
        YoloPayload(
            detections = detections,
            pinProxyIndices = pinProxies,
            carProxyIndices = carProxies,
            stats = YoloStats(
                preprocessMs = statsMap.getFloat("preprocess_ms"),
                modelMs = statsMap.getFloat("model_ms"),
                postprocessMs = statsMap.getFloat("postprocess_ms"),
                totalMs = statsMap.getFloat("total_ms"),
            ),
        )
    }

    val timingMap = (map["timing_ms"] ?: error("missing 'timing_ms'")).toStringKeyMap()
    val timing = TimingBreakdown(
        classicalMs = timingMap.getFloat("classical"),
        yoloMs = timingMap.getFloat("yolo"),
        totalMs = timingMap.getFloat("total"),
    )

    return CombinedFrameResult(
        width = width,
        height = height,
        classical = ClassicalPayload(
            pinCount = classicalMap.getInt("pin_count"),
            pins = classicalPins,
            car = classicalCar,
        ),
        yolo = yolo,
        yoloStatus = map["yolo_status"]?.toString() ?: "unknown",
        timing = timing,
    )
}

private fun PyObject.toYoloBox(): YoloBox {
    val map = this.toStringKeyMap()
    val bbox = (map["bbox"] ?: error("missing yolo detection.bbox")).toPyList()
    return YoloBox(
        classId = map.getInt("class_id"),
        className = map["class_name"]?.toString() ?: "?",
        confidence = map.getFloat("confidence"),
        x1 = bbox[0].toJava(Float::class.javaPrimitiveType!!) as Float,
        y1 = bbox[1].toJava(Float::class.javaPrimitiveType!!) as Float,
        x2 = bbox[2].toJava(Float::class.javaPrimitiveType!!) as Float,
        y2 = bbox[3].toJava(Float::class.javaPrimitiveType!!) as Float,
    )
}

private fun PyObject.toVideoAnalysisResult(): VideoAnalysisResult {
    val map = this.toStringKeyMap()

    val fallenPins = (map["fallen_pins"] ?: error("missing 'fallen_pins'"))
        .toPyList()
        .map { it.toFallenPinRecord() }

    val carPath = (map["car_path"] ?: error("missing 'car_path'"))
        .toPyList()
        .map { pointPy ->
            val pair = pointPy.toPyList()
            pair[0].toJava(Float::class.javaPrimitiveType!!) as Float to
                pair[1].toJava(Float::class.javaPrimitiveType!!) as Float
        }

    val carSourcesMap = (map["car_sources"] ?: error("missing 'car_sources'"))
        .toStringKeyMap()
    val carSources = CarSources(
        color = carSourcesMap.getInt("color"),
        yolo = carSourcesMap.getInt("yolo"),
        lk = carSourcesMap.getInt("lk"),
        missing = carSourcesMap.getInt("missing"),
    )

    val timingMap = (map["timing_total_ms"] ?: error("missing 'timing_total_ms'"))
        .toStringKeyMap()
    val timing = TimingBreakdown(
        classicalMs = timingMap.getFloat("classical"),
        yoloMs = timingMap.getFloat("yolo"),
        // The streaming orchestrator carries a Lucas-Kanade timing
        // bucket too; we surface it as the "total" slot of the
        // breakdown for now since the UI does not differentiate yet.
        totalMs = timingMap.getFloat("classical") +
            timingMap.getFloat("yolo") +
            timingMap.getFloat("lk"),
    )

    return VideoAnalysisResult(
        videoPath = map["video_path"]?.toString().orEmpty(),
        totalPins = map.getInt("total_pins"),
        fallenPins = fallenPins,
        carPath = carPath,
        frameCount = map.getInt("frame_count"),
        analysisWidth = map["analysis_width"]?.let {
            it.toJava(Int::class.javaPrimitiveType!!) as Int
        } ?: 0,
        analysisHeight = map["analysis_height"]?.let {
            it.toJava(Int::class.javaPrimitiveType!!) as Int
        } ?: 0,
        yoloStatus = map["yolo_status"]?.toString() ?: "unknown",
        yoloInvocations = map.getInt("yolo_invocations"),
        carSources = carSources,
        timingTotalMs = timing,
        error = map["error"]?.toString(),
    )
}

private fun PyObject.toFallenPinRecord(): FallenPinRecord {
    val map = this.toStringKeyMap()
    return FallenPinRecord(
        order = map.getInt("order"),
        pinId = map.getInt("pin_id"),
        frameIndex = map.getInt("frame_index"),
        timestampSeconds = map.getFloat("timestamp_seconds"),
    )
}

private fun PyObject.toAnalysisSnapshot(): AnalysisSnapshot {
    val map = this.toStringKeyMap()
    val pins = (map["pins"] ?: error("snapshot missing 'pins'"))
        .toPyList()
        .map { it.toTrackedPin() }
    val carPy = map["car"]
    val car = if (carPy == null || carPy.toString() == "None") {
        null
    } else {
        val list = carPy.toPyList()
        list[0].toJava(Int::class.javaPrimitiveType!!) as Int to
            list[1].toJava(Int::class.javaPrimitiveType!!) as Int
    }
    return AnalysisSnapshot(
        width = map.getInt("width"),
        height = map.getInt("height"),
        frameIndex = map.getInt("frame_index"),
        timestampSeconds = map.getFloat("timestamp_seconds"),
        pinCount = map.getInt("pin_count"),
        fallenCount = map.getInt("fallen_count"),
        pins = pins,
        car = car,
        carSource = map["car_source"]?.toString() ?: "missing",
    )
}

private fun PyObject.toTrackedPin(): TrackedPin {
    val map = this.toStringKeyMap()
    return TrackedPin(
        id = map.getInt("id"),
        x = map.getInt("x"),
        y = map.getInt("y"),
        w = map.getInt("w"),
        h = map.getInt("h"),
        fallen = (map["fallen"]?.toJava(Boolean::class.javaPrimitiveType!!) as? Boolean) ?: false,
    )
}

/**
 * Re-keys a Python dict by string keys. Chaquopy's `asMap()` returns
 * `Map<PyObject, PyObject>` because Python dicts can have non-string
 * keys, but the pipeline payloads only use string keys so we coerce
 * up front and avoid re-stringifying at every lookup.
 */
private fun PyObject.toStringKeyMap(): Map<String, PyObject> {
    @Suppress("UNCHECKED_CAST")
    val raw = this.asMap() as Map<PyObject, PyObject>
    return raw.entries.associate { (key, value) -> key.toString() to value }
}

private fun PyObject.toPyList(): List<PyObject> {
    @Suppress("UNCHECKED_CAST")
    return this.asList() as List<PyObject>
}

private fun Map<String, PyObject>.getInt(key: String): Int =
    requireNotNull(this[key]) { "missing key '$key' in Python result" }
        .toJava(Int::class.javaPrimitiveType!!) as Int

private fun Map<String, PyObject>.getFloat(key: String): Float =
    requireNotNull(this[key]) { "missing key '$key' in Python result" }
        .toJava(Float::class.javaPrimitiveType!!) as Float
