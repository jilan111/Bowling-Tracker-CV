package com.bowltrack.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * State surfaced by [VideoCaptureController].
 *
 * The controller exposes a single [StateFlow] rather than fine-grained
 * properties so the Compose UI only has to collect one stream. Each
 * value is a snapshot — never mutate the contained [Recording] reference
 * directly; round-trip through the controller's API instead.
 */
data class VideoCaptureState(
    val isInitialised: Boolean = false,
    val isRecording: Boolean = false,
    val elapsedNanos: Long = 0L,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val lastError: Throwable? = null,
)

/**
 * Outcome reported when a recording finishes.
 *
 * @property file On-disk video file. Lives in app-private storage so the
 *                user does not have to grant any extra storage
 *                permissions to read it back during analysis.
 * @property durationMillis Wall-clock duration of the recording.
 */
data class VideoCaptureResult(
    val file: File,
    val durationMillis: Long,
)

/**
 * Thin domain wrapper around CameraX video capture.
 *
 * The point of this class is to give the Compose layer one stable handle
 * with a small, testable surface area:
 *
 *   - `bindToLifecycle(...)` once, when the screen attaches.
 *   - `startRecording()` / `stopRecording()` round-trip through the
 *     controller's coroutines so callers do not see CameraX's listener
 *     callbacks.
 *   - `state` is a [StateFlow] the Compose UI collects to drive the
 *     timer, button state, and error toasts.
 *
 * Threading: CameraX's listeners fire on the main executor returned by
 * [ContextCompat.getMainExecutor], so observers are driven on the main
 * thread and Compose state writes are safe without extra dispatch.
 *
 * Lifecycle: the controller binds use cases to the [LifecycleOwner] that
 * owns the recorder screen; CameraX automatically tears down its
 * [androidx.camera.core.Camera] instance when that owner is destroyed,
 * so callers do not need to call `unbind()` explicitly. They *should*
 * call [stopRecording] from `onPause` if a recording is in progress —
 * see [bindToLifecycle] for why.
 *
 * @param context Application context. Used for the camera provider
 *                handle and the main-thread executor.
 */
class VideoCaptureController(private val context: Context) {

    private val mutableState = MutableStateFlow(VideoCaptureState())
    /** Snapshot of every observable value the controller manages. */
    val state: StateFlow<VideoCaptureState> = mutableState.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartNanos: Long = 0L
    private var pendingResult: ((VideoCaptureResult) -> Unit)? = null
    private var pendingError: ((Throwable) -> Unit)? = null

    /**
     * Binds CameraX's [Preview] and [VideoCapture] use cases to
     * [lifecycleOwner], targeting [previewView]. Subsequent calls
     * rebind cleanly — used when the user flips the camera.
     *
     * Caller must hold [Manifest.permission.CAMERA]; this is enforced
     * by the @RequiresPermission contract and by a defensive check
     * because runtime denial would otherwise surface as a security
     * exception inside CameraX rather than a clear domain error.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int = mutableState.value.lensFacing,
    ) {
        if (!hasCameraPermission()) {
            mutableState.value = mutableState.value.copy(
                lastError = SecurityException("CAMERA permission not granted"),
            )
            return
        }

        val provider = obtainCameraProvider()
        cameraProvider = provider

        // Quality selector falls back through HD -> SD if the phone does
        // not advertise FHD; this keeps recording working on older
        // devices without an extra check from the caller.
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD)
                )
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)

        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            videoCapture = capture
            mutableState.value = mutableState.value.copy(
                isInitialised = true,
                lensFacing = lensFacing,
                lastError = null,
            )
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                isInitialised = false,
                lastError = error,
            )
        }
    }

    /**
     * Toggles the lens between front and back. No-op while a recording
     * is in progress because CameraX cannot rebind use cases mid-record
     * without dropping frames.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun flipCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (mutableState.value.isRecording) return
        val newFacing = when (mutableState.value.lensFacing) {
            CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }
        bindToLifecycle(lifecycleOwner, previewView, newFacing)
    }

    /**
     * Begins a recording into [destinationFile]. The returned coroutine
     * suspends until the recording finishes successfully (`stopRecording`
     * was called and the muxer flushed) or fails.
     *
     * Audio capture is enabled when the caller holds RECORD_AUDIO; the
     * pipeline does not need audio for analysis but losing it would
     * surprise users who just want to keep the recorded video.
     */
    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO])
    suspend fun startRecording(destinationFile: File): VideoCaptureResult =
        suspendCancellableCoroutine { continuation ->
            val capture = videoCapture
            if (capture == null) {
                continuation.resumeWithException(
                    IllegalStateException("Camera not bound; call bindToLifecycle() first."),
                )
                return@suspendCancellableCoroutine
            }
            if (mutableState.value.isRecording) {
                continuation.resumeWithException(
                    IllegalStateException("A recording is already in progress."),
                )
                return@suspendCancellableCoroutine
            }

            val outputOptions = FileOutputOptions.Builder(destinationFile).build()
            val executor = ContextCompat.getMainExecutor(context)

            val pendingRecording = capture.output
                .prepareRecording(context, outputOptions)
                .apply {
                    if (hasAudioPermission()) {
                        @Suppress("MissingPermission") // checked above
                        withAudioEnabled()
                    }
                }

            pendingResult = { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            pendingError = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            recordingStartNanos = System.nanoTime()
            activeRecording = pendingRecording.start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        mutableState.value = mutableState.value.copy(
                            isRecording = true,
                            elapsedNanos = 0L,
                            lastError = null,
                        )
                    }
                    is VideoRecordEvent.Status -> {
                        // CameraX reports per-frame stats here; we use it
                        // to drive the elapsed-time readout in the UI.
                        mutableState.value = mutableState.value.copy(
                            elapsedNanos = event.recordingStats.recordedDurationNanos,
                        )
                    }
                    is VideoRecordEvent.Finalize -> handleFinalize(
                        event = event,
                        destinationFile = destinationFile,
                    )
                }
            }

            continuation.invokeOnCancellation {
                runCatching { activeRecording?.stop() }
                activeRecording = null
            }
        }

    /**
     * Stops the in-flight recording. Safe to call when nothing is
     * recording (no-op). The actual completion event is delivered
     * through the coroutine started in [startRecording].
     */
    fun stopRecording() {
        val recording = activeRecording ?: return
        recording.stop()
        activeRecording = null
    }

    private fun handleFinalize(
        event: VideoRecordEvent.Finalize,
        destinationFile: File,
    ) {
        val durationNanos = event.recordingStats.recordedDurationNanos
        mutableState.value = mutableState.value.copy(
            isRecording = false,
            elapsedNanos = 0L,
        )
        if (event.hasError()) {
            // CameraX considers some "soft" stops (eg. user pressed
            // stop) as errored finalisations; treat them as success
            // when the file was written and is non-empty.
            val cause = event.cause
            val producedUsableFile = destinationFile.exists() && destinationFile.length() > 0L
            if (producedUsableFile && event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                deliverSuccess(destinationFile, durationNanos)
            } else {
                Log.w(TAG, "Recording finalize error: ${event.error}", cause)
                val error = cause ?: RuntimeException(
                    "Recording failed with code ${event.error}",
                )
                mutableState.value = mutableState.value.copy(lastError = error)
                pendingError?.invoke(error)
                clearPendingCallbacks()
            }
        } else {
            deliverSuccess(destinationFile, durationNanos)
        }
    }

    private fun deliverSuccess(file: File, durationNanos: Long) {
        val durationMillis = durationNanos / 1_000_000L
        pendingResult?.invoke(VideoCaptureResult(file, durationMillis))
        clearPendingCallbacks()
    }

    private fun clearPendingCallbacks() {
        pendingResult = null
        pendingError = null
    }

    private suspend fun obtainCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future: ListenableFuture<ProcessCameraProvider> =
                ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { provider ->
                        if (continuation.isActive) continuation.resume(provider)
                    }
                    .onFailure { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }, ContextCompat.getMainExecutor(context))
        }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VideoCaptureController"
    }
}

/**
 * Allocates a unique destination file for a new recording inside
 * app-private storage. Filenames embed the start timestamp so they sort
 * chronologically when listed.
 *
 * On API 26+ (our minSdk), [Context.getFilesDir] always returns a stable
 * path that survives reboots; we use it directly rather than the cache
 * directory because cleared caches would silently delete recordings
 * that have not been analysed yet.
 */
fun newRecordingFile(context: Context): File {
    val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        java.time.LocalDateTime.now()
            .toString()
            .replace(':', '-')
            .replace('.', '_')
    } else {
        System.currentTimeMillis().toString()
    }
    return File(directory, "BowlTrack_$timestamp.mp4")
}
