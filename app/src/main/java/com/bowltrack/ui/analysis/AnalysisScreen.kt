package com.bowltrack.ui.analysis

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.ui.components.AnimatedScoreFraction
import com.bowltrack.ui.components.CircularProgress
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.util.HapticHelper
import java.io.File

/**
 * Live analysis screen.
 *
 * Renders the source video on an ExoPlayer surface, overlays the live
 * detection state via [DetectionOverlay], and surfaces a custom
 * progress ring + animated score label. The Python orchestrator drives
 * the overlay through a `MutableStateFlow<VideoAnalysisProgress>` that
 * the [AnalysisViewModel] manages.
 *
 * @param video Source video on disk — produced by either the M4
 *              recorder or the M4 picker.
 * @param onClose Tapped on the close icon.
 * @param onComplete Fired once analysis finishes successfully. The
 *                   callback receives the source video alongside the
 *                   aggregate result so the caller can route to the
 *                   M10 results screen.
 */
@Composable
fun AnalysisScreen(
    video: File,
    onClose: () -> Unit,
    onComplete: (File, VideoAnalysisResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val viewModel = remember(video) { AnalysisViewModel(context.applicationContext) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(video) {
        viewModel.start(video)
    }

    // Bubble the result up exactly once after analysis completes so
    // the navigation graph can route to the results screen.
    LaunchedEffect(state.result) {
        val finished = state.result
        if (finished != null) {
            HapticHelper.confirm(view)
            onComplete(video, finished)
        }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancel() }
    }

    val player = remember(video) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Derived values used by the overlays. Computing them inline in
    // the composable keeps the snapshot read-only.
    val totalPins = state.snapshot?.pinCount ?: 0
    val fallenPins = state.snapshot?.fallenCount ?: 0
    val progressFraction: Float? = remember(state.progress.frameIndex, state.durationSeconds) {
        val ts = state.snapshot?.timestampSeconds?.toDouble() ?: 0.0
        val duration = state.durationSeconds
        if (duration <= 0.0) null else (ts / duration).toFloat().coerceIn(0f, 1f)
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ---- Video surface + overlay ---------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (state.sourceWidth > 0 && state.sourceHeight > 0) {
                    state.sourceWidth.toFloat() / state.sourceHeight
                } else 16f / 9f)
                .align(Alignment.TopCenter)
                .systemBarsPadding(),
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
            DetectionOverlay(
                snapshot = state.snapshot,
                modifier = Modifier.fillMaxSize(),
            )

            // Top-right progress ring sits inside the video frame so
            // it stays visually anchored to the action.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                CircularProgress(
                    size = 56.dp,
                    progress = if (state.isRunning) progressFraction else 1f,
                    showPercentLabel = true,
                )
            }

            // Top-left close button.
            val closeInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = closeInteraction,
                        indication = null,
                    ) {
                        HapticHelper.light(view)
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cancel analysis",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // ---- Bottom score + status card ------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PINS DOWN",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AnimatedScoreFraction(
                        numerator = fallenPins,
                        denominator = totalPins,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusPill(
                            label = if (state.isRunning) "Analysing" else "Done",
                            color = if (state.isRunning) AccentMint else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "frame #${state.progress.frameIndex.coerceAtLeast(0)}",
                            style = LocalTypographyExtras.current.mono,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        state.snapshot?.carSource?.let { source ->
                            Text(
                                text = "car: $source",
                                style = LocalTypographyExtras.current.mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.errorMessage?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCoral,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

