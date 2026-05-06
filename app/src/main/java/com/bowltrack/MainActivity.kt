package com.bowltrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bowltrack.ui.navigation.BowlTrackNavHost
import com.bowltrack.ui.theme.BowlTrackTheme

/**
 * Single-activity host for the Compose UI.
 *
 * Mounts [BowlTrackNavHost], which owns the navigation graph and the
 * persistent bottom bar. Auxiliary harness behaviour like edge-to-edge
 * layout is set here rather than inside the Compose tree because it
 * applies window-wide before the first frame renders.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BowlTrackTheme { BowlTrackNavHost() }
        }
    }
}
