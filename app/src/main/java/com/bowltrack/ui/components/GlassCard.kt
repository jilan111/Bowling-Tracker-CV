package com.bowltrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BackgroundSecondary
import com.bowltrack.ui.theme.BackgroundTertiary

/**
 * Frosted-glass surface with a 1-px mint-to-coral gradient border.
 *
 * Note on the "blur backdrop" goal in the design brief: Compose 1.6 does
 * not yet expose a portable backdrop-blur API. Applying a RenderEffect
 * blur to the card's own graphicsLayer would also blur its foreground
 * content (text, icons), which is the opposite of what a frosted surface
 * should look like. Implementing real backdrop blur requires either a
 * platform-level Window blur (API 31+, only effective behind the entire
 * window) or capturing the parent into a Picture — both of which add
 * meaningful complexity for marginal visual gain.
 *
 * Instead, we approximate frosted glass with a vertical translucent
 * gradient that lightens toward the top, a 1-px gradient border, and a
 * carefully tuned alpha that lets the dark navy background bleed through
 * by a few percent. The result reads as "glass over instrument panel"
 * without the cost or correctness pitfalls of a synthetic blur.
 *
 * @param modifier Layout modifier; callers control sizing.
 * @param cornerRadius Corner radius of clip and border. Defaults to the
 *                     theme's medium slot (14 dp).
 * @param contentPadding Inner padding applied between the gradient border
 *                       and [content].
 * @param content Slot composable rendered inside the card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    contentPadding: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)

    val borderBrush = Brush.linearGradient(
        listOf(
            AccentMint.copy(alpha = 0.55f),
            AccentCoral.copy(alpha = 0.35f),
        )
    )

    // Top is the higher-luminance tier (BackgroundTertiary), bottom drops
    // to BackgroundSecondary. The 6 % alpha drop between the two creates
    // an "instrument-panel sheen" that suggests reflected light without
    // resorting to an explicit highlight stripe.
    val surfaceBrush = Brush.verticalGradient(
        listOf(
            BackgroundTertiary.copy(alpha = 0.92f),
            BackgroundSecondary.copy(alpha = 0.86f),
        )
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush = surfaceBrush, shape = shape)
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .padding(contentPadding),
    ) { content() }
}

/**
 * Opaque variant used where we need a card without backdrop bleed —
 * notably the share-card snapshot path, where transparency would render
 * the share-sheet thumbnail with a checkerboard background.
 */
@Composable
fun OpaqueGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    contentPadding: Dp = 18.dp,
    fillColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = Brush.linearGradient(
        listOf(
            AccentMint.copy(alpha = 0.55f),
            AccentCoral.copy(alpha = 0.35f),
        )
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, borderBrush, shape)
            .padding(contentPadding),
    ) { content() }
}
