package com.bowltrack.ui.analysis

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bowltrack.data.prefs.DetectionPreferences
import com.bowltrack.python.AnalysisSnapshot
import com.bowltrack.python.PythonBridge
import com.bowltrack.python.VideoAnalysisProgress
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.python.YoloModelAsset
import com.bowltrack.video.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI-state for the Analysis screen.
 *
 * Carrying every observable in one immutable record keeps the
 * [AnalysisScreen] composable lean — it `collectAsState()` once and
 * never has to resolve null-races between sub-flows. The Python tier
 * pushes per-frame updates through [progress]; everything else is
 * derived locally.
 */
data class AnalysisUiState(
    val isRunning: Boolean = false,
    val progress: VideoAnalysisProgress = VideoAnalysisProgress(),
    val snapshot: AnalysisSnapshot? = null,
    val durationSeconds: Double = 0.0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val errorMessage: String? = null,
    val result: VideoAnalysisResult? = null,
)

/**
 * Backs [AnalysisScreen]. Owns the analysis coroutine, the progress
 * flow it hands to Python, and the derived UI state.
 *
 * Lifecycle: the screen instantiates one of these per "session" (i.e.
 * per video). Cancellation is handled via the Job we hold; calling
 * [cancel] tears down the analysis without leaving the Python
 * orchestrator dangling.
 */
class AnalysisViewModel(
    private val appContext: Context,
) : ViewModel() {

    private val mutableProgress = MutableStateFlow(VideoAnalysisProgress())
    private val mutableState = MutableStateFlow(AnalysisUiState())

    /** Streamed UI state for the Analysis screen. */
    val state: StateFlow<AnalysisUiState> = mutableState.asStateFlow()

    private var analysisJob: Job? = null

    /**
     * Kicks off analysis on [video]. Safe to call only once per
     * instance — subsequent invocations are no-ops while the prior
     * job is still running.
     */
    fun start(video: File) {
        if (analysisJob?.isActive == true) return

        mutableState.value = AnalysisUiState(isRunning = true)

        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            // Probe up front so the UI can report duration even before
            // the first decoded frame lands. Failure here is fatal —
            // the source file is bad and there is nothing to analyse.
            val probe = runCatching {
                VideoFrameExtractor(video).probe()
            }.getOrElse { error ->
                mutableState.value = mutableState.value.copy(
                    isRunning = false,
                    errorMessage = "Could not read video: ${error.message}",
                )
                return@launch
            }

            mutableState.value = mutableState.value.copy(
                durationSeconds = probe.durationSeconds,
                sourceWidth = probe.width,
                sourceHeight = probe.height,
            )

            // Pull persisted detection preferences. `first()` rather
            // than `collect()` because we sample once per analysis;
            // the calibration screen ensures the user has saved before
            // returning here.
            val prefs = DetectionPreferences(appContext)
            val settings = prefs.flow.first()
            val useYolo = settings.useYoloFallback

            // Bridge the Python progress flow into our state machine.
            // Collecting in a child coroutine keeps the analyzeVideo()
            // suspend point clean while still letting us merge
            // snapshots into the public StateFlow.
            val progressJob = launch {
                mutableProgress.collect { progress ->
                    mutableState.value = mutableState.value.copy(
                        progress = progress,
                        snapshot = progress.snapshot ?: mutableState.value.snapshot,
                    )
                }
            }

            val outcome = runCatching {
                val modelPath = if (useYolo) YoloModelAsset.ensureExtracted(appContext) else null
                PythonBridge.analyzeVideo(
                    video = video,
                    useYolo = useYolo,
                    yoloModelPath = modelPath,
                    progress = mutableProgress,
                    // Pass null when the user has not calibrated, so
                    // the Python detector picks the colour-agnostic
                    // path instead of being pinned to a specific hue.
                    pinHsvRanges = settings.customPinRanges,
                    carHsvRanges = settings.customCarRanges,
                )
            }
            progressJob.cancel()

            withContext(Dispatchers.Main) {
                outcome
                    .onSuccess { result ->
                        mutableState.value = mutableState.value.copy(
                            isRunning = false,
                            result = result,
                            progress = mutableState.value.progress.copy(finished = true),
                        )
                    }
                    .onFailure { error ->
                        mutableState.value = mutableState.value.copy(
                            isRunning = false,
                            errorMessage = error.message ?: error::class.java.simpleName,
                        )
                    }
            }
        }
    }

    /** Cancels the in-flight analysis. Safe at any time. */
    fun cancel() {
        analysisJob?.cancel()
        analysisJob = null
        if (mutableState.value.isRunning) {
            mutableState.value = mutableState.value.copy(
                isRunning = false,
                errorMessage = "Cancelled.",
            )
        }
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
