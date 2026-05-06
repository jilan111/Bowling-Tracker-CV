package com.bowltrack.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint
import com.bowltrack.ui.theme.BorderSubtle
import com.bowltrack.ui.theme.LocalTypographyExtras
import kotlin.math.min

/**
 * Custom indeterminate / determinate progress ring drawn on a [Canvas].
 *
 * The ring has two visual layers:
 *   1. A faint background track (full circle, [BorderSubtle]) so the user
 *      sees the bounding shape even at 0 % progress.
 *   2. A foreground arc filled with a sweep-gradient that rotates over
 *      time. The rotation is what gives the ring its "instrument-panel"
 *      character — a static arc would look too much like Material's
 *      default indicator.
 *
 * For determinate mode, [progress] is clamped to `0f..1f` and animated
 * with a spring so jumpy callback values do not jitter the arc; for
 * indeterminate mode (i.e. [progress] == null), the arc length is fixed
 * at 0.27 (≈97°) and the rotation runs on an infinite transition.
 *
 * @param modifier Standard layout modifier.
 * @param size Diameter of the outer ring. The arc stroke width scales
 *             from this so the ring stays visually balanced at any size.
 * @param progress `null` for indeterminate, `0f..1f` for determinate.
 *                 Values outside the range are coerced.
 * @param showPercentLabel If true and [progress] is non-null, render the
 *                         percentage centred inside the ring using the
 *                         monospaced numeric style.
 */
@Composable
fun CircularProgress(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    progress: Float? = null,
    showPercentLabel: Boolean = false,
) {
    // Spring on incoming progress: callback values from the Python
    // pipeline are not perfectly monotonic (they reflect frame batches
    // landing at variable cadence), and a spring smooths that out.
    val animatedProgress by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "CircularProgressArcLength",
    )

    // Constant rotation drives the "alive" feel. Two rotations are
    // tracked separately because they have very different speeds: a
    // slow gradient rotation (3.6 s) and, in indeterminate mode, a
    // faster arc-position rotation (1.4 s).
    val transition = rememberInfiniteTransition(label = "CircularProgressRot")
    val gradientRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "CircularProgressGradientRotation",
    )
    val indeterminateSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "CircularProgressIndeterminateSweep",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val diameter = min(this.size.width, this.size.height)
            // Stroke width scales gently with diameter so 24-dp and 96-dp
            // rings both read correctly. 8 % is the magic ratio we landed
            // on after sketching at three sizes.
            val stroke = diameter * 0.08f
            val arcSize = Size(diameter - stroke, diameter - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)

            // 1. Background track.
            drawArc(
                color = BorderSubtle,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // 2. Foreground arc.
            val sweepDegrees: Float
            val startAngle: Float
            if (progress == null) {
                sweepDegrees = 360f * 0.27f
                startAngle = indeterminateSweep - 90f
            } else {
                sweepDegrees = 360f * animatedProgress
                startAngle = -90f
            }

            // Gradient is a 0..1 sweep cycled by gradientRotation so the
            // colour wheel appears to spin even when sweep is static.
            val gradient = Brush.sweepGradient(
                0f to AccentMint,
                0.5f to AccentCoral,
                1f to AccentMint,
                center = Offset(this.size.width / 2f, this.size.height / 2f),
            )

            // Rotate the entire layer to drive the gradient sweep. This
            // is cheaper than building a new Brush each frame.
            rotate(degrees = gradientRotation) {
                drawArc(
                    brush = gradient,
                    startAngle = startAngle,
                    sweepAngle = sweepDegrees,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        if (showPercentLabel && progress != null) {
            val pct = (animatedProgress * 100f).toInt().coerceIn(0, 100)
            Text(
                text = "$pct%",
                style = LocalTypographyExtras.current.monoMedium.copy(
                    fontSize = (size.value * 0.22f).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

