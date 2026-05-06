package com.bowltrack.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bowltrack.python.CombinedFrameResult
import com.bowltrack.python.PythonBridge
import com.bowltrack.python.YoloModelAsset
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.components.SecondaryButton
import com.bowltrack.ui.theme.AccentBlue
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.video.VideoFrame
import com.bowltrack.video.VideoFrameExtractor
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * Diagnostic for the Milestone 7 hybrid pipeline. Decodes the first
 * frame of the most recent recording or imported video, runs both the
 * classical detector and (optionally) the YOLO tier, and overlays the
 * results in two colours so they can be compared at a glance:
 *
 *  - **Mint** boxes — classical-CV detections.
 *  - **Blue** boxes — YOLO detections (only when the YOLO toggle is on
 *    and the model is bundled).
 *  - Mint circle — classical-CV car centroid (if found).
 *
 * The toggle defaults to on; when no model file is present in
 * app-private storage the screen reports the YOLO status string from
 * Python and the overlay simply omits the blue boxes.
 */
@Composable
fun DetectionDebugScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var probeText by remember { mutableStateOf("") }
    var lastFrame by remember { mutableStateOf<VideoFrame?>(null) }
    var result by remember { mutableStateOf<CombinedFrameResult?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var useYolo by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Detection (M7)",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Mint = classical CV. Blue = YOLOv8n TFLite. Toggle YOLO off to compare classical-only behaviour.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use YOLO",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Runs YOLOv8n TFLite alongside the classical detector.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useYolo,
                        onCheckedChange = { useYolo = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = AccentBlue,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = LocalTypographyExtras.current.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (probeText.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = probeText,
                        style = LocalTypographyExtras.current.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val current = result
                if (current != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat(
                            label = "Classical",
                            value = "${current.classical.pinCount} pins • ${"%.1f".format(current.timing.classicalMs)}ms",
                            color = AccentMint,
                        )
                        Stat(
                            label = "YOLO",
                            value = current.yolo?.let {
                                "${it.detections.size} det • ${"%.1f".format(current.timing.yoloMs)}ms"
                            } ?: current.yoloStatus,
                            color = AccentBlue,
                        )
                        Stat(
                            label = "Total",
                            value = "${"%.1f".format(current.timing.totalMs)}ms",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (current.yolo != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "YOLO breakdown — pre ${"%.1f".format(current.yolo.stats.preprocessMs)}ms / model ${"%.1f".format(current.yolo.stats.modelMs)}ms / post ${"%.1f".format(current.yolo.stats.postprocessMs)}ms",
                            style = LocalTypographyExtras.current.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val frame = lastFrame
            val resultSnapshot = result
            if (frame == null) {
                Text(
                    text = "Run the pipeline to see overlays for both detectors.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                androidx.compose.foundation.Image(
                    bitmap = frame.bitmap.asImageBitmap(),
                    contentDescription = "Decoded frame",
                    modifier = Modifier.fillMaxSize(),
                )
                if (resultSnapshot != null) {
                    DetectionsOverlay(
                        result = resultSnapshot,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        PrimaryButton(
            text = "Run pipeline on first frame",
            onClick = {
                val source = findMostRecentVideo(context.filesDir)
                if (source == null) {
                    status = "No video found in app-private storage. Record or import one first."
                    return@PrimaryButton
                }
                status = "Decoding ${source.name}..."
                lastFrame = null
                result = null
                elapsedMs = 0L
                scope.launch {
                    runCatching {
                        val extractor = VideoFrameExtractor(source)
                        val probe = extractor.probe()
                        probeText = "Source: ${probe.width}x${probe.height} • ${probe.sourceFps} fps • ${"%.1f".format(probe.durationSeconds)} s"
                        val first: VideoFrame = extractor.frames().firstOrNull()
                            ?: error("Extractor produced no frames")
                        lastFrame = first
                        status = "Running pipeline (use_yolo = $useYolo)..."
                        val modelPath = if (useYolo) YoloModelAsset.ensureExtracted(context) else null
                        val started = System.currentTimeMillis()
                        val outcome = PythonBridge.analyzeSingleFrameCombined(
                            frame = first,
                            useYolo = useYolo,
                            yoloModelPath = modelPath,
                        )
                        elapsedMs = System.currentTimeMillis() - started
                        result = outcome
                        status = buildString {
                            append("Done. ")
                            append("Classical ${outcome.classical.pinCount} pins")
                            outcome.yolo?.let { append(", YOLO ${it.detections.size} detections") }
                            append(". Status = ${outcome.yoloStatus}.")
                        }
                    }.onFailure { error ->
                        status = "Failed: ${error.message ?: error::class.java.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = "Close",
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = LocalTypographyExtras.current.monoMedium,
            color = color,
        )
    }
}

/**
 * Two-pass overlay so YOLO boxes always sit above classical ones and
 * the line widths read clearly even when both detectors agree on a
 * pin (the boxes very nearly coincide). The Canvas scales bbox
 * coordinates from analysis-frame pixels to the rendered preview's
 * size, so layout changes do not break alignment.
 */
@Composable
private fun DetectionsOverlay(
    result: CombinedFrameResult,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (result.width <= 0 || result.height <= 0) return@Canvas
        val scaleX = size.width / result.width
        val scaleY = size.height / result.height

        // Pass 1: classical detections (mint).
        result.classical.pins.forEach { pin ->
            drawRect(
                color = AccentMint,
                topLeft = Offset(pin.x * scaleX, pin.y * scaleY),
                size = Size(pin.w * scaleX, pin.h * scaleY),
                style = Stroke(width = 2.5f),
            )
        }
        result.classical.car?.let { (cx, cy) ->
            drawCircle(
                color = AccentMint,
                center = Offset(cx * scaleX, cy * scaleY),
                radius = 8f,
                style = Stroke(width = 2.5f),
            )
        }

        // Pass 2: YOLO detections (blue).
        result.yolo?.detections?.forEach { box ->
            val x = box.x1 * scaleX
            val y = box.y1 * scaleY
            val w = (box.x2 - box.x1) * scaleX
            val h = (box.y2 - box.y1) * scaleY
            drawRect(
                color = AccentBlue,
                topLeft = Offset(x, y),
                size = Size(w, h),
                style = Stroke(width = 2.0f),
            )
        }
    }
}

/** See `FrameExtractorPreview.kt` — same logic, kept in lockstep. */
private fun findMostRecentVideo(filesDir: File): File? {
    val candidates = listOf(
        File(filesDir, "recordings"),
        File(filesDir, "imports"),
    ).flatMap { dir ->
        if (dir.isDirectory) dir.listFiles { f -> f.isFile && f.length() > 0L }?.toList().orEmpty()
        else emptyList()
    }
    return candidates.maxByOrNull { it.lastModified() }
}
