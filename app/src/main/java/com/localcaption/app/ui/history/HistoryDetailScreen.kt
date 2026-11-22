package com.localcaption.app.ui.history

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.asr.OfflineSessionRediarizer
import com.localcaption.app.data.db.SegmentEntity
import com.localcaption.app.data.db.SpeakerEntity
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.export.ExportHelper
import com.localcaption.app.export.formatSrt
import com.localcaption.app.export.formatTxt
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.SpeakerBadge
import com.localcaption.app.ui.common.audioSourceLabel
import com.localcaption.app.ui.common.formatClock
import com.localcaption.app.ui.common.formatDuration
import com.localcaption.app.ui.common.formatSessionDate
import com.localcaption.app.ui.common.rememberAppContainer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session transcript with renaming, speaker management (rename, merge, whole-session
 * offline re-diarization), inline segment editing (text + speaker) and SRT / TXT export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val settings by app.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULTS)
    val session by app.transcriptionRepository.observeSession(sessionId)
        .collectAsStateWithLifecycle(initialValue = null)
    val segments by app.transcriptionRepository.observeSegments(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val speakers by app.transcriptionRepository.observeSpeakers(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showRenameSession by remember { mutableStateOf(false) }
    var sessionTitleDraft by remember { mutableStateOf("") }
    var speakerToManage by remember { mutableStateOf<SpeakerEntity?>(null) }
    var speakerLabelDraft by remember { mutableStateOf("") }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showRediarizeDialog by remember { mutableStateOf(false) }
    var rediarizing by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var segmentTextDraft by remember { mutableStateOf("") }
    var segmentSpeakerDraft by remember { mutableStateOf<Long?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val speakersMap = remember(speakers) { speakers.associate { it.id to it.label } }
    val audioFile = remember(session?.audioFilePath) {
        session?.audioFilePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
    }

    fun srtContent(): String =
        formatSrt(segments, speakersMap, includeSpeaker = settings.exportIncludeSpeaker)

    fun txtContent(): String = formatTxt(
        segments = segments,
        speakersMap = speakersMap,
        includeTimestamps = settings.exportIncludeTimestamps,
        includeSpeaker = settings.exportIncludeSpeaker,
    )

    fun baseFileName(extension: String): String =
        ExportHelper.buildExportFileName(session?.title ?: "字幕", extension)

    val srtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportHelper.MIME_SRT),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportHelper.writeToUri(context, uri, payload.second)
                }
                message = result.fold(
                    onSuccess = { "已导出 SRT" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportHelper.MIME_TXT),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportHelper.writeToUri(context, uri, payload.second)
                }
                message = result.fold(
                    onSuccess = { "已导出 TXT" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    fun exportSrt() {
        if (segments.isEmpty()) {
            message = "没有可导出的字幕"
            return
        }
        val fileName = baseFileName("srt")
        pendingExport = fileName to srtContent()
        srtLauncher.launch(fileName)
    }

    fun exportTxt() {
        if (segments.isEmpty()) {
            message = "没有可导出的字幕"
            return
        }
        val fileName = baseFileName("txt")
        pendingExport = fileName to txtContent()
        txtLauncher.launch(fileName)
    }

    fun shareSrt() {
        if (segments.isEmpty()) {
            message = "没有可分享的字幕"
            return
        }
        val fileName = baseFileName("srt")
        val content = srtContent()
        val subject = session?.title ?: "字幕"
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                ExportHelper.createShareIntent(
                    context = context,
                    fileName = fileName,
                    content = content,
                    mimeType = ExportHelper.MIME_SRT,
                    subject = subject,
                )
            }
            val chooser = Intent.createChooser(intent, "分享字幕")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .onFailure { message = "分享失败：${it.message ?: "未知错误"}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = session?.title ?: "记录详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    session?.let {
                        Text(
                            text = formatSessionDate(it.startedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        sessionTitleDraft = session?.title.orEmpty()
                        showRenameSession = true
                    },
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = "重命名")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出 SRT") },
                            leadingIcon = {
                                Icon(Icons.Filled.Subtitles, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportSrt()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出 TXT") },
                            leadingIcon = {
                                Icon(Icons.Filled.Description, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportTxt()
                            },
                        )
                    }
                }
                IconButton(onClick = { shareSrt() }) {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = "分享")
                }
            },
        )

        session?.let { current ->
            val duration = current.endedAt?.let { it - current.startedAt }
                ?: (System.currentTimeMillis() - current.startedAt)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    text = audioSourceLabel(current.audioSource),
                    icon = Icons.Filled.Mic,
                )
                MetaChip(
                    text = current.asrModel.displayName,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                MetaChip(
                    text = formatDuration(duration),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let { text ->
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (segments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "action-rediarize") {
                    AssistChip(
                        onClick = {
                            if (rediarizing) return@AssistChip
                            if (audioFile == null) {
                                message = "该历史记录未保存原始音频，仅支持合并或重命名说话人；新录制的会话支持整段重识。"
                            } else {
                                showRediarizeDialog = true
                            }
                        },
                        enabled = !rediarizing,
                        leadingIcon = {
                            if (rediarizing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        label = { Text(if (rediarizing) "整段分析中…" else "重识说话人") },
                    )
                }

                if (speakers.size >= 2) {
                    item(key = "action-merge") {
                        AssistChip(
                            onClick = { showMergeDialog = true },
                            enabled = !rediarizing,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.MergeType,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text("合并说话人") },
                        )
                    }
                }

                items(items = speakers, key = { it.id }) { speaker ->
                    AssistChip(
                        onClick = {
                            speakerToManage = speaker
                            speakerLabelDraft = speaker.label
                        },
                        label = { Text(speaker.label) },
                    )
                }
            }
        }

        HorizontalDivider()

        if (segments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这次记录还没有字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = segments, key = { it.id }) { segment ->
                    SegmentRow(
                        segment = segment,
                        speakerLabel = segment.speakerId?.let { speakersMap[it] },
                        onEdit = {
                            editingSegment = segment
                            segmentTextDraft = segment.text
                            segmentSpeakerDraft = segment.speakerId
                        },
                    )
                }
            }
        }
    }

    if (showRenameSession) {
        AlertDialog(
            onDismissRequest = { showRenameSession = false },
            title = { Text("重命名记录") },
            text = {
                OutlinedTextField(
                    value = sessionTitleDraft,
                    onValueChange = { sessionTitleDraft = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = sessionTitleDraft.trim()
                        showRenameSession = false
                        if (title.isNotEmpty()) {
                            scope.launch {
                                runCatching {
                                    app.transcriptionRepository.renameSession(sessionId, title)
                                }.onSuccess {
                                    message = "已重命名"
                                }.onFailure {
                                    message = "重命名失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameSession = false }) { Text("取消") }
            },
        )
    }

    speakerToManage?.let { speaker ->
        val otherSpeakers = speakers.filter { it.id != speaker.id }
        AlertDialog(
            onDismissRequest = { speakerToManage = null },
            title = { Text("管理「${speaker.label}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "重命名后，该说话人的全部字幕都会同步更新：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = speakerLabelDraft,
                        onValueChange = { speakerLabelDraft = it },
                        singleLine = true,
                        label = { Text("说话人名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (otherSpeakers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "或将该说话人合并到：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            otherSpeakers.forEach { target ->
                                AssistChip(
                                    onClick = {
                                        speakerToManage = null
                                        scope.launch {
                                            runCatching {
                                                app.transcriptionRepository.mergeSpeakers(
                                                    sessionId = sessionId,
                                                    sourceSpeakerId = speaker.id,
                                                    targetSpeakerId = target.id,
                                                )
                                            }.onSuccess {
                                                message = "已将「${speaker.label}」合并到「${target.label}」"
                                            }.onFailure {
                                                message = "合并失败：${it.message ?: "未知错误"}"
                                            }
                                        }
                                    },
                                    label = { Text(target.label) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val label = speakerLabelDraft.trim()
                        speakerToManage = null
                        if (label.isNotEmpty() && label != speaker.label) {
                            scope.launch {
                                runCatching {
                                    app.transcriptionRepository.renameSpeaker(speaker.id, label)
                                }.onSuccess {
                                    message = "已重命名为「$label」"
                                }.onFailure {
                                    message = "重命名失败：${it.message ?: "未知错误"}"
                                }
                            }
                        }
                    },
                ) {
                    Text("保存名称")
                }
            },
            dismissButton = {
                TextButton(onClick = { speakerToManage = null }) { Text("取消") }
            },
        )
    }

    if (showMergeDialog && speakers.size >= 2) {
        MergeSpeakersDialog(
            speakers = speakers,
            onMerge = { source, target ->
                showMergeDialog = false
                scope.launch {
                    runCatching {
                        app.transcriptionRepository.mergeSpeakers(
                            sessionId = sessionId,
                            sourceSpeakerId = source.id,
                            targetSpeakerId = target.id,
                        )
                    }.onSuccess {
                        message = "已将「${source.label}」合并到「${target.label}」"
                    }.onFailure {
                        message = "合并失败：${it.message ?: "未知错误"}"
                    }
                }
            },
            onDismiss = { showMergeDialog = false },
        )
    }

    if (showRediarizeDialog && audioFile != null) {
        RediarizeDialog(
            initialThreshold = settings.diarizationThreshold.coerceIn(0.30f, 0.80f),
            onConfirm = { expectedCount, threshold ->
                showRediarizeDialog = false
                rediarizing = true
                message = "正在对整段录音进行去噪归一化与全局说话人聚类…"
                scope.launch {
                    val result = OfflineSessionRediarizer.rediarize(
                        audioFile = audioFile,
                        segments = segments,
                        modelManager = app.modelManager,
                        expectedSpeakers = expectedCount,
                        similarityThreshold = threshold,
                    )
                    result.onSuccess { rediarized ->
                        app.transcriptionRepository.replaceSessionSpeakers(
                            sessionId = sessionId,
                            segmentSpeakerLabels = rediarized.segmentToSpeakerLabel,
                        )
                        message = "整段重新识别完成，共区分 ${rediarized.speakerCount} 位说话人"
                    }.onFailure { err ->
                        message = "重新识别失败：${err.message ?: "未知错误"}"
                    }
                    rediarizing = false
                }
            },
            onDismiss = { showRediarizeDialog = false },
        )
    }

    editingSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text("编辑字幕 (${formatClock(segment.startMs)})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = segmentTextDraft,
                        onValueChange = { segmentTextDraft = it },
                        label = { Text("转录内容（可修改错别字）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (speakers.isNotEmpty()) {
                        Text(
                            text = "归属说话人：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            speakers.forEach { sp ->
                                FilterChip(
                                    selected = segmentSpeakerDraft == sp.id,
                                    onClick = { segmentSpeakerDraft = sp.id },
                                    label = { Text(sp.label) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = segmentTextDraft.trim()
                        val newSpeakerId = segmentSpeakerDraft
                        editingSegment = null
                        scope.launch {
                            runCatching {
                                if (text.isNotEmpty() && text != segment.text) {
                                    app.transcriptionRepository.updateSegmentText(segment.id, text)
                                }
                                if (newSpeakerId != segment.speakerId) {
                                    app.transcriptionRepository.assignSpeaker(segment.id, newSpeakerId)
                                }
                            }.onSuccess {
                                message = "已更新字幕"
                            }.onFailure {
                                message = "更新失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSegment = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MergeSpeakersDialog(
    speakers: List<SpeakerEntity>,
    onMerge: (source: SpeakerEntity, target: SpeakerEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var sourceId by remember(speakers) { mutableStateOf(speakers.last().id) }
    var targetId by remember(speakers) { mutableStateOf(speakers.first().id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("合并说话人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "选择要合并的来源说话人：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    speakers.forEach { sp ->
                        FilterChip(
                            selected = sourceId == sp.id,
                            onClick = {
                                sourceId = sp.id
                                if (targetId == sp.id) {
                                    targetId = speakers.first { it.id != sp.id }.id
                                }
                            },
                            label = { Text(sp.label) },
                        )
                    }
                }
                Text(
                    text = "合并到目标说话人：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    speakers.filter { it.id != sourceId }.forEach { sp ->
                        FilterChip(
                            selected = targetId == sp.id,
                            onClick = { targetId = sp.id },
                            label = { Text(sp.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val src = speakers.firstOrNull { it.id == sourceId }
                    val dst = speakers.firstOrNull { it.id == targetId }
                    if (src != null && dst != null && src.id != dst.id) {
                        onMerge(src, dst)
                    }
                },
            ) {
                Text("确认合并")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RediarizeDialog(
    initialThreshold: Float,
    onConfirm: (expectedSpeakers: Int?, threshold: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val countOptions = listOf<Pair<Int?, String>>(
        null to "自动",
        2 to "2 人",
        3 to "3 人",
        4 to "4 人",
        5 to "5 人",
    )
    var selectedCount by remember { mutableStateOf<Int?>(null) }
    var threshold by remember { mutableFloatStateOf(initialThreshold) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整段重新识别说话人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "将对整段原始录音做全局去噪归一化与声纹均值去偏，再进行全局层次聚类，有效消除不同片段背景噪声导致的同一人被拆分的现象。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "预期说话人数：",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    countOptions.forEach { (count, label) ->
                        FilterChip(
                            selected = selectedCount == count,
                            onClick = { selectedCount = count },
                            label = { Text(label) },
                        )
                    }
                }
                if (selectedCount == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "自动聚类合并阈值（越低越容易合并）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", threshold),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        valueRange = 0.25f..0.80f,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedCount, threshold) }) {
                Text("开始重识")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SegmentRow(
    segment: SegmentEntity,
    speakerLabel: String?,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = formatClock(segment.startMs),
                modifier = Modifier
                    .width(46.dp)
                    .padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val speaker = speakerLabel?.takeIf { it.isNotBlank() }
                if (speaker != null) {
                    SpeakerBadge(speaker)
                }
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑字幕",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
