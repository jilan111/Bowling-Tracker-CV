package com.bowltrack.ui.debug

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.components.SecondaryButton
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.video.VideoFrame
import com.bowltrack.video.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diagnostic screen that runs [VideoFrameExtractor] over the most
 * recent recording or imported video and prints summary stats.
 *
 * Renders the latest decoded bitmap so we can eyeball orientation and
 * colour conversion correctness, plus a frame counter and decode rate
 * so we can confirm the decimation is hitting the requested target FPS.
 *
 * The screen is part of Milestone 5 only; it will be removed once the
 * Milestone 9 analysis screen is wired up to drive the extractor in
 * production.
 */
@Composable
fun FrameExtractorPreview(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Idle") }
    var probeText by remember { mutableStateOf("") }
    var lastFrame by remember { mutableStateOf<VideoFrame?>(null) }
    var frameCount by remember { mutableStateOf(0) }
    var firstFrameMicros by remember { mutableLongStateOf(0L) }
    var lastFrameMicros by remember { mutableLongStateOf(0L) }
    var elapsedDecodeMs by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Frame extractor",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Decodes the most recent recording or imported video, downscaled to 720 px wide and decimated to 15 fps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatColumn(label = "Frames", value = frameCount.toString())
                    StatColumn(
                        label = "Decode",
                        value = if (elapsedDecodeMs == 0L) "—" else "${elapsedDecodeMs}ms",
                    )
                    StatColumn(
                        label = "Output FPS",
                        value = computeFps(frameCount, firstFrameMicros, lastFrameMicros),
                    )
                }
            }
        }

        // Latest frame thumbnail so we can sanity-check colour and
        // orientation. Aspect ratio fixed to 16:9 — the extractor may
        // emit a different shape, but a fixed frame keeps the layout
        // stable while frames stream in.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val frame = lastFrame
            if (frame == null) {
                Text(
                    text = "Frames will appear here once decoding starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Image(
                    bitmap = frame.bitmap.asImageBitmap(),
                    contentDescription = "Decoded frame ${frame.index}",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        PrimaryButton(
            text = "Run extractor on latest video",
            onClick = {
                val source = findMostRecentVideo(context.filesDir)
                if (source == null) {
                    status = "No video found in app-private storage. Record or import one first."
                    return@PrimaryButton
                }
                status = "Decoding ${source.name}..."
                lastFrame = null
                frameCount = 0
                firstFrameMicros = 0L
                lastFrameMicros = 0L
                elapsedDecodeMs = 0L
                scope.launch {
                    val startMillis = System.currentTimeMillis()
                    runCatching {
                        val extractor = VideoFrameExtractor(source)
                        val probe = extractor.probe()
                        probeText = "Source: ${probe.width}x${probe.height} • ${probe.sourceFps} fps • ${"%.1f".format(probe.durationSeconds)} s • rot=${probe.rotation}°"
                        withContext(Dispatchers.Default) {
                            extractor.frames().collectLatest { frame ->
                                lastFrame = frame
                                frameCount = frame.index + 1
                                if (firstFrameMicros == 0L) firstFrameMicros = frame.timestampMicros
                                lastFrameMicros = frame.timestampMicros
                            }
                        }
                    }.onSuccess {
                        elapsedDecodeMs = System.currentTimeMillis() - startMillis
                        status = "Done. Emitted $frameCount frames in ${elapsedDecodeMs}ms."
                    }.onFailure { error ->
                        elapsedDecodeMs = System.currentTimeMillis() - startMillis
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
private fun StatColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = LocalTypographyExtras.current.monoMedium,
            color = AccentMint,
        )
    }
}

/**
 * Searches `filesDir/recordings/` and `filesDir/imports/` for the most
 * recently modified video file, returning `null` if neither has any
 * content. Used by the diagnostic to skip an explicit file picker.
 */
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

private fun computeFps(frameCount: Int, firstMicros: Long, lastMicros: Long): String {
    if (frameCount < 2 || lastMicros <= firstMicros) return "—"
    val seconds = (lastMicros - firstMicros) / 1_000_000.0
    if (seconds <= 0.0) return "—"
    return "%.1f".format((frameCount - 1) / seconds)
}

