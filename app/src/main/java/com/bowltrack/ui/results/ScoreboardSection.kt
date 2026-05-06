package com.bowltrack.ui.results

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bowltrack.ui.components.AnimatedScoreFraction
import com.bowltrack.ui.components.GlassCard
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.LocalTypographyExtras
import kotlinx.coroutines.delay

/**
 * Hero scoreboard for the Results screen.
 *
 * Drives a count-up effect by holding a local int that animates from
 * 0 to [fallenPins] over ~600 ms, so the user sees the digits flip
 * one by one (the underlying [AnimatedScoreFraction] takes care of
 * the per-digit slide / fade). A subtle mint glow brackets the
 * numbers; a second-row stat block reports duration and pin count.
 */
@Composable
fun ScoreboardSection(
    fallenPins: Int,
    totalPins: Int,
    durationSeconds: Float,
    modifier: Modifier = Modifier,
) {
    var displayed by remember(fallenPins) { mutableIntStateOf(0) }

    // Step the animated value up over time. `LaunchedEffect` keyed on
    // the target value re-runs cleanly if the user navigates between
    // two saved sessions in M11 history.
    LaunchedEffect(fallenPins) {
        if (fallenPins <= 0) {
            displayed = 0
            return@LaunchedEffect
        }
        val stepDelay = (600L / fallenPins).coerceAtLeast(60L)
        for (n in 1..fallenPins) {
            displayed = n
            delay(stepDelay)
        }
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (displayed > 0) 0.55f else 0.0f,
        animationSpec = tween(durationMillis = 700, easing = LinearEasing),
        label = "ScoreboardGlow",
    )

    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 22.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "FINAL SCORE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center) {
                // Soft mint halo behind the digits — drawn as a
                // rounded rectangle with a vertical gradient so it
                // reads as light, not as a solid pill.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(74.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    AccentMint.copy(alpha = glowAlpha * 0.20f),
                                    AccentMint.copy(alpha = 0f),
                                )
                            )
                        ),
                )
                AnimatedScoreFraction(
                    numerator = displayed,
                    denominator = totalPins,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatBlock(label = "Duration", value = "%.1fs".format(durationSeconds))
                StatBlock(label = "Pins detected", value = totalPins.toString())
                StatBlock(label = "Pins down", value = fallenPins.toString())
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = LocalTypographyExtras.current.monoLarge,
            color = AccentMint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
