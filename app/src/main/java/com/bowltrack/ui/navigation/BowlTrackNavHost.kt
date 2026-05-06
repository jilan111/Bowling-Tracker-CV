package com.bowltrack.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bowltrack.BowlTrackApplication
import com.bowltrack.ui.analysis.AnalysisScreen
import com.bowltrack.ui.components.BottomBarTopHairline
import com.bowltrack.ui.components.BowlTrackTab
import com.bowltrack.ui.components.HapticBottomBar
import com.bowltrack.ui.debug.AnalyzeVideoDebugScreen
import com.bowltrack.ui.debug.DetectionDebugScreen
import com.bowltrack.ui.debug.FrameExtractorPreview
import com.bowltrack.ui.history.HistoryScreen
import com.bowltrack.ui.history.SessionDetailScreen
import com.bowltrack.ui.home.HomeScreen
import com.bowltrack.ui.input.CameraRecorderScreen
import com.bowltrack.ui.input.VideoPickerScreen
import com.bowltrack.ui.results.ResultsHandoff
import com.bowltrack.ui.results.ResultsScreen
import com.bowltrack.ui.settings.SettingsScreen
import com.bowltrack.ui.settings.calibration.CalibrationTarget
import com.bowltrack.ui.settings.calibration.ColorCalibrationScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

/**
 * Root navigation graph for the app.
 *
 * Built with the simple [NavHost] pattern from `androidx.navigation:
 * navigation-compose`: a single [androidx.navigation.NavHostController]
 * is hoisted at the top, three top-level destinations are registered
 * (Home, History, Settings), and parameterised routes open session
 * details + the live analysis screen.
 *
 * The nav host owns the application-scoped [SessionRepository]
 * collection — every screen that needs the persisted run list reads
 * the same `StateFlow` so a single round-trip to Room serves the
 * whole graph.
 */
@Composable
fun BowlTrackNavHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as BowlTrackApplication
    val repository = remember(app) { app.sessionRepository }
    val coroutineScope = rememberCoroutineScope()

    // Hot StateFlow so collecting from multiple screens does not
    // re-subscribe to Room each time.
    val sessionsFlow = remember(repository, coroutineScope) {
        repository.sessions.stateIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )
    }
    val sessions by sessionsFlow.collectAsState()

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val activeTab = when {
        currentRoute == Routes.History -> BowlTrackTab.History
        currentRoute == Routes.Settings -> BowlTrackTab.Settings
        else -> BowlTrackTab.Home
    }

    val showBottomBar = currentRoute in setOf(
        Routes.Home,
        Routes.History,
        Routes.Settings,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.Home,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (showBottomBar) 80.dp else 0.dp,
                ),
        ) {
            composable(Routes.Home) {
                HomeScreen(
                    recentSessions = sessions.take(8),
                    onRecordClick = { navController.navigate(Routes.Recorder) },
                    onPickClick = { navController.navigate(Routes.Picker) },
                    onSessionClick = { session ->
                        navController.navigate(Routes.sessionDetail(session.id))
                    },
                )
            }
            composable(Routes.Recorder) {
                CameraRecorderScreen(
                    onClose = { navController.popBackStack() },
                    onCaptureComplete = { file, _ ->
                        navController.navigate(Routes.analysis(file.absolutePath)) {
                            popUpTo(Routes.Recorder) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.Picker) {
                VideoPickerScreen(
                    onClose = { navController.popBackStack() },
                    onPicked = { file ->
                        navController.navigate(Routes.analysis(file.absolutePath)) {
                            popUpTo(Routes.Picker) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.AnalysisPattern,
                arguments = listOf(
                    navArgument(Routes.ArgVideoPath) { type = NavType.StringType }
                ),
            ) { entry ->
                val encoded = entry.arguments?.getString(Routes.ArgVideoPath).orEmpty()
                val videoPath = URLDecoder.decode(encoded, "UTF-8")
                AnalysisScreen(
                    video = File(videoPath),
                    onClose = { navController.popBackStack() },
                    onComplete = { video, result ->
                        ResultsHandoff.put(video, result)
                        navController.navigate(Routes.Results) {
                            popUpTo(Routes.AnalysisPattern) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.Results) {
                ResultsScreen(
                    onBack = { navController.popBackStack() },
                    onNewRun = {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Home) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onSave = { result ->
                        // We only know the source video here through
                        // the hand-off, which has already been taken
                        // by the time the user taps Save. Look up the
                        // path from the result instead.
                        val video = File(result.videoPath)
                        coroutineScope.launch(Dispatchers.IO) {
                            repository.saveAnalysis(video, result)
                        }
                    },
                )
            }
            composable(Routes.History) {
                HistoryScreen(
                    sessions = sessions,
                    onSessionClick = { session ->
                        navController.navigate(Routes.sessionDetail(session.id))
                    },
                    onDeleteSession = { session ->
                        coroutineScope.launch(Dispatchers.IO) {
                            repository.delete(session.id)
                        }
                    },
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    onPinColorClick = {
                        navController.navigate(Routes.calibration("pin"))
                    },
                    onCarColorClick = {
                        navController.navigate(Routes.calibration("car"))
                    },
                    onFrameExtractorDebugClick = {
                        navController.navigate(Routes.FrameExtractorDebug)
                    },
                    onDetectionDebugClick = {
                        navController.navigate(Routes.DetectionDebug)
                    },
                    onAnalyzeVideoDebugClick = {
                        navController.navigate(Routes.AnalyzeVideoDebug)
                    },
                )
            }
            composable(
                route = Routes.CalibrationPattern,
                arguments = listOf(
                    navArgument(Routes.ArgCalibrationTarget) { type = NavType.StringType }
                ),
            ) { entry ->
                val raw = entry.arguments?.getString(Routes.ArgCalibrationTarget).orEmpty()
                val target = if (raw.equals("car", ignoreCase = true)) {
                    CalibrationTarget.Car
                } else {
                    CalibrationTarget.Pin
                }
                ColorCalibrationScreen(
                    target = target,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.FrameExtractorDebug) {
                FrameExtractorPreview(
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.DetectionDebug) {
                DetectionDebugScreen(
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.AnalyzeVideoDebug) {
                AnalyzeVideoDebugScreen(
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.SessionDetailPattern,
                arguments = listOf(
                    navArgument(Routes.ArgSessionId) { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments
                    ?.getString(Routes.ArgSessionId)
                    .orEmpty()
                SessionDetailScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onNewRun = {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Home) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }

        if (showBottomBar) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding(),
            ) {
                BottomBarTopHairline()
                HapticBottomBar(
                    selected = activeTab,
                    onSelect = { tab ->
                        val route = when (tab) {
                            BowlTrackTab.Home -> Routes.Home
                            BowlTrackTab.History -> Routes.History
                            BowlTrackTab.Settings -> Routes.Settings
                        }
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(Routes.Home) {
                                    saveState = true
                                    inclusive = false
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }

}
