package com.localcaption.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.ui.common.rememberAppContainer
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Languages offered as an ASR hint (`auto` lets the model detect it). */
private val languageOptions = listOf(
    "auto" to "自动",
    "zh" to "中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "yue" to "粤语",
)

/** All user facing preferences, persisted immediately (sliders on release). */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val stored by app.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULTS)

    // Permission state is re-read on every resume, which covers returning from the
    // system permission/battery screens.
    var permissionKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionKey++ }

    val overlayGranted = remember(permissionKey) { Settings.canDrawOverlays(context) }
    val batteryExempt = remember(permissionKey) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
    }

    fun openBatteryPermission() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val opened = runCatching { context.startActivity(request) }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
    }

    // Local draft so slider drags stay smooth; only committed changes hit DataStore.
    var draft by remember { mutableStateOf<AppSettings?>(null) }
    val current = draft ?: stored

    fun persist(updated: AppSettings) {
        draft = updated
        scope.launch { app.settingsRepository.update(updated) }
    }

    fun preview(updated: AppSettings) {
        draft = updated
    }

    fun commit() {
        val pending = draft ?: return
        scope.launch { app.settingsRepository.update(pending) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        PermissionsSection(
            overlayGranted = overlayGranted,
            batteryExempt = batteryExempt,
            onRequestOverlay = { openOverlayPermission() },
            onRequestBattery = { openBatteryPermission() },
            onOpenAppDetails = { openAppDetails() },
        )

        SettingsSection("语音识别") {
            Text(
                text = "识别模型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AsrModelType.entries.forEach { model ->
                    FilterChip(
                        selected = current.asrModel == model,
                        onClick = { persist(current.copy(asrModel = model)) },
                        label = { Text(model.displayName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Text(
                text = "语言提示",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                languageOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = current.language == value,
                        onClick = { persist(current.copy(language = value)) },
                        label = { Text(label) },
                    )
                }
            }

            SliderRow(
                title = "推理线程数",
                valueText = "${current.numThreads}",
                value = current.numThreads.toFloat(),
                valueRange = AppSettings.MIN_NUM_THREADS.toFloat()..
                    AppSettings.MAX_NUM_THREADS.toFloat(),
                steps = AppSettings.MAX_NUM_THREADS - AppSettings.MIN_NUM_THREADS - 1,
                onValueChange = {
                    preview(current.copy(numThreads = it.roundToInt()))
                },
                onValueChangeFinished = { commit() },
                inputValue = "${current.numThreads}",
                inputUnit = "线程",
                inputRangeHint = "范围：${AppSettings.MIN_NUM_THREADS} – ${AppSettings.MAX_NUM_THREADS}",
                onDirectInput = { typed ->
                    persist(
                        current.copy(
                            numThreads = typed.roundToInt().coerceIn(
                                AppSettings.MIN_NUM_THREADS,
                                AppSettings.MAX_NUM_THREADS,
                            ),
                        ),
                    )
                },
            )

            Text(
                text = "推理后端（硬件加速）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.executionProvider != AppSettings.PROVIDER_NNAPI,
                    onClick = { persist(current.copy(executionProvider = AppSettings.PROVIDER_CPU)) },
                    label = { Text("CPU（稳定推荐）") },
                )
                FilterChip(
                    selected = current.executionProvider == AppSettings.PROVIDER_NNAPI,
                    onClick = { persist(current.copy(executionProvider = AppSettings.PROVIDER_NNAPI)) },
                    label = { Text("NNAPI（实验性 NPU）") },
                )
            }
            Text(
                text = "提示：NNAPI 会尝试将部分算子委托给手机 NPU/DSP；若当前模型不支持会自动安全回退到 CPU。" +
                    "想要更低延迟，推荐优先切换至 SenseVoice Small 或 Paraformer-zh Small，并将推理线程调至 4。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection("说话人分离") {
            SwitchRow(
                title = "启用说话人分离",
                subtitle = "需要 3D-Speaker 嵌入模型，未安装时自动跳过",
                checked = current.enableDiarization,
                onCheckedChange = {
                    persist(current.copy(enableDiarization = it))
                },
            )
            SliderRow(
                title = "聚类相似度阈值",
                valueText = String.format(Locale.US, "%.2f", current.diarizationThreshold),
                value = current.diarizationThreshold,
                valueRange = 0.1f..0.95f,
                enabled = current.enableDiarization,
                onValueChange = {
                    preview(current.copy(diarizationThreshold = it))
                },
                onValueChangeFinished = { commit() },
                inputValue = String.format(Locale.US, "%.2f", current.diarizationThreshold),
                inputUnit = "",
                inputRangeHint = "范围：0.10 – 0.95",
                isDecimal = true,
                onDirectInput = { typed ->
                    persist(current.copy(diarizationThreshold = typed.coerceIn(0.1f, 0.95f)))
                },
            )
        }

        SettingsSection("断句 (VAD)") {
            SliderRow(
                title = "静音断句时长",
                valueText = "${current.vadSilenceMs} ms",
                value = current.vadSilenceMs.toFloat(),
                valueRange = 100f..500f,
                onValueChange = {
                    preview(current.copy(vadSilenceMs = it.roundToInt()))
                },
                onValueChangeFinished = { commit() },
                inputValue = "${current.vadSilenceMs}",
                inputUnit = "ms",
                inputRangeHint = "滑块 100–500 ms，手动输入支持 ${AppSettings.MIN_VAD_SILENCE_MS}–${AppSettings.MAX_VAD_SILENCE_MS} ms",
                onDirectInput = { typed ->
                    persist(
                        current.copy(
                            vadSilenceMs = typed.roundToInt().coerceIn(
                                AppSettings.MIN_VAD_SILENCE_MS,
                                AppSettings.MAX_VAD_SILENCE_MS,
                            ),
                        ),
                    )
                },
            )
            SliderRow(
                title = "句首回溯比例（前段空白占比）",
                valueText = "${(current.preRollSilenceRatio * 100).roundToInt()}%",
                value = current.preRollSilenceRatio,
                valueRange = 0f..1f,
                onValueChange = {
                    preview(current.copy(preRollSilenceRatio = it))
                },
                onValueChangeFinished = { commit() },
                inputValue = "${(current.preRollSilenceRatio * 100).roundToInt()}",
                inputUnit = "%",
                inputRangeHint = "范围：0 – 100 %",
                onDirectInput = { typed ->
                    persist(
                        current.copy(
                            preRollSilenceRatio = (typed / 100f).coerceIn(0f, 1f),
                        ),
                    )
                },
            )
            Text(
                text = "按当前句前方空白间隔的比例回溯音频（上限 180 ms）以保留开头清辅音；调为 0% 则完全关闭回溯。点击任意数值可直接手动输入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderRow(
                title = "单句最长时长",
                valueText = String.format(
                    Locale.US,
                    "%.1f s",
                    current.maxSegmentDurationMs / 1000f,
                ),
                value = current.maxSegmentDurationMs.toFloat(),
                valueRange = AppSettings.MIN_SEGMENT_DURATION_MS.toFloat()..
                    AppSettings.MAX_SEGMENT_DURATION_MS.toFloat(),
                onValueChange = {
                    preview(current.copy(maxSegmentDurationMs = it.roundToInt()))
                },
                onValueChangeFinished = { commit() },
                inputValue = String.format(Locale.US, "%.1f", current.maxSegmentDurationMs / 1000f),
                inputUnit = "s",
                inputRangeHint = "范围：${AppSettings.MIN_SEGMENT_DURATION_MS / 1000} – ${AppSettings.MAX_SEGMENT_DURATION_MS / 1000} s",
                isDecimal = true,
                onDirectInput = { typed ->
                    val ms = (typed * 1000f).roundToInt().coerceIn(
                        AppSettings.MIN_SEGMENT_DURATION_MS,
                        AppSettings.MAX_SEGMENT_DURATION_MS,
                    )
                    persist(current.copy(maxSegmentDurationMs = ms))
                },
            )
        }

        SettingsSection("悬浮字幕") {
            SliderRow(
                title = "字号",
                valueText = String.format(Locale.US, "%.0f sp", current.fontSizeSp),
                value = current.fontSizeSp,
                valueRange = 12f..40f,
                onValueChange = { preview(current.copy(fontSizeSp = it)) },
                onValueChangeFinished = { commit() },
                inputValue = String.format(Locale.US, "%.0f", current.fontSizeSp),
                inputUnit = "sp",
                inputRangeHint = "范围：12 – 40 sp",
                onDirectInput = { typed ->
                    persist(current.copy(fontSizeSp = typed.coerceIn(12f, 40f)))
                },
            )
            SliderRow(
                title = "背景不透明度",
                valueText = "${(current.overlayBgAlpha * 100).roundToInt()}%",
                value = current.overlayBgAlpha,
                valueRange = AppSettings.MIN_OVERLAY_ALPHA..AppSettings.MAX_OVERLAY_ALPHA,
                onValueChange = { preview(current.copy(overlayBgAlpha = it)) },
                onValueChangeFinished = { commit() },
                inputValue = "${(current.overlayBgAlpha * 100).roundToInt()}",
                inputUnit = "%",
                inputRangeHint = "范围：10 – 100 %",
                onDirectInput = { typed ->
                    persist(
                        current.copy(
                            overlayBgAlpha = (typed / 100f).coerceIn(
                                AppSettings.MIN_OVERLAY_ALPHA,
                                AppSettings.MAX_OVERLAY_ALPHA,
                            ),
                        ),
                    )
                },
            )
            SliderRow(
                title = "最多显示行数",
                valueText = "${current.overlayMaxLines} 行",
                value = current.overlayMaxLines.toFloat(),
                valueRange = AppSettings.MIN_OVERLAY_LINES.toFloat()..
                    AppSettings.MAX_OVERLAY_LINES.toFloat(),
                steps = AppSettings.MAX_OVERLAY_LINES - AppSettings.MIN_OVERLAY_LINES - 1,
                onValueChange = {
                    preview(current.copy(overlayMaxLines = it.roundToInt()))
                },
                onValueChangeFinished = { commit() },
                inputValue = "${current.overlayMaxLines}",
                inputUnit = "行",
                inputRangeHint = "范围：${AppSettings.MIN_OVERLAY_LINES} – ${AppSettings.MAX_OVERLAY_LINES} 行",
                onDirectInput = { typed ->
                    persist(
                        current.copy(
                            overlayMaxLines = typed.roundToInt().coerceIn(
                                AppSettings.MIN_OVERLAY_LINES,
                                AppSettings.MAX_OVERLAY_LINES,
                            ),
                        ),
                    )
                },
            )
            SwitchRow(
                title = "显示时间戳",
                subtitle = "在悬浮窗每条字幕前显示开始时间",
                checked = current.showTimestamps,
                onCheckedChange = { persist(current.copy(showTimestamps = it)) },
            )
        }

        SettingsSection("导出") {
            SwitchRow(
                title = "导出时包含说话人",
                checked = current.exportIncludeSpeaker,
                onCheckedChange = { persist(current.copy(exportIncludeSpeaker = it)) },
            )
            SwitchRow(
                title = "导出时包含时间戳",
                subtitle = "仅影响 TXT 导出，SRT 始终带时间轴",
                checked = current.exportIncludeTimestamps,
                onCheckedChange = { persist(current.copy(exportIncludeTimestamps = it)) },
            )
        }

        Text(
            text = "所有识别、模型与记录都只保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun PermissionsSection(
    overlayGranted: Boolean,
    batteryExempt: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    SettingsSection("权限与后台保活") {
        Text(
            text = "悬浮字幕需要显示在其他应用上层；长时间听写还需要系统允许应用在后台运行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionStatus(
            title = "悬浮窗权限",
            granted = overlayGranted,
            grantedHint = "已授权，字幕可以悬浮在其他应用上方。",
            deniedHint = "未授权时字幕只在应用内显示，无法悬浮到其他应用上方。",
        )
        OutlinedButton(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("去授权")
        }

        PermissionStatus(
            title = "后台保活保护",
            granted = batteryExempt,
            grantedHint = "已加入电池优化白名单，锁屏后不易被系统终止。",
            deniedHint = "未加入白名单时，锁屏或切到后台后系统可能停止转录。",
        )
        OutlinedButton(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
            Text("开启后台保护")
        }
        OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.fillMaxWidth()) {
            Text("应用详情 / 自启动设置")
        }
        Text(
            text = "国产 ROM 提示：MIUI / HyperOS、EMUI、ColorOS、OriginOS 等还需要在" +
                "「应用详情 → 电池 / 耗电管理」中允许后台活动，并在「自启动管理」中允许" +
                "本应用自启动，否则锁屏后悬浮字幕可能被系统清理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionStatus(
    title: String,
    granted: Boolean,
    grantedHint: String,
    deniedHint: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) grantedHint else deniedHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (granted) "已开启" else "未开启",
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0,
    enabled: Boolean = true,
    inputValue: String = "",
    inputUnit: String = "",
    inputRangeHint: String = "",
    isDecimal: Boolean = false,
    onDirectInput: ((Float) -> Unit)? = null,
) {
    var showInputDialog by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (onDirectInput != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable(enabled = enabled) {
                        draftText = inputValue
                        showInputDialog = true
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "手动输入",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            } else {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }

    if (showInputDialog && onDirectInput != null) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = { Text("设置$title") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inputRangeHint.isNotBlank()) {
                        Text(
                            text = inputRangeHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        singleLine = true,
                        label = {
                            Text(if (inputUnit.isNotBlank()) "数值（$inputUnit）" else "数值")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = draftText.trim().toFloatOrNull()
                        showInputDialog = false
                        if (parsed != null) {
                            onDirectInput(parsed)
                        }
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) { Text("取消") }
            },
        )
    }
}
