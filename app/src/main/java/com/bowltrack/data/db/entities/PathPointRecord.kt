package com.bowltrack.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One smoothed-path sample.
 *
 * The orchestrator typically returns a few hundred points per video
 * (the Catmull-Rom densifier emits ~12 samples per control segment),
 * which is more than fits in a JSON column comfortably. Keeping each
 * sample as its own row gives Room cheap ordered reads via the
 * `position` index and lets us drop a session's path with a cascade
 * when the parent is deleted.
 */
@Entity(
    tableName = "path_point_record",
    foreignKeys = [
        ForeignKey(
            entity = BowlingSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "position"]),
    ],
)
data class PathPointRecord(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val sessionId: String,
    val position: Int,
    val x: Float,
    val y: Float,
)
