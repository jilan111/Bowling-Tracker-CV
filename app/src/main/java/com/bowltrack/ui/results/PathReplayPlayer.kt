package com.bowltrack.ui.results

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bowltrack.data.prefs.PathStyle
import com.bowltrack.python.FallenPinRecord
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import java.io.File

/**
 * Replay player + path overlay.
 *
 * Loops the source video on an `ExoPlayer` surface and paints the
 * smoothed Catmull-Rom path on top as a glowing mint polyline, plus
 * coral circles numbered with each pin's fall order. Coordinates are
 * scaled from the analysis-frame pixel space (the resolution the
 * Python tier processed at) into whatever size the host lays the
 * canvas out in.
 */
@Composable
fun PathReplayPlayer(
    video: File,
    result: VideoAnalysisResult,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
    pathStyle: PathStyle = PathStyle.Line,
    playbackSpeed: Float = 1f,
) {
    val context = LocalContext.current

    val player = remember(video) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    // Track playback speed reactively so toggling slow-motion in
    // Settings re-applies without re-creating the player.
    androidx.compose.runtime.LaunchedEffect(playbackSpeed, player) {
        player.playbackParameters = PlaybackParameters(playbackSpeed.coerceAtLeast(0.05f))
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Prefer the source mp4's intrinsic, rotation-aware dimensions for
    // the player Box's aspect ratio. The analysis-frame size reported
    // by Python is sometimes stale or absent (older sessions) and the
    // 16:9 default landscape-letterboxed portrait clips with white bars.
    val (renderWidth, renderHeight) = remember(video, sourceWidth, sourceHeight) {
        readVideoDimensions(video) ?: (sourceWidth to sourceHeight)
    }
    val aspect = if (renderWidth > 0 && renderHeight > 0) {
        renderWidth.toFloat() / renderHeight
    } else 16f / 9f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // The Canvas overlay shares the Box's bounds and uses its own
        // size for scaling, so as long as the Box matches the video's
        // aspect ratio, the path stays glued to the underlying frame.
        PathOverlay(
            path = result.carPath,
            falls = result.fallenPins,
            sourceWidth = renderWidth,
            sourceHeight = renderHeight,
            pathStyle = pathStyle,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Reads the source mp4's intrinsic dimensions, swapping width/height
 * if the file carries a 90°/270° rotation tag. Returns ``null`` when
 * the metadata cannot be read so callers can fall back to whatever
 * dimensions the analysis stage reported.
 */
private fun readVideoDimensions(video: java.io.File): Pair<Int, Int>? {
    if (!video.exists()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(video.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: return null
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) h to w else w to h
    } catch (_: RuntimeException) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Pure presentation overlay — no player coupling, so the unit-test
 * version of the screen can render this against a snapshot without
 * spinning up Media3.
 *
 * The path is drawn as a glowing line: a wider, lower-alpha mint
 * stroke underneath a tighter, full-alpha mint stroke. Together they
 * approximate a glow without RenderEffect (still off the table at
 * minSdk 26 for in-line content; see GlassCard's notes).
 */
@Composable
fun PathOverlay(
    path: List<Pair<Float, Float>>,
    falls: List<FallenPinRecord>,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
    pathStyle: PathStyle = PathStyle.Line,
) {
    Canvas(modifier = modifier) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return@Canvas
        val scaleX = size.width / sourceWidth
        val scaleY = size.height / sourceHeight

        if (path.size >= 2) {
            when (pathStyle) {
                PathStyle.Line -> {
                    val composePath = Path().apply {
                        val (x0, y0) = path.first()
                        moveTo(x0 * scaleX, y0 * scaleY)
                        for (i in 1 until path.size) {
                            val (x, y) = path[i]
                            lineTo(x * scaleX, y * scaleY)
                        }
                    }
                    drawPath(
                        path = composePath,
                        color = AccentMint.copy(alpha = 0.35f),
                        style = Stroke(width = 12f),
                    )
                    drawPath(
                        path = composePath,
                        color = AccentMint,
                        style = Stroke(width = 4.5f),
                    )
                }
                PathStyle.Dotted -> {
                    // Skip every other sample so adjacent dots do not
                    // run together at typical 12-samples-per-segment
                    // density.
                    for (i in path.indices step 2) {
                        val (x, y) = path[i]
                        drawCircle(
                            color = AccentMint,
                            radius = 4f,
                            center = Offset(x * scaleX, y * scaleY),
                        )
                    }
                }
                PathStyle.FadingTail -> {
                    // The most recent ~30% of the path is brightest;
                    // alpha fades to 0 at the head. We render as a
                    // sequence of short segments so each can have its
                    // own alpha without splitting the Compose path.
                    val total = path.size
                    val tailWindow = (total * 0.3f).toInt().coerceAtLeast(2)
                    for (i in 1 until total) {
                        val tailIndex = total - 1 - i
                        val alpha = (1f - tailIndex.toFloat() / tailWindow).coerceIn(0f, 1f)
                        if (alpha <= 0f) continue
                        val (x0, y0) = path[i - 1]
                        val (x1, y1) = path[i]
                        drawLine(
                            color = AccentMint.copy(alpha = alpha),
                            start = Offset(x0 * scaleX, y0 * scaleY),
                            end = Offset(x1 * scaleX, y1 * scaleY),
                            strokeWidth = 4.5f,
                        )
                    }
                }
            }
        }

        // Numbered fall markers. We do not have per-pin coordinates
        // in the M9 result yet (the orchestrator does not store the
        // bbox at the moment of fall), so for now we plot the markers
        // in a horizontal strip across the bottom of the canvas in
        // fall order. The full positional render lands in M11 once
        // the Room schema carries the bbox-at-fall column.
        if (falls.isNotEmpty()) {
            val markerY = size.height - 24f
            val pad = 24f
            val span = (size.width - 2 * pad).coerceAtLeast(1f)
            falls.forEachIndexed { index, fall ->
                val cx = pad + span * (index + 0.5f) / falls.size
                drawCircle(
                    color = AccentCoral,
                    center = Offset(cx, markerY),
                    radius = 12f,
                )
                drawCircle(
                    color = Color.White,
                    center = Offset(cx, markerY),
                    radius = 12f,
                    style = Stroke(width = 1.5f),
                )
                // Order label drawn through the native canvas so we
                // can use Android's text rendering without pulling in
                // the Compose text-measurer dependency.
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 14f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    canvas.nativeCanvas.drawText(
                        fall.order.toString(),
                        cx,
                        markerY + 5f,
                        paint,
                    )
                }
            }
        }
    }
}

/** Helper to keep `nativeCanvas` access inside a single block. */
private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvas(
    block: (androidx.compose.ui.graphics.Canvas) -> Unit,
) {
    block(drawContext.canvas)
}
