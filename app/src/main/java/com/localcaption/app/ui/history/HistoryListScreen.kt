package com.localcaption.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.data.db.SessionEntity
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.audioSourceLabel
import com.localcaption.app.ui.common.formatDuration
import com.localcaption.app.ui.common.formatSessionDate
import com.localcaption.app.ui.common.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * Searchable list of recorded sessions with single delete and batch multi-select delete
 * (entered via the top-right "批量管理" button or by long-pressing any session card).
 */
@Composable
fun HistoryListScreen(onOpenSession: (Long) -> Unit) {
    val app = rememberAppContainer()
    val scope = rememberCoroutineScope()

    val sessions by app.transcriptionRepository.observeSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val filtered = remember(sessions, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.title.contains(needle, ignoreCase = true) }
        }
    }

    val filteredIds = remember(filtered) { filtered.map { it.id }.toSet() }
    val allFilteredSelected = filtered.isNotEmpty() && filteredIds.all { it in selectedIds }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelect(sessionId: Long) {
        selectedIds = if (sessionId in selectedIds) {
            selectedIds - sessionId
        } else {
            selectedIds + sessionId
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = { exitSelectionMode() }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "取消选择")
                }
                Text(
                    text = "已选 ${selectedIds.size} 项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selectedIds = if (allFilteredSelected) {
                            selectedIds - filteredIds
                        } else {
                            selectedIds + filteredIds
                        }
                    },
                    enabled = filtered.isNotEmpty(),
                ) {
                    Text(if (allFilteredSelected) "取消全选" else "全选")
                }
                Button(
                    onClick = { showBatchDeleteConfirm = true },
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("删除")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "历史记录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (sessions.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selectionMode = true
                            selectedIds = emptySet()
                        },
                    ) {
                        Text("批量管理")
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索标题…") },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            shape = RoundedCornerShape(16.dp),
        )

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            sessions.isEmpty() -> EmptyHistory("还没有字幕记录，去「实时字幕」页开始一次录制吧")
            filtered.isEmpty() -> EmptyHistory("没有匹配「${query.trim()}」的记录")
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selectionMode = selectionMode,
                        selected = session.id in selectedIds,
                        onClick = {
                            if (selectionMode) {
                                toggleSelect(session.id)
                            } else {
                                onOpenSession(session.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedIds = setOf(session.id)
                            } else {
                                toggleSelect(session.id)
                            }
                        },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = {
                Text("「${session.title}」及其全部字幕、说话人信息和录音文件都会被删除，且无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                app.transcriptionRepository.deleteSession(session.id)
                            }.onSuccess {
                                message = "已删除「${session.title}」"
                            }.onFailure {
                                message = "删除失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showBatchDeleteConfirm && selectedIds.isNotEmpty()) {
        val idsToDelete = selectedIds.toList()
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除 ${idsToDelete.size} 条记录？") },
            text = {
                Text("选中的 ${idsToDelete.size} 条转录记录及其字幕、说话人信息和原始录音文件都会被彻底删除，且无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirm = false
                        exitSelectionMode()
                        scope.launch {
                            runCatching {
                                idsToDelete.forEach { id ->
                                    app.transcriptionRepository.deleteSession(id)
                                }
                            }.onSuccess {
                                message = "已批量删除 ${idsToDelete.size} 条记录"
                            }.onFailure {
                                message = "批量删除失败：${it.message ?: "未知错误"}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ColumnScope.EmptyHistory(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val durationMs = session.endedAt?.let { it - session.startedAt }
        ?: (System.currentTimeMillis() - session.startedAt)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (selectionMode) 2.dp else 4.dp),
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSessionDate(session.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!selectionMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(
                    start = if (selectionMode) 48.dp else 4.dp,
                    end = 10.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    text = audioSourceLabel(session.audioSource),
                    icon = Icons.Filled.Mic,
                )
                MetaChip(
                    text = session.asrModel.displayName,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                MetaChip(
                    text = formatDuration(durationMs),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
