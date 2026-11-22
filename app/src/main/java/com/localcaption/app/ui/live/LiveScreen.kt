package com.localcaption.app.ui.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.R
import com.localcaption.app.asr.LiveCaption
import com.localcaption.app.asr.SherpaOnnxBridge
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.service.CaptionForegroundService
import com.localcaption.app.service.CaptionForegroundService.Companion.ServiceStateHolder
import com.localcaption.app.service.CompletedSessionPrompt
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.SpeakerBadge
import com.localcaption.app.ui.common.formatDuration
import com.localcaption.app.ui.common.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * Live caption screen: service status, audio source selection, capture controls and the
 * auto-scrolling list of recognized captions.
 *
 * @param quickOverlayRequest incremented by [com.localcaption.app.MainActivity] when the
 * launcher shortcut or Quick Settings tile fires; the screen then starts a session and
 * turns the floating overlay on
 */
@Composable
fun LiveScreen(
    onOpenModels: () -> Unit,
    quickOverlayRequest: Int = 0,
    onConsumeQuickOverlayRequest: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val serviceState by ServiceStateHolder.state.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULTS)

    // Model / native availability is re-checked whenever the screen is resumed, which
    // covers returning from the model manager tab.
    var checkKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checkKey++ }
    val modelInstalled = remember(settings.asrModel, checkKey) {
        app.modelManager.isInstalled(settings.asrModel)
    }
    val nativeAvailable = remember(checkKey) { SherpaOnnxBridge.isAvailable }

    var hint by remember { mutableStateOf<String?>(null) }
    var pendingSource by remember { mutableStateOf<AudioSourceType?>(null) }
    var pendingAutoShowOverlay by remember { mutableStateOf(false) }
    var startAfterOverlayGrant by remember { mutableStateOf(false) }

    val isActive = serviceState.audioSource != null
    val isRunning = serviceState.isRunning
    val isInitializing = serviceState.isInitializing
    val isStopping = serviceState.isStopping

    // MediaProjection consent result -> start playback capture.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptionForegroundService.startPlaybackCapture(
                context,
                result.resultCode,
                data,
                pendingAutoShowOverlay,
            )
            hint = null
        } else {
            hint = context.getString(R.string.error_missing_projection_permission)
        }
    }

    val startForSource: (AudioSourceType, Boolean) -> Unit = { source, autoShowOverlay ->
        pendingAutoShowOverlay = autoShowOverlay
        if (source == AudioSourceType.PLAYBACK_CAPTURE) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager
            if (manager == null) {
                hint = "无法获取屏幕录制服务"
            } else {
                hint = null
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        } else {
            hint = null
            CaptionForegroundService.startMicrophone(context, autoShowOverlay)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val source = pendingSource
        pendingSource = null
        val granted = grants[Manifest.permission.RECORD_AUDIO]
            ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (source != null && granted) {
            startForSource(source, pendingAutoShowOverlay)
        } else {
            hint = context.getString(R.string.error_missing_audio_permission)
        }
    }

    fun startCapture(autoShowOverlay: Boolean = false) {
        pendingAutoShowOverlay = autoShowOverlay
        val source = settings.audioSource
        val missing = requiredRuntimePermissions().filterNot { hasPermission(context, it) }
        if (missing.isNotEmpty()) {
            pendingSource = source
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startForSource(source, autoShowOverlay)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            hint = null
            if (startAfterOverlayGrant) {
                startAfterOverlayGrant = false
                startCapture(autoShowOverlay = true)
            } else if (isActive) {
                CaptionForegroundService.toggleOverlay(context)
            }
        } else {
            startAfterOverlayGrant = false
            hint = "未授予悬浮窗权限，字幕只在应用内显示"
        }
    }

    fun toggleOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { overlayPermissionLauncher.launch(intent) }
                .onFailure { hint = "无法打开悬浮窗权限设置" }
        } else {
            CaptionForegroundService.toggleOverlay(context)
        }
    }

    // Launcher long-press shortcut / Quick Settings tile: start (or reveal) the overlay.
    LaunchedEffect(quickOverlayRequest) {
        if (quickOverlayRequest <= 0) return@LaunchedEffect
        onConsumeQuickOverlayRequest()
        if (isActive) {
            if (!serviceState.isOverlayVisible) {
                CaptionForegroundService.toggleOverlay(context)
            }
            return@LaunchedEffect
        }
        if (Settings.canDrawOverlays(context)) {
            startCapture(autoShowOverlay = true)
        } else {
            startAfterOverlayGrant = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { overlayPermissionLauncher.launch(intent) }
                .onFailure {
                    startAfterOverlayGrant = false
                    hint = "无法打开悬浮窗权限设置"
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "实时字幕",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        StatusBanner(
            statusMessage = serviceState.statusMessage.ifBlank {
                when {
                    isStopping -> "正在识别剩余语音，请稍候…"
                    isInitializing -> "正在加载模型，请稍候…"
                    serviceState.isPaused -> "已暂停"
                    isRunning -> "已就绪，正在聆听"
                    else -> "未开始"
                }
            },
            isActive = isActive,
            isRunning = isRunning,
            isInitializing = isInitializing,
            isStopping = isStopping,
            isPaused = serviceState.isPaused,
            audioLevel = serviceState.audioLevel,
            modelName = settings.asrModel.displayName,
            modelInstalled = modelInstalled,
            nativeAvailable = nativeAvailable,
            lastError = serviceState.lastError,
            hint = hint,
            onOpenModels = onOpenModels,
        )

        SourceSelector(
            selected = settings.audioSource,
            enabled = !isActive && !isStopping,
            onSelect = { source ->
                scope.launch { app.settingsRepository.updateAudioSource(source) }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (isActive) {
                        CaptionForegroundService.stop(context, promptSaveDialog = true)
                    } else {
                        startCapture()
                    }
                },
                enabled = !isStopping,
                modifier = Modifier.weight(1f),
                colors = if (isActive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (isStopping || isInitializing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    when {
                        isStopping -> "正在保存…"
                        isInitializing -> "取消加载"
                        isActive -> "停止"
                        else -> "开始"
                    },
                )
            }

            OutlinedButton(
                onClick = { CaptionForegroundService.togglePause(context) },
                enabled = isRunning && !isInitializing && !isStopping,
            ) {
                Icon(
                    imageVector = if (serviceState.isPaused) {
                        Icons.Filled.PlayArrow
                    } else {
                        Icons.Filled.Pause
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.size(4.dp))
                Text(if (serviceState.isPaused) "继续" else "暂停")
            }

            OutlinedButton(
                onClick = { toggleOverlay() },
                enabled = isActive && !isStopping,
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Icon(imageVector = Icons.Filled.Layers, contentDescription = "悬浮字幕")
                Spacer(Modifier.size(4.dp))
                Text(if (serviceState.isOverlayVisible) "浮窗开" else "浮窗")
            }
        }

        CaptionList(
            captions = serviceState.recentCaptions,
            emptyHint = when {
                isStopping -> "正在识别最后一段语音，请稍候…"
                isInitializing -> "正在加载本地模型，加载完成后会自动开始识别…"
                serviceState.isPaused -> "已暂停，点击「继续」恢复识别"
                isRunning -> "已就绪，正在聆听…识别结果会显示在这里"
                else -> "点击「开始」创建一条实时字幕"
            },
            modifier = Modifier.weight(1f),
        )
    }

    serviceState.completedSession?.let { prompt ->
        SaveSessionDialog(
            prompt = prompt,
            onSave = { title ->
                val finalTitle = title.trim().ifBlank { prompt.defaultTitle }
                scope.launch {
                    app.transcriptionRepository.renameSession(prompt.sessionId, finalTitle)
                    ServiceStateHolder.dismissCompletedPrompt()
                    hint = "已保存录音档案：$finalTitle"
                }
            },
            onDismiss = {
                ServiceStateHolder.dismissCompletedPrompt()
            },
        )
    }
}

// region Upstream state helpers

/** Runtime permissions needed before a caption session can start. */
private fun requiredRuntimePermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// endregion

@Composable
private fun StatusBanner(
    statusMessage: String,
    isActive: Boolean,
    isRunning: Boolean,
    isInitializing: Boolean,
    isStopping: Boolean,
    isPaused: Boolean,
    audioLevel: Float,
    modelName: String,
    modelInstalled: Boolean,
    nativeAvailable: Boolean,
    lastError: String?,
    hint: String?,
    onOpenModels: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isInitializing || isStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isPaused -> MaterialTheme.colorScheme.tertiary
                                    isRunning -> Color(0xFF2FBF8F)
                                    isActive -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                }
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (isPaused) {
                    MetaChip(
                        text = "已暂停",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else if (isInitializing) {
                    MetaChip(
                        text = "未就绪",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else if (isStopping) {
                    MetaChip(
                        text = "收尾中",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            if (isRunning && !isPaused && !isInitializing && !isStopping) {
                AudioWaveformMeter(level = audioLevel)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(text = modelName, icon = Icons.Filled.GraphicEq)
                MetaChip(
                    text = if (nativeAvailable) "sherpa-onnx 已集成" else "sherpa-onnx 未集成",
                    icon = if (nativeAvailable) Icons.Filled.GraphicEq else Icons.Filled.WarningAmber,
                    containerColor = if (nativeAvailable) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (nativeAvailable) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }

            if (!modelInstalled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "当前模型尚未下载，识别不会有结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenModels) { Text("去下载") }
                }
            }

            if (!nativeAvailable) {
                Text(
                    text = "未检测到 sherpa-onnx 原生库：请把 sherpa-onnx-*.aar 放入 app/libs/ 后重新构建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val error = lastError?.takeIf { it.isNotBlank() }
            if (error != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AudioWaveformMeter(level: Float) {
    val hasVoice = level > 0.04f
    val barWeights = listOf(0.45f, 0.72f, 0.95f, 1.0f, 0.88f, 0.68f, 0.42f)
    val activeColor = Color(0xFF2FBF8F)
    val idleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            barWeights.forEach { weight ->
                val targetFraction = (0.18f + level * weight * 0.82f).coerceIn(0.18f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(durationMillis = 75),
                    label = "audio_bar",
                )
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(22.dp * animatedFraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (hasVoice) activeColor else idleColor),
                )
            }
        }
        Text(
            text = if (hasVoice) "已收到声音信号" else "等待声音输入…",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasVoice) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveSessionDialog(
    prompt: CompletedSessionPrompt,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(prompt.sessionId) { mutableStateOf(prompt.defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存本次录音档案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "已处理全部缓存语音，共识别 ${prompt.segmentCount} 段字幕 · 时长 ${formatDuration(prompt.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("档案名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("保留默认名")
            }
        },
    )
}

@Composable
private fun SourceSelector(
    selected: AudioSourceType,
    enabled: Boolean,
    onSelect: (AudioSourceType) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SourceSegment(
                label = "手机内录",
                selected = selected == AudioSourceType.PLAYBACK_CAPTURE,
                enabled = enabled,
                onClick = { onSelect(AudioSourceType.PLAYBACK_CAPTURE) },
            )
            SourceSegment(
                label = "麦克风收音",
                selected = selected == AudioSourceType.MICROPHONE,
                enabled = enabled,
                onClick = { onSelect(AudioSourceType.MICROPHONE) },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SourceSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled && !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (label == "手机内录") Icons.Filled.GraphicEq else Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun CaptionList(
    captions: List<LiveCaption>,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(captions.size) {
        if (captions.isNotEmpty()) {
            listState.animateScrollToItem(captions.lastIndex)
        }
    }

    if (captions.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = captions, key = { it.segmentId }) { caption ->
            LiveCaptionCard(caption)
        }
    }
}

@Composable
private fun LiveCaptionCard(caption: LiveCaption) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = caption.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val speaker = caption.speakerLabel?.takeIf { it.isNotBlank() }
                if (speaker != null) {
                    SpeakerBadge(speaker)
                }
            }
            Text(
                text = caption.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
