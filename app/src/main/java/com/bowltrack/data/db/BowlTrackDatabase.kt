package com.bowltrack.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bowltrack.data.db.entities.BowlingSession
import com.bowltrack.data.db.entities.FallenPinRecord
import com.bowltrack.data.db.entities.PathPointRecord

/**
 * Room database bundling the three BowlTrack tables.
 *
 * `exportSchema = false` because we ship a single-version schema in
 * the capstone; if a future revision needs migrations we'll flip it
 * back on and check the generated JSON into VCS. Until then, leaving
 * it on would emit a build warning we'd have to silence anyway.
 */
@Database(
    entities = [
        BowlingSession::class,
        FallenPinRecord::class,
        PathPointRecord::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BowlTrackDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: BowlTrackDatabase? = null

        /**
         * Returns the process-wide singleton. We deliberately avoid a
         * DI framework — the capstone has exactly one persistent
         * dependency to wire, and a manual singleton keeps the
         * scaffolding visible for the thesis defence.
         */
        fun getInstance(context: Context): BowlTrackDatabase {
            val current = instance
            if (current != null) return current
            synchronized(this) {
                val checked = instance
                if (checked != null) return checked
                val built = Room.databaseBuilder(
                    context.applicationContext,
                    BowlTrackDatabase::class.java,
                    "bowltrack.db",
                ).build()
                instance = built
                return built
            }
        }
    }
}
