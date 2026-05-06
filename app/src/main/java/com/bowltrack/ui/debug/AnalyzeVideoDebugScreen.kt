package com.bowltrack.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bowltrack.python.PythonBridge
import com.bowltrack.python.VideoAnalysisProgress
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.python.YoloModelAsset
import com.bowltrack.ui.components.CircularProgress
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.components.SecondaryButton
import com.bowltrack.ui.theme.AccentBlue
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug screen that drives the full Milestone 8 pipeline end-to-end
 * against the most recent recording or imported video.
 *
 * Renders three blocks:
 *   1. **Status / progress** — live frame index + indeterminate ring
 *      while Python is processing (we cannot compute a reliable
 *      fraction without a frame-count probe ahead of time).
 *   2. **Aggregate result** — score, fall log, car-source breakdown,
 *      total timings.
 *   3. **Smoothed car path preview** — first/last sampled coordinates
 *      so we can sanity-check the path tracker output.
 *
 * The screen is part of Milestone 8 only; the polished Analysis +
 * Results screens (Milestones 9-10) supersede it.
 */
@Composable
fun AnalyzeVideoDebugScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val progressFlow = remember { MutableStateFlow(VideoAnalysisProgress()) }
    val progress by progressFlow.collectAsState()

    var status by remember { mutableStateOf("Idle") }
    var useYolo by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf<VideoAnalysisResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Full pipeline (M8)",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Streams every frame of the most recent video through the hybrid orchestrator (classical CV every frame, YOLO every 5th, Lucas-Kanade fallback for the car).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // -- Toggle ----------------------------------------------------
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use YOLO",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Runs YOLOv8n every 5th frame as a verification + recovery step.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useYolo,
                    onCheckedChange = { if (!isRunning) useYolo = it },
                    enabled = !isRunning,
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

        // -- Progress --------------------------------------------------
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgress(
                    size = 56.dp,
                    progress = if (isRunning) null else 1f.takeIf { result != null },
                )
                Spacer(Modifier.padding(end = 16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = status,
                        style = LocalTypographyExtras.current.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isRunning) {
                        Text(
                            text = "frame #${progress.frameIndex.coerceAtLeast(0)}",
                            style = LocalTypographyExtras.current.mono,
                            color = AccentMint,
                        )
                    }
                }
            }
        }

        // -- Result summary -------------------------------------------
        val current = result
        if (current != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow(label = "Score", value = "${current.fallenPins.size} / ${current.totalPins}")
                    KeyValueRow(label = "Frames analysed", value = current.frameCount.toString())
                    KeyValueRow(label = "Wall-clock", value = "${elapsedMs}ms")
                    KeyValueRow(label = "YOLO status", value = current.yoloStatus)
                    KeyValueRow(label = "YOLO invocations", value = current.yoloInvocations.toString())
                    KeyValueRow(
                        label = "Timing (CPU)",
                        value = "cls ${"%.0f".format(current.timingTotalMs.classicalMs)}ms / yolo ${"%.0f".format(current.timingTotalMs.yoloMs)}ms",
                    )
                    KeyValueRow(
                        label = "Car sources",
                        value = "color=${current.carSources.color} yolo=${current.carSources.yolo} lk=${current.carSources.lk} missing=${current.carSources.missing}",
                    )
                    if (current.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Error: ${current.error}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (current.fallenPins.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = "Fall log",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        current.fallenPins.forEach { event ->
                            Text(
                                text = "#${event.order} — pin ${event.pinId} @ ${"%.2f".format(event.timestampSeconds)}s (frame ${event.frameIndex})",
                                style = LocalTypographyExtras.current.mono,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (current.carPath.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = "Car path (smoothed)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        val first = current.carPath.first()
                        val last = current.carPath.last()
                        Text(
                            text = "${current.carPath.size} samples • start=(${"%.0f".format(first.first)}, ${"%.0f".format(first.second)}) end=(${"%.0f".format(last.first)}, ${"%.0f".format(last.second)})",
                            style = LocalTypographyExtras.current.mono,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // -- CTAs -----------------------------------------------------
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = if (isRunning) "Running..." else "Run analyze_video on latest video",
            onClick = {
                if (isRunning) return@PrimaryButton
                val source = findMostRecentVideo(context.filesDir)
                if (source == null) {
                    status = "No video found in app-private storage. Record or import one first."
                    return@PrimaryButton
                }
                status = "Decoding ${source.name}..."
                result = null
                progressFlow.value = VideoAnalysisProgress()
                isRunning = true
                scope.launch {
                    val started = System.currentTimeMillis()
                    runCatching {
                        val modelPath = if (useYolo) YoloModelAsset.ensureExtracted(context) else null
                        PythonBridge.analyzeVideo(
                            video = source,
                            useYolo = useYolo,
                            yoloModelPath = modelPath,
                            progress = progressFlow,
                        )
                    }.onSuccess { outcome ->
                        elapsedMs = System.currentTimeMillis() - started
                        result = outcome
                        status = "Done. ${outcome.frameCount} frames analysed."
                    }.onFailure { error ->
                        elapsedMs = System.currentTimeMillis() - started
                        status = "Failed: ${error.message ?: error::class.java.simpleName}"
                    }
                    isRunning = false
                    progressFlow.value = progressFlow.value.copy(finished = true)
                }
            },
            enabled = !isRunning,
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
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = LocalTypographyExtras.current.monoMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Same heuristic the other debug screens use. */
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
