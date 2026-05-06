package com.bowltrack.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bowltrack.python.AnalysisSnapshot
import com.bowltrack.ui.theme.AccentCoral
import com.bowltrack.ui.theme.AccentMint

/**
 * Compose overlay that renders one [AnalysisSnapshot] over a video
 * surface. Pure presentation — every coordinate is scaled from the
 * analysis-frame pixel space to whatever size the host lays the
 * Canvas out at.
 *
 * Visual contract from the brief:
 *   - Mint stroke for the car centroid.
 *   - White (`onBackground`) outline for standing pins.
 *   - Coral fill for fallen pins.
 */
@Composable
fun DetectionOverlay(
    snapshot: AnalysisSnapshot?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (snapshot == null) return@Canvas
        if (snapshot.width <= 0 || snapshot.height <= 0) return@Canvas

        val scaleX = size.width / snapshot.width
        val scaleY = size.height / snapshot.height

        snapshot.pins.forEach { pin ->
            val topLeft = Offset(pin.x * scaleX, pin.y * scaleY)
            val rect = Size(pin.w * scaleX, pin.h * scaleY)
            if (pin.fallen) {
                // Filled coral rectangle so the fallen state pops.
                drawRect(
                    color = AccentCoral.copy(alpha = 0.45f),
                    topLeft = topLeft,
                    size = rect,
                )
                drawRect(
                    color = AccentCoral,
                    topLeft = topLeft,
                    size = rect,
                    style = Stroke(width = 2.5f),
                )
            } else {
                drawRect(
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    topLeft = topLeft,
                    size = rect,
                    style = Stroke(width = 2f),
                )
            }
        }

        snapshot.car?.let { (cx, cy) ->
            val center = Offset(cx * scaleX, cy * scaleY)
            // Outer halo + inner solid dot reads cleanly even on busy
            // frames where the pin boxes might cluster around the car.
            drawCircle(
                color = AccentMint.copy(alpha = 0.35f),
                center = center,
                radius = 16f,
            )
            drawCircle(
                color = AccentMint,
                center = center,
                radius = 6f,
            )
        }
    }
}
