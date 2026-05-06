package com.bowltrack.video

import android.graphics.Bitmap

/**
 * Single decoded frame surfaced by [VideoFrameExtractor].
 *
 * The class carries both an ARGB [Bitmap] (cheap to draw on a Compose
 * canvas for debugging) and a raw RGBA byte array (cheap to push across
 * the Chaquopy bridge as a NumPy buffer). The Kotlin layer should treat
 * the byte array as the canonical wire format for Python, and only
 * keep the bitmap when it actually plans to render the frame.
 *
 * @property index Zero-based frame index in the output stream. Note
 *                 that this is *not* the source-video frame index when
 *                 the extractor is decimating; see
 *                 [VideoFrameExtractor.targetFps].
 * @property timestampMicros Source-video presentation time in
 *                           microseconds — handy for the fall detector
 *                           which wants seconds-precision timestamps.
 * @property width Width of the (already downscaled) frame in pixels.
 * @property height Height of the (already downscaled) frame in pixels.
 * @property bitmap ARGB_8888 bitmap for inspection. Reused across calls
 *                  in a future revision; today each frame allocates
 *                  fresh storage to keep the API obvious.
 * @property rgba Tightly packed RGBA bytes, row-major, with stride =
 *               `width * 4`. Length always equals `width * height * 4`.
 */
data class VideoFrame(
    val index: Int,
    val timestampMicros: Long,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap,
    val rgba: ByteArray,
) {
    /** Convenience: timestamp in seconds (used by Python timestamps). */
    val timestampSeconds: Double get() = timestampMicros / 1_000_000.0

    // data class equals/hashCode would compare ByteArray references, which
    // is meaningless. Override so equality is index-based — frames are
    // identified by their position in the stream.
    override fun equals(other: Any?): Boolean =
        other is VideoFrame &&
            index == other.index &&
            timestampMicros == other.timestampMicros &&
            width == other.width &&
            height == other.height

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + timestampMicros.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
