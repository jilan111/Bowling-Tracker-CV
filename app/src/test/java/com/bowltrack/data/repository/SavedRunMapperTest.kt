package com.bowltrack.data.repository

import com.bowltrack.data.db.entities.BowlingSession
import com.bowltrack.data.db.entities.FallenPinRecord
import com.bowltrack.data.db.entities.PathPointRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM round-trip test for the saved-bundle → analysis-result
 * mapper. Worth isolating because the same mapper is what makes the
 * Saved-mode Results screen render identically to the Live one.
 */
class SavedRunMapperTest {

    @Test
    fun `mapper preserves score, timings, car sources, and path order`() {
        val session = BowlingSession(
            id = "uuid-001",
            videoPath = "/data/run.mp4",
            recordedAtMillis = 1_700_000_000_000L,
            durationSeconds = 6.5f,
            totalPins = 10,
            fallenPins = 3,
            analysisWidth = 720,
            analysisHeight = 1280,
            yoloStatus = "ok",
            yoloInvocations = 12,
            carColor = 50,
            carYolo = 6,
            carLk = 14,
            carMissing = 4,
            classicalMs = 320f,
            yoloMs = 880f,
        )
        val falls = listOf(
            FallenPinRecord(sessionId = session.id, order = 1, pinId = 4, frameIndex = 18, timestampSeconds = 0.6f),
            FallenPinRecord(sessionId = session.id, order = 2, pinId = 7, frameIndex = 36, timestampSeconds = 1.2f),
        )
        val path = (0 until 5).map { i ->
            PathPointRecord(sessionId = session.id, position = i, x = i.toFloat(), y = (10 - i).toFloat())
        }
        val bundle = SavedRunBundle(session = session, falls = falls, path = path)

        val result = bundle.toAnalysisResult()

        assertEquals(session.totalPins, result.totalPins)
        assertEquals(session.fallenPins, result.fallenPins.size)
        assertEquals(session.carColor, result.carSources.color)
        assertEquals(session.classicalMs, result.timingTotalMs.classicalMs, 0.0001f)
        assertEquals(session.yoloMs, result.timingTotalMs.yoloMs, 0.0001f)
        // Path order preserved.
        assertEquals(path.first().x, result.carPath.first().first, 0.0001f)
        assertEquals(path.last().y, result.carPath.last().second, 0.0001f)
    }
}
