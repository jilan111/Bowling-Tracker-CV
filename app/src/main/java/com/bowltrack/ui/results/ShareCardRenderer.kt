package com.bowltrack.ui.results

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.bowltrack.python.VideoAnalysisResult
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a 1080×1350 share card summarising a [VideoAnalysisResult]
 * and writes it to disk, then returns a chooser intent ready to be
 * fired with `startActivity`.
 *
 * Done in pure Android `Canvas` rather than Compose-to-Bitmap because:
 *   - the share card is a fixed offscreen render — there is no
 *     interactive composition to tear down,
 *   - we want predictable pixel sizing for messaging apps' previews,
 *   - capturing a Compose tree to a bitmap requires the experimental
 *     `GraphicsLayer.toImageBitmap()` API and a real composition; the
 *     ceremony is not worth it for a single static image.
 *
 * The card is exposed through `FileProvider` to honour scoped storage;
 * receiving apps see a content:// URI and we never need WRITE_EXTERNAL
 * storage permissions.
 */
object ShareCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    /**
     * Renders the card and returns a share `Intent.createChooser`
     * ready to fire. Returns `null` when the bitmap could not be
     * persisted (typically a disk-full edge case).
     */
    fun build(context: Context, result: VideoAnalysisResult): Intent? {
        val bitmap = renderBitmap(result)
        val outFile = File(context.cacheDir, "share/bowltrack_${result.frameCount}.png")
        outFile.parentFile?.mkdirs()
        return runCatching {
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, outFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { send ->
                Intent.createChooser(send, "Share BowlTrack run")
            }
        }.getOrNull().also {
            // Bitmap is freed on next GC; we cannot recycle here
            // because the caller may still need a reference if the
            // chooser intent is mutated downstream.
        }
    }

    /**
     * Pure render path. Public so unit tests can render to a snapshot
     * and compare without exercising the FileProvider plumbing.
     */
    fun renderBitmap(result: VideoAnalysisResult): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        // Subtle vertical gradient for depth without distracting from
        // the score — same idiom the in-app GlassCard uses.
        val gradientPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f,
                0f, HEIGHT.toFloat(),
                BACKGROUND_TOP,
                BACKGROUND,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), gradientPaint)

        // Wordmark
        val wordmarkPaint = Paint().apply {
            color = TEXT_PRIMARY
            textSize = 56f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("BowlTrack", 80f, 130f, wordmarkPaint)

        val taglinePaint = Paint(wordmarkPaint).apply {
            color = TEXT_SECONDARY
            textSize = 28f
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText("Track. Knock. Analyze.", 80f, 175f, taglinePaint)

        // Hero score
        val labelPaint = Paint(taglinePaint).apply {
            color = TEXT_SECONDARY
            textSize = 32f
            letterSpacing = 0.18f
        }
        canvas.drawText("FINAL SCORE", 80f, 320f, labelPaint)

        val scorePaint = Paint().apply {
            color = ACCENT_MINT
            textSize = 280f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("${result.fallenPins.size}", 80f, 600f, scorePaint)

        val outOfPaint = Paint(scorePaint).apply {
            color = TEXT_PRIMARY
            textSize = 110f
        }
        canvas.drawText(" / ${result.totalPins}", 360f, 600f, outOfPaint)

        // Stat strip
        val stats = listOf(
            "Frames" to result.frameCount.toString(),
            "Falls"  to result.fallenPins.size.toString(),
            "YOLO"   to (if (result.yoloStatus == "ok") "${result.yoloInvocations} runs" else result.yoloStatus),
        )
        val stripTop = 730f
        val cellWidth = (WIDTH - 160f) / stats.size
        stats.forEachIndexed { index, (label, value) ->
            val cellLeft = 80f + cellWidth * index
            val rect = RectF(cellLeft, stripTop, cellLeft + cellWidth - 16f, stripTop + 160f)
            val cellBg = Paint().apply {
                color = SURFACE_TIER
                isAntiAlias = true
            }
            canvas.drawRoundRect(rect, 28f, 28f, cellBg)
            val valuePaint = Paint().apply {
                color = ACCENT_MINT
                textSize = 60f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }
            canvas.drawText(value, rect.left + 24f, rect.top + 80f, valuePaint)
            val labelP = Paint(labelPaint).apply { textSize = 24f }
            canvas.drawText(label.uppercase(), rect.left + 24f, rect.top + 130f, labelP)
        }

        // Timeline preview (first 6 falls)
        val timelineTop = 940f
        val timelineLabel = Paint(labelPaint).apply { textSize = 26f }
        canvas.drawText("FALL TIMELINE", 80f, timelineTop, timelineLabel)

        val rowPaint = Paint().apply {
            color = TEXT_PRIMARY
            textSize = 36f
            isAntiAlias = true
        }
        val timestampPaint = Paint().apply {
            color = ACCENT_CORAL
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val visibleFalls = result.fallenPins.take(6)
        if (visibleFalls.isEmpty()) {
            val emptyPaint = Paint(rowPaint).apply {
                color = TEXT_SECONDARY
                textSize = 32f
            }
            canvas.drawText("No pins fell during this run.", 80f, timelineTop + 60f, emptyPaint)
        } else {
            visibleFalls.forEachIndexed { index, fall ->
                val rowY = timelineTop + 60f + index * 56f
                drawOrderBadge(canvas, x = 80f, y = rowY - 28f, order = fall.order)
                canvas.drawText("Pin #${fall.pinId}", 160f, rowY, rowPaint)
                canvas.drawText("${"%.2f".format(fall.timestampSeconds)}s", WIDTH - 220f, rowY, timestampPaint)
            }
            if (result.fallenPins.size > visibleFalls.size) {
                val more = result.fallenPins.size - visibleFalls.size
                val morePaint = Paint(rowPaint).apply { color = TEXT_SECONDARY; textSize = 28f }
                canvas.drawText(
                    "+$more more",
                    80f,
                    timelineTop + 60f + visibleFalls.size * 56f + 16f,
                    morePaint,
                )
            }
        }

        // Footer
        val footerPaint = Paint(taglinePaint).apply {
            color = TEXT_TERTIARY
            textSize = 24f
        }
        canvas.drawText(
            "Captured & analysed on-device. No video left this phone.",
            80f,
            HEIGHT - 80f,
            footerPaint,
        )

        return bitmap
    }

    private fun drawOrderBadge(canvas: Canvas, x: Float, y: Float, order: Int) {
        val r = 24f
        val cx = x + r
        val cy = y + r
        val bg = Paint().apply {
            color = ACCENT_MINT
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, r, bg)
        val txt = Paint().apply {
            color = BACKGROUND
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(order.toString(), cx, cy + 10f, txt)
    }

    // Mirror of the Compose tokens — kept literal here so the share
    // card never depends on a Compose theme being current.
    private val BACKGROUND = Color.rgb(0x0A, 0x0E, 0x1A)
    private val BACKGROUND_TOP = Color.rgb(0x14, 0x19, 0x25)
    private val SURFACE_TIER = Color.rgb(0x1E, 0x25, 0x36)
    private val ACCENT_MINT = Color.rgb(0x00, 0xD9, 0xA3)
    private val ACCENT_CORAL = Color.rgb(0xFF, 0x47, 0x57)
    private val TEXT_PRIMARY = Color.rgb(0xF5, 0xF7, 0xFA)
    private val TEXT_SECONDARY = Color.rgb(0x8B, 0x95, 0xA7)
    private val TEXT_TERTIARY = Color.rgb(0x5A, 0x64, 0x78)
}
