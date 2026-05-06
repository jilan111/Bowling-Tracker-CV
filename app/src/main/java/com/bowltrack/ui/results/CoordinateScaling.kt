package com.bowltrack.ui.results

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Maps a detection expressed in normalized coordinates (cx, cy, w, h
 * each in [0, 1]) onto the actual pixel rectangle of the view that
 * holds the video.
 *
 * Normalized coordinates are convenient because they are independent
 * of how the video has been scaled into its container — a detection
 * stored at (0.5, 0.5, 0.2, 0.4) will land at the centre of the
 * preview regardless of the layout's current size.
 *
 * Both the path overlay and the per-pin markers should use this
 * helper so that resizing, rotating the device, or showing the
 * preview at a different resolution never desynchronises the boxes
 * from the underlying frame.
 */
fun normalizedToViewRect(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    viewSize: Size,
): Rect {
    val w = (width.coerceIn(0f, 1f)) * viewSize.width
    val h = (height.coerceIn(0f, 1f)) * viewSize.height
    val cx = (centerX.coerceIn(0f, 1f)) * viewSize.width
    val cy = (centerY.coerceIn(0f, 1f)) * viewSize.height
    return Rect(
        left = cx - w / 2f,
        top = cy - h / 2f,
        right = cx + w / 2f,
        bottom = cy + h / 2f,
    )
}

/** Convenience for a single normalized point. */
fun normalizedToViewOffset(
    x: Float,
    y: Float,
    viewSize: Size,
): Offset = Offset(
    x = x.coerceIn(0f, 1f) * viewSize.width,
    y = y.coerceIn(0f, 1f) * viewSize.height,
)

/**
 * Maps source-frame pixel coordinates onto the view's pixel space.
 * This is what [PathOverlay] uses today; the path is stored in
 * analysis-frame pixels because the orchestrator never had to commit
 * to a normalized form. Use this when you have raw pixels in the
 * source frame but the same scale factor still has to apply.
 */
fun sourcePixelsToViewOffset(
    sourceX: Float,
    sourceY: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    viewSize: Size,
): Offset {
    if (sourceWidth <= 0 || sourceHeight <= 0) return Offset.Zero
    return Offset(
        x = sourceX * viewSize.width / sourceWidth,
        y = sourceY * viewSize.height / sourceHeight,
    )
}
