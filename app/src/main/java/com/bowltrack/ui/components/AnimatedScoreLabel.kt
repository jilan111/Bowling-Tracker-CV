package com.bowltrack.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bowltrack.ui.theme.AccentMint

/**
 * Number readout where each digit cross-fades and slides when its value
 * changes, modelled after fitness-app scoreboards.
 *
 * The component renders the value as a sequence of "tracks", one per
 * character of the [value]. Each track is its own [AnimatedContent] so
 * stable digits do not animate when only one digit changes (e.g. when
 * 109 -> 110, only the units digit slides). Padding the value to a fixed
 * [width] prevents layout shift when the magnitude grows from one to two
 * digits between updates.
 *
 * @param value The current value to display. Animated via per-digit
 *              [AnimatedContent], so consecutive small updates feel
 *              continuous rather than jumpy.
 * @param modifier Standard layout modifier.
 * @param width Fixed character width — pads the value with leading
 *              spaces so the layout does not jump when the magnitude
 *              grows. `0` means "no padding".
 * @param style Text style applied to each digit. Defaults to the theme's
 *              displayLarge for hero scoreboards.
 * @param color Text color. Defaults to AccentMint to match the brand
 *              accent — pass a different colour for warning/error
 *              variants.
 */
@Composable
fun AnimatedScoreLabel(
    value: Int,
    modifier: Modifier = Modifier,
    width: Int = 0,
    style: TextStyle = MaterialTheme.typography.displayLarge.copy(
        fontWeight = FontWeight.Bold,
    ),
    color: Color = AccentMint,
) {
    val padded = remember(value, width) {
        if (width <= 0) value.toString() else value.toString().padStart(width)
    }

    Row(modifier = modifier) {
        padded.forEachIndexed { index, ch ->
            // The key on AnimatedContent is the character itself; if a
            // digit holds steady from one frame to the next, Compose
            // skips its animation and re-uses the existing slot.
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val direction = if (targetState > initialState) -1 else 1
                    (slideInVertically(tween(220)) { full -> direction * full } +
                        fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(220)) { full -> -direction * full } +
                            fadeOut(tween(220)))
                },
                label = "AnimatedScoreLabelDigit$index",
            ) { renderedChar ->
                Text(
                    text = renderedChar.toString(),
                    style = style,
                    color = color,
                )
            }
        }
    }
}

/**
 * Convenience overload for "x / y" score readouts. Renders the numerator
 * with the digit-flip animation and a static denominator beside it. The
 * separator slash inherits the same style but with reduced alpha so the
 * eye locks onto the numerator first.
 */
@Composable
fun AnimatedScoreFraction(
    numerator: Int,
    denominator: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge.copy(
        fontWeight = FontWeight.Bold,
    ),
    color: Color = AccentMint,
) {
    var debouncedDenominator by remember { mutableStateOf(denominator) }
    // Debouncing the denominator prevents a flash when both values land
    // in the same frame (the analysis pipeline reports total_pins after
    // a brief warm-up).
    LaunchedEffect(denominator) {
        debouncedDenominator = denominator
    }
    Row(modifier = modifier) {
        AnimatedScoreLabel(
            value = numerator,
            style = style,
            color = color,
        )
        Text(
            text = " / $debouncedDenominator",
            style = style.copy(fontSize = (style.fontSize.value * 0.55f).sp),
            color = color.copy(alpha = 0.55f),
        )
    }
}
