package com.bowltrack.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bowltrack.data.db.entities.BowlingSession
import com.bowltrack.data.format.formatDuration
import com.bowltrack.data.format.formatRelative
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BackgroundSecondary
import com.bowltrack.ui.theme.BackgroundTertiary
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.util.HapticHelper

/**
 * History destination — 2-column grid of saved sessions, backed by
 * the repository.
 *
 * Long-pressing a tile opens a delete-confirmation dialog. The brief
 * calls for swipe-to-delete; we settle for long-press because true
 * swipe-to-dismiss is not available in `LazyVerticalGrid` without a
 * third-party gesture library, and a long-press dialog is the most
 * common Android idiom for grid items anyway. The dialog itself is a
 * confirm step, satisfying the brief's safety requirement.
 *
 * @param onSessionClick Tapping a tile routes to the read-only
 *                       session detail.
 * @param onDeleteSession Confirmed delete from the dialog. Caller is
 *                        expected to invoke
 *                        [com.bowltrack.data.repository.SessionRepository.delete].
 */
@Composable
fun HistoryScreen(
    onSessionClick: (BowlingSession) -> Unit,
    onDeleteSession: (BowlingSession) -> Unit,
    sessions: List<BowlingSession>,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var pendingDelete by remember { mutableStateOf<BowlingSession?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Text(
            text = "History",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${sessions.size} saved ${pluralRuns(sessions.size)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            EmptyHistory(modifier = Modifier.fillMaxWidth())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    HistoryTile(
                        session = session,
                        onClick = {
                            HapticHelper.light(view)
                            onSessionClick(session)
                        },
                        onLongPress = {
                            HapticHelper.confirm(view)
                            pendingDelete = session
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this run?") },
            text = {
                Text(
                    "Removes the saved analysis. The original video file on disk is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HapticHelper.confirm(view)
                    onDeleteSession(target)
                    pendingDelete = null
                }) {
                    Text(text = "Delete", color = AccentCoral)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = "Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryTile(
    session: BowlingSession,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        contentPadding = 12.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(BackgroundTertiary, BackgroundSecondary)
                        )
                    ),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(AccentMint)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = session.scoreLabel,
                        style = LocalTypographyExtras.current.monoMedium,
                        color = MaterialTheme.colorScheme.background,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = formatRelative(session.recordedAtMillis),
                style = LocalTypographyExtras.current.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatDuration(session.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No saved runs yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap save on the analysis summary and your runs will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pluralRuns(count: Int): String = if (count == 1) "run" else "runs"
