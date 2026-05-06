package com.bowltrack.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bowltrack.BowlTrackApplication
import com.bowltrack.data.repository.toAnalysisResult
import com.bowltrack.python.VideoAnalysisResult
import com.bowltrack.ui.components.PrimaryButton
import com.bowltrack.ui.results.ResultsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only saved-session view.
 *
 * Loads the requested session from the repository on mount, then
 * mounts [ResultsScreen]'s read-only overload with the reconstituted
 * [VideoAnalysisResult]. Keeping the heavy lifting inside
 * [ResultsScreen] means we have one rendering path for live + saved
 * runs, and Milestone 12 polish lands in both at once.
 *
 * Source video is loaded from the absolute path persisted alongside
 * the session. If the file no longer exists we render a soft error
 * card with a way back to History — saved runs can outlive their
 * source video if the user manually clears app storage.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onNewRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as BowlTrackApplication).sessionRepository
    }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<Pair<File, VideoAnalysisResult>?>(null) }

    LaunchedEffect(sessionId) {
        loading = true
        error = null
        val outcome = runCatching {
            withContext(Dispatchers.IO) { repository.loadFull(sessionId) }
        }
        loading = false
        outcome
            .onSuccess { bundle ->
                if (bundle == null) {
                    error = "Saved run is no longer available."
                    return@onSuccess
                }
                val file = File(bundle.session.videoPath)
                if (!file.exists()) {
                    error = "The source video file is missing on disk."
                    return@onSuccess
                }
                loaded = file to bundle.toAnalysisResult()
            }
            .onFailure { exc ->
                error = exc.message ?: exc::class.java.simpleName
            }
    }

    val target = loaded
    when {
        loading -> LoadingCard(modifier = modifier)
        target != null -> ResultsScreen(
            video = target.first,
            result = target.second,
            onBack = onBack,
            onNewRun = onNewRun,
            modifier = modifier,
        )
        else -> ErrorCard(
            message = error ?: "Could not load this run.",
            onBack = onBack,
            modifier = modifier,
        )
    }

}

@Composable
private fun LoadingCard(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pulling the saved run from local storage.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Cannot open this run",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = "Back to History", onClick = onBack)
    }
}

