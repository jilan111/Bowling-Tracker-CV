package com.bowltrack.data.repository

import com.bowltrack.data.db.SessionDao
import com.bowltrack.data.db.entities.BowlingSession
import com.bowltrack.data.db.entities.FallenPinRecord as PersistedFall
import com.bowltrack.data.db.entities.PathPointRecord
import com.bowltrack.python.VideoAnalysisResult
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * Domain-level facade over [SessionDao].
 *
 * The repository is the only class that knows both Room types and the
 * Python bridge's [VideoAnalysisResult]. It is responsible for the
 * shape conversion, the foreign-key plumbing, and the small bit of
 * "business logic" that decides what counts as a complete session.
 *
 * Threading: every public function is `suspend` or returns a `Flow`,
 * so callers never block. The DAO already runs writes on Room's
 * background pool; we don't need an extra dispatcher hop here.
 */
class SessionRepository(private val dao: SessionDao) {

    /** All sessions, most-recent first. Hot when collected. */
    val sessions: Flow<List<BowlingSession>> = dao.observeSessions()

    /** One-shot lookup used by the read-only detail screen. */
    suspend fun loadFull(id: String): SavedRunBundle? {
        val session = dao.findById(id) ?: return null
        return SavedRunBundle(
            session = session,
            falls = dao.fallsFor(id),
            path = dao.pathFor(id),
        )
    }

    /**
     * Persists a [VideoAnalysisResult] freshly produced by the
     * pipeline. Returns the assigned id so the caller can route to
     * a detail view if it wants. Idempotent on the (videoPath,
     * recordedAtMillis) pair only at the conceptual level — the
     * caller is expected to invoke this exactly once per
     * "Save to history" tap.
     */
    suspend fun saveAnalysis(video: File, result: VideoAnalysisResult): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val session = BowlingSession(
            id = id,
            videoPath = video.absolutePath,
            recordedAtMillis = now,
            durationSeconds = result.fallenPins.maxOfOrNull { it.timestampSeconds }
                ?: (result.frameCount.toFloat() / 15f),
            totalPins = result.totalPins,
            fallenPins = result.fallenPins.size,
            analysisWidth = result.analysisWidth,
            analysisHeight = result.analysisHeight,
            yoloStatus = result.yoloStatus,
            yoloInvocations = result.yoloInvocations,
            carColor = result.carSources.color,
            carYolo = result.carSources.yolo,
            carLk = result.carSources.lk,
            carMissing = result.carSources.missing,
            classicalMs = result.timingTotalMs.classicalMs,
            yoloMs = result.timingTotalMs.yoloMs,
        )
        val falls = result.fallenPins.map { record ->
            PersistedFall(
                sessionId = id,
                order = record.order,
                pinId = record.pinId,
                frameIndex = record.frameIndex,
                timestampSeconds = record.timestampSeconds,
            )
        }
        val path = result.carPath.mapIndexed { index, point ->
            PathPointRecord(
                sessionId = id,
                position = index,
                x = point.first,
                y = point.second,
            )
        }

        dao.saveSession(session, falls, path)
        return id
    }

    /** Removes a session and (via FK cascade) its child rows. */
    suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

/** All persisted state for a single saved run, read on demand. */
data class SavedRunBundle(
    val session: BowlingSession,
    val falls: List<PersistedFall>,
    val path: List<PathPointRecord>,
)
