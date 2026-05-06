package com.bowltrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BackgroundSecondary
import com.bowltrack.ui.theme.BackgroundTertiary
import com.bowltrack.ui.theme.LocalTypographyExtras
import com.bowltrack.util.HapticHelper

/**
 * Top-level Home destination, now backed by real persisted runs.
 *
 * @param onSessionClick Tapping a thumbnail in the recent runs row.
 *                       Routes to the read-only session detail.
 * @param recentSessions Sessions to render. Caller (the navigation
 *                       host) collects from the repository and passes
 *                       in. Empty list shows the friendly empty
 *                       state, matching the brief.
 */
@Composable
fun HomeScreen(
    onRecordClick: () -> Unit,
    onPickClick: () -> Unit,
    onSessionClick: (BowlingSession) -> Unit,
    recentSessions: List<BowlingSession>,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val recordWithHaptic = {
        HapticHelper.confirm(view); onRecordClick()
    }
    val pickWithHaptic = {
        HapticHelper.confirm(view); onPickClick()
    }
    val sessionTapWithHaptic: (BowlingSession) -> Unit = { session ->
        HapticHelper.light(view); onSessionClick(session)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ActionTile(
                title = "Record",
                subtitle = "New run",
                icon = Icons.Outlined.Videocam,
                onClick = recordWithHaptic,
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                title = "Gallery",
                subtitle = "Pick a video",
                icon = Icons.Outlined.Folder,
                onClick = pickWithHaptic,
                modifier = Modifier.weight(1f),
            )
        }

        SectionTitle(text = "Recent runs")

        if (recentSessions.isEmpty()) {
            EmptyRecentRuns()
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 4.dp),
            ) {
                items(items = recentSessions, key = { it.id }) { session ->
                    SessionThumbnail(
                        session = session,
                        onClick = { sessionTapWithHaptic(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "BowlTrack",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Track. Knock. Analyze.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .height(140.dp)
            .clickable(onClick = onClick),
        contentPadding = 16.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentMint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentMint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionThumbnail(
    session: BowlingSession,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .width(176.dp)
            .clickable(onClick = onClick),
        contentPadding = 12.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(BackgroundTertiary, BackgroundSecondary)
                        )
                    ),
                contentAlignment = Alignment.TopEnd,
            ) {
                ScoreBadge(label = session.scoreLabel)
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
private fun ScoreBadge(label: String) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(AccentMint)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = LocalTypographyExtras.current.monoMedium,
            color = MaterialTheme.colorScheme.background,
        )
    }
}

@Composable
private fun EmptyRecentRuns() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Your first run is one tap away.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Saved sessions appear here once you finish your first run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
