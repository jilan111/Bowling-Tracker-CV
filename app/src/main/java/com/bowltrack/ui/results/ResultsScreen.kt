package com.bowltrack.ui.results

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bowltrack.data.prefs.DetectionPreferences
import com.bowltrack.data.prefs.PathStyle
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.components.SecondaryButton
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.LocalTypographyExtras
import java.io.File

/** How the [ResultsScreen] is being displayed today. */
enum class ResultsDisplayMode {
    /** Live result freshly produced by the Analysis screen. Save is
     *  enabled, and the screen pulls from [ResultsHandoff]. */
    Live,

    /** Read-only render of a previously persisted run. Save is hidden;
     *  caller injects the [VideoAnalysisResult] and source video
     *  through [ResultsScreen]'s overload. */
    Saved,
}

/**
 * Results screen.
 *
 * Renders four stacked sections:
 *   1. Scoreboard hero (animated count-up).
 *   2. Replay player with smoothed mint path + numbered coral
 *      fall markers.
 *   3. Vertical fall timeline.
 *   4. Action row — Save / Share / New run (Save hidden in Saved mode).
 *
 * In Live mode, the video and result are *not* re-fetched here. The
 * Analysis screen deposits them in [ResultsHandoff] before
 * navigating; this composable pulls them out on first composition.
 * If the slot is empty (process death + return-to-task) we render a
 * friendly empty state and offer the user a way back to Home.
 *
 * @param onBack Tap on the back chevron — pops the screen off the
 *               stack.
 * @param onNewRun Tap on the "New run" CTA — caller decides whether
 *                 to route to Home or directly to the recorder.
 * @param onSave Invoked when the user taps "Save to history". Caller
 *               persists; the button flips to "Saved".
 */
@Composable
fun ResultsScreen(
    onBack: () -> Unit,
    onNewRun: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (VideoAnalysisResult) -> Unit = {},
) {
    // Pull the hand-off exactly once and stash it in remember so the
    // result survives recompositions while the screen is alive.
    val handoff = remember { ResultsHandoff.take() }
    if (handoff == null) {
        EmptyResults(modifier = modifier, onBack = onBack)
        return
    }
    val (video, result) = handoff
    ResultsBody(
        video = video,
        result = result,
        displayMode = ResultsDisplayMode.Live,
        onBack = onBack,
        onNewRun = onNewRun,
        onSave = onSave,
        modifier = modifier,
    )
}

/**
 * Read-only entry point: caller supplies the video + result directly
 * (typically loaded from [com.bowltrack.data.repository.SessionRepository]
 * for a saved session).
 */
@Composable
fun ResultsScreen(
    video: File,
    result: VideoAnalysisResult,
    onBack: () -> Unit,
    onNewRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ResultsBody(
        video = video,
        result = result,
        displayMode = ResultsDisplayMode.Saved,
        onBack = onBack,
        onNewRun = onNewRun,
        onSave = { /* read-only; Save is hidden anyway */ },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsBody(
    video: File,
    result: VideoAnalysisResult,
    displayMode: ResultsDisplayMode,
    onBack: () -> Unit,
    onNewRun: () -> Unit,
    onSave: (VideoAnalysisResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { DetectionPreferences(context.applicationContext) }
    val settings by prefs.flow.collectAsState(initial = null)
    val pathStyle = settings?.pathStyle ?: PathStyle.Line
    val playbackSpeed = if (settings?.slowMotionReplay == true) 0.5f else 1f

    var saved by remember { mutableStateOf(displayMode == ResultsDisplayMode.Saved) }

    // The actual source dimensions are read inside PathReplayPlayer
    // via MediaMetadataRetriever; what the analysis stage reports here
    // is only a best-effort hint for the overlay's scale factor when
    // the file metadata is unavailable.
    val sourceWidth = result.analysisWidth.takeIf { it > 0 } ?: 1280
    val sourceHeight = result.analysisHeight.takeIf { it > 0 } ?: 720

    val durationSeconds: Float = result.fallenPins.maxOfOrNull { it.timestampSeconds }
        ?: result.frameCount.toFloat() / 15f // assumed targetFps when no falls

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Run summary",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = video.name,
                            style = LocalTypographyExtras.current.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // -- Scoreboard ---------------------------------------
            ScoreboardSection(
                fallenPins = result.fallenPins.size,
                totalPins = result.totalPins,
                durationSeconds = durationSeconds,
            )

            // -- Replay -------------------------------------------
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "REPLAY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    PathReplayPlayer(
                        video = video,
                        result = result,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        pathStyle = pathStyle,
                        playbackSpeed = playbackSpeed,
                    )
                }
            }

            // -- Timeline -----------------------------------------
            PinTimelineSection(falls = result.fallenPins)

            // -- Diagnostic strip --------------------------------
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "DIAGNOSTICS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DiagnosticRow("YOLO status", result.yoloStatus)
                    DiagnosticRow("YOLO invocations", result.yoloInvocations.toString())
                    DiagnosticRow(
                        "Car sources",
                        "color=${result.carSources.color} yolo=${result.carSources.yolo} lk=${result.carSources.lk} missing=${result.carSources.missing}",
                    )
                    DiagnosticRow(
                        "Pipeline timing",
                        "cls ${"%.0f".format(result.timingTotalMs.classicalMs)}ms / yolo ${"%.0f".format(result.timingTotalMs.yoloMs)}ms",
                    )
                    if (result.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Error: ${result.error}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCoral,
                        )
                    }
                }
            }

            // -- Actions ------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (displayMode == ResultsDisplayMode.Live) {
                    PrimaryButton(
                        text = if (saved) "Saved" else "Save to history",
                        onClick = {
                            if (!saved) {
                                saved = true
                                onSave(result)
                            }
                        },
                        enabled = !saved,
                        modifier = Modifier.weight(1f),
                    )
                }
                SecondaryButton(
                    text = "Share",
                    onClick = {
                        val intent: Intent? = ShareCardRenderer.build(context, result)
                        if (intent != null) {
                            ContextCompat.startActivity(context, intent, null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            SecondaryButton(
                text = "New run",
                onClick = onNewRun,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = LocalTypographyExtras.current.mono,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyResults(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No result available",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The most recent analysis result has expired. Record a new run from Home and the summary will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = "Back to Home", onClick = onBack)
    }
}
