package com.localcaption.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localcaption.app.ui.history.HistoryDetailScreen
import com.localcaption.app.ui.history.HistoryListScreen
import com.localcaption.app.ui.live.LiveScreen
import com.localcaption.app.ui.models.ModelManagerScreen
import com.localcaption.app.ui.settings.SettingsScreen

/** Route constants of the app. */
object AppRoutes {
    const val LIVE = "live"
    const val HISTORY = "history"
    const val MODELS = "models"
    const val SETTINGS = "settings"

    /** HistoryDetail route pattern; the concrete route is built by [historyDetail]. */
    const val HISTORY_DETAIL = "history/{sessionId}"
    const val ARG_SESSION_ID = "sessionId"

    fun historyDetail(sessionId: Long): String = "history/$sessionId"
}

/** The four top level destinations shown in the bottom navigation bar. */
private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    LIVE(AppRoutes.LIVE, "实时字幕", Icons.Filled.Mic),
    HISTORY(AppRoutes.HISTORY, "历史记录", Icons.Filled.History),
    MODELS(AppRoutes.MODELS, "模型管理", Icons.Filled.Storage),
    SETTINGS(AppRoutes.SETTINGS, "设置", Icons.Filled.Settings),
}

/**
 * Root navigation graph: a four tab bottom bar plus the session detail screen.
 *
 * The bottom bar is hidden on [AppRoutes.HISTORY_DETAIL] so the detail screen owns the
 * full height of the window.
 *
 * @param quickOverlayRequest incremented by [com.localcaption.app.MainActivity] when the
 * launcher shortcut or Quick Settings tile asks for a live caption; the host switches to
 * the Live tab and hands the request to [LiveScreen]
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    quickOverlayRequest: Int = 0,
    onConsumeQuickOverlayRequest: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }

    LaunchedEffect(quickOverlayRequest) {
        if (quickOverlayRequest > 0 && currentRoute != AppRoutes.LIVE) {
            navController.navigate(AppRoutes.LIVE) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute == destination.route) return@NavigationBarItem
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoutes.LIVE,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppRoutes.LIVE) {
                LiveScreen(
                    onOpenModels = {
                        navController.navigate(AppRoutes.MODELS) {
                            launchSingleTop = true
                        }
                    },
                    quickOverlayRequest = quickOverlayRequest,
                    onConsumeQuickOverlayRequest = onConsumeQuickOverlayRequest,
                )
            }

            composable(AppRoutes.HISTORY) {
                HistoryListScreen(
                    onOpenSession = { sessionId ->
                        navController.navigate(AppRoutes.historyDetail(sessionId))
                    },
                )
            }

            composable(AppRoutes.MODELS) {
                ModelManagerScreen()
            }

            composable(AppRoutes.SETTINGS) {
                SettingsScreen()
            }

            composable(
                route = AppRoutes.HISTORY_DETAIL,
                arguments = listOf(
                    navArgument(AppRoutes.ARG_SESSION_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                HistoryDetailScreen(
                    sessionId = entry.arguments?.getLong(AppRoutes.ARG_SESSION_ID) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
