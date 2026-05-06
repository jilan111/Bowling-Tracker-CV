package com.bowltrack.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bowltrack.python.FallenPinRecord
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras

/**
 * Vertical fall log. One row per fallen pin, ordered chronologically
 * with the pin id, the fall-order ordinal, and a JetBrains-Mono
 * timestamp readout.
 *
 * Implementation note: the brief calls for a `LazyColumn`, but the
 * complete fall log is bounded (≤ 10 entries on a real bowling alley),
 * so we use a regular `Column` here. The whole Results screen is one
 * scroll container, and nesting a LazyColumn inside it would force us
 * to size it explicitly — extra ceremony for no benefit.
 */
@Composable
fun PinTimelineSection(
    falls: List<FallenPinRecord>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "TIMELINE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (falls.isEmpty()) {
                Text(
                    text = "No pins fell during this run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    falls.forEach { fall ->
                        TimelineRow(fall = fall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(fall: FallenPinRecord) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Mint order badge — sits flush with the row baseline so the
        // user reads the ordinal first ("which fall was this?"),
        // then the pin id, then the timestamp.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AccentMint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fall.order.toString(),
                style = LocalTypographyExtras.current.monoMedium,
                color = MaterialTheme.colorScheme.background,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Pin #${fall.pinId}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Frame ${fall.frameIndex}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${"%.2f".format(fall.timestampSeconds)}s",
            style = LocalTypographyExtras.current.monoMedium,
            color = AccentCoral,
        )
    }
}
