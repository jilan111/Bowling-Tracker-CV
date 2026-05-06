package com.bowltrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bowltrack.data.db.entities.BowlingSession
import com.bowltrack.data.db.entities.FallenPinRecord
import com.bowltrack.data.db.entities.PathPointRecord
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO covering everything the UI layer needs to do with a
 * persisted run.
 *
 * Reads are exposed as [Flow]s so the UI can collect once and react
 * to changes without polling. Writes are `suspend` and always
 * sequential — there is no parallel write workload to defend
 * against, so we lean on Room's per-database write lock rather than
 * adding a Mutex.
 */
@Dao
interface SessionDao {

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------
    /** Most recent first, used by Home's recent-runs row and by History. */
    @Query("SELECT * FROM bowling_session ORDER BY recordedAtMillis DESC")
    fun observeSessions(): Flow<List<BowlingSession>>

    /** One-shot lookup for the detail screen / share-card flow. */
    @Query("SELECT * FROM bowling_session WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BowlingSession?

    @Query("SELECT * FROM fallen_pin_record WHERE sessionId = :id ORDER BY `order` ASC")
    suspend fun fallsFor(id: String): List<FallenPinRecord>

    @Query("SELECT * FROM path_point_record WHERE sessionId = :id ORDER BY position ASC")
    suspend fun pathFor(id: String): List<PathPointRecord>

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: BowlingSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFalls(records: List<FallenPinRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPath(records: List<PathPointRecord>)

    @Query("DELETE FROM bowling_session WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Atomically writes one full session (parent + children).
     *
     * Wrapped in [Transaction] so a partial save cannot leave the
     * database with orphaned child rows or a session with no path /
     * no falls. Cascades on the children's foreign keys handle the
     * delete-side of the same invariant.
     */
    @Transaction
    suspend fun saveSession(
        session: BowlingSession,
        falls: List<FallenPinRecord>,
        path: List<PathPointRecord>,
    ) {
        upsertSession(session)
        if (falls.isNotEmpty()) insertFalls(falls)
        if (path.isNotEmpty()) insertPath(path)
    }
}
