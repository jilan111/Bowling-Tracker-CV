package com.bowltrack.python

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the lifecycle of the bundled YOLOv8n TFLite model on disk.
 *
 * The model ships inside the APK as
 * ``app/src/main/assets/ml/yolov8n_float16.tflite`` (Milestone 1
 * scaffolded the directory; the actual weights are copied in by the
 * developer per the README). Python's TFLite interpreter wants a real
 * filesystem path, so on first launch we copy the asset into
 * ``filesDir/ml/`` and hand that path to Python.
 *
 * Subsequent launches skip the copy as long as the destination already
 * exists and is non-empty. Bumping the model file requires deleting
 * the cached copy or bumping the destination filename in this object.
 */
object YoloModelAsset {

    /** Asset path inside the APK; relative to ``app/src/main/assets``. */
    private const val ASSET_PATH = "ml/yolov8n_float16.tflite"

    /** Filename used in app-private storage. */
    private const val DESTINATION_NAME = "yolov8n_float16.tflite"

    private const val TAG = "YoloModelAsset"

    /**
     * Returns the on-disk path the Python tier should open, or
     * ``null`` when the model is not bundled yet (typical until the
     * developer drops the .tflite into ``assets/ml/``).
     *
     * Safe to call from any thread; the actual extraction is wrapped
     * in a try/catch so a missing or corrupt asset never crashes the
     * app — the YOLO tier simply degrades to "model unavailable".
     */
    fun ensureExtracted(context: Context): String? {
        val destination = File(File(context.filesDir, "ml").apply { mkdirs() }, DESTINATION_NAME)
        if (destination.exists() && destination.length() > 0L) {
            return destination.absolutePath
        }
        return runCatching {
            context.assets.open(ASSET_PATH).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted YOLO model to ${destination.absolutePath} (${destination.length()} bytes)")
            destination.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "YOLO model not bundled or extraction failed: ${error.message}")
            // Make sure a half-written file does not poison subsequent runs.
            destination.delete()
        }.getOrNull()
    }
}
