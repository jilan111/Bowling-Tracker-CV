package com.bowltrack.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bowltrack.data.db.BowlTrackDatabase
import com.bowltrack.python.CarSources
import com.bowltrack.python.FallenPinRecord
import com.bowltrack.python.TimingBreakdown
import com.bowltrack.python.VideoAnalysisResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * JVM-side tests for [SessionRepository].
 *
 * Uses Robolectric to host an in-memory Room database. Each test
 * builds a clean DB, exercises the repository, and tears down. We do
 * not exercise the Python bridge here — those interactions are out of
 * scope for a JVM unit test and live in the pytest suite instead.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var database: BowlTrackDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BowlTrackDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saveAnalysis persists session, falls and path atomically`() = runTest {
        val result = sampleResult(falls = 3, pathPoints = 8)
        val id = repository.saveAnalysis(File("/data/recordings/sample.mp4"), result)

        val bundle = repository.loadFull(id)
        assertNotNull("saveAnalysis must round-trip through loadFull", bundle)
        bundle!!
        assertEquals(3, bundle.falls.size)
        assertEquals(8, bundle.path.size)
        assertEquals(result.totalPins, bundle.session.totalPins)
        assertEquals(result.fallenPins.size, bundle.session.fallenPins)
        assertEquals(result.yoloStatus, bundle.session.yoloStatus)
    }

    @Test
    fun `sessions flow emits in reverse-chronological order`() = runTest {
        val older = sampleResult(falls = 1)
        val newer = sampleResult(falls = 2)
        repository.saveAnalysis(File("/data/old.mp4"), older)
        Thread.sleep(2)
        repository.saveAnalysis(File("/data/new.mp4"), newer)

        val sessions = repository.sessions.first()
        assertEquals(2, sessions.size)
        assertTrue(
            "newer session should sort first",
            sessions[0].recordedAtMillis >= sessions[1].recordedAtMillis,
        )
    }

    @Test
    fun `delete cascades child rows via foreign keys`() = runTest {
        val id = repository.saveAnalysis(
            File("/data/run.mp4"),
            sampleResult(falls = 4, pathPoints = 5),
        )

        repository.delete(id)

        val bundle = repository.loadFull(id)
        assertEquals(null, bundle)
    }

    private fun sampleResult(
        falls: Int = 0,
        pathPoints: Int = 4,
    ): VideoAnalysisResult = VideoAnalysisResult(
        videoPath = "/data/sample.mp4",
        totalPins = 10,
        fallenPins = (1..falls).map { i ->
            FallenPinRecord(
                order = i,
                pinId = i,
                frameIndex = 30 + i * 10,
                timestampSeconds = i * 0.5f,
            )
        },
        carPath = (0 until pathPoints).map { it.toFloat() to it.toFloat() },
        frameCount = 90,
        analysisWidth = 720,
        analysisHeight = 1280,
        yoloStatus = "ok",
        yoloInvocations = 18,
        carSources = CarSources(color = 60, yolo = 12, lk = 8, missing = 10),
        timingTotalMs = TimingBreakdown(classicalMs = 410f, yoloMs = 1100f, totalMs = 1520f),
        error = null,
    )
}
