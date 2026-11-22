package com.localcaption.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.localcaption.app.MainActivity
import com.localcaption.app.R
import com.localcaption.app.service.CaptionForegroundService.Companion.ServiceStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that starts/stops the floating live caption.
 *
 * The tile mirrors [ServiceStateHolder.state]: it is ACTIVE
 * while a session exists and its subtitle reflects the current recognition state.
 * Tapping it while idle launches [MainActivity] with
 * [MainActivity.ACTION_QUICK_OVERLAY_CAPTION], which switches to the Live tab and starts
 * a session with the overlay enabled.
 */
class CaptionTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile(ServiceStateHolder.state.value)
        if (stateJob == null) {
            stateJob = scope.launch {
                ServiceStateHolder.state.collect { state ->
                    refreshTile(state)
                }
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = ServiceStateHolder.state.value
        if (state.isRunning || state.audioSource != null) {
            CaptionForegroundService.stop(this)
        } else if (isLocked) {
            unlockAndRun { launchQuickOverlay() }
        } else {
            launchQuickOverlay()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile(state: CaptionServiceState) {
        val tile = qsTile ?: return
        val active = state.isRunning || state.audioSource != null
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_caption_label)
        tile.subtitle = when {
            state.isPaused -> "已暂停"
            state.isOverlayVisible -> "正在识别 · 悬浮窗显示中"
            active -> "正在识别"
            else -> "点击开启悬浮字幕"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_caption)
        tile.updateTile()
    }

    private fun launchQuickOverlay() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_OVERLAY_CAPTION)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_QUICK_OVERLAY,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        private const val REQUEST_QUICK_OVERLAY = 3001
    }
}
