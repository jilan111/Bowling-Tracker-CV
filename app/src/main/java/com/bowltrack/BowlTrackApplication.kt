package com.bowltrack

import android.app.Application
import com.bowltrack.data.db.BowlTrackDatabase
import com.bowltrack.data.repository.SessionRepository
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Application entry point.
 *
 * Two responsibilities:
 *
 *  1. Bring the embedded CPython interpreter up before any UI calls
 *     into [com.bowltrack.python.PythonBridge]. Initialising eagerly
 *     here keeps the cold-start hitch off the analysis screen where
 *     it would be visible.
 *
 *  2. Expose the singletons the UI needs to reach the persistence
 *     layer: a process-wide [BowlTrackDatabase] and the
 *     [SessionRepository] wrapping its DAO. Manual DI keeps the
 *     project legible for the thesis defence — a Hilt graph would be
 *     overkill for the two dependencies we wire here.
 */
class BowlTrackApplication : Application() {

    /**
     * Repository used by the UI layer. Resolved lazily so unit-test
     * Application stubs can override it before the first read.
     */
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(BowlTrackDatabase.getInstance(this).sessionDao())
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
