package com.localcaption.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.localcaption.app.ui.navigation.AppNavHost
import com.localcaption.app.ui.theme.LocalLiveCaptionTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single activity host of the Compose UI.
 *
 * The activity is `singleTop`, and handles [ACTION_QUICK_OVERLAY_CAPTION] (sent by the
 * launcher long-press shortcut and the Quick Settings tile) by forwarding a monotonically
 * increasing request to [AppNavHost], which switches to the Live tab and starts the
 * floating caption.
 */
class MainActivity : ComponentActivity() {

    private val quickOverlayRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only consume the launch intent on a fresh start; a configuration change
        // restores the same intent and must not re-trigger the quick action.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            LocalLiveCaptionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val quickOverlayRequest by quickOverlayRequests.collectAsState()
                    AppNavHost(
                        quickOverlayRequest = quickOverlayRequest,
                        onConsumeQuickOverlayRequest = { quickOverlayRequests.value = 0 },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!launchedFromHistory && intent.action == ACTION_QUICK_OVERLAY_CAPTION) {
            quickOverlayRequests.update { it + 1 }
            // Clear the action immediately so re-opening the app from recents or
            // returning to the Activity never replays the quick-start prompt.
            intent.action = Intent.ACTION_MAIN
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    companion object {
        /** Action that starts a live caption session with the floating overlay shown. */
        const val ACTION_QUICK_OVERLAY_CAPTION =
            "com.localcaption.app.ACTION_QUICK_OVERLAY_CAPTION"
    }
}
