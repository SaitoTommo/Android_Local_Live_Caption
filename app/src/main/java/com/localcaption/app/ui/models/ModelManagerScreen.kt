package com.localcaption.app.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcaption.app.asr.ModelSpec
import com.localcaption.app.asr.ModelTaskPhase
import com.localcaption.app.asr.ModelTaskState
import com.localcaption.app.asr.SherpaOnnxBridge
import com.localcaption.app.ui.common.MetaChip
import com.localcaption.app.ui.common.formatBytes
import com.localcaption.app.ui.common.rememberAppContainer

/**
 * Model manager: download/import/delete the four model bundles and inspect whether the
 * native sherpa-onnx AAR is available.
 *
 * All task state lives in [com.localcaption.app.asr.ModelManager], so switching tabs no
 * longer loses an in-flight download or resets its progress.
 */
@Composable
fun ModelManagerScreen() {
    val app = rememberAppContainer()

    val specs = remember { app.modelManager.specs() }
    val managerState by app.modelManager.state.collectAsStateWithLifecycle()

    var checkKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checkKey++
        app.modelManager.refreshInstalled()
    }

    val nativeStatus = remember(checkKey) {
        NativeStatus(
            asr = SherpaOnnxBridge.isAsrAvailable,
            vad = SherpaOnnxBridge.isVadAvailable,
            speaker = SherpaOnnxBridge.isSpeakerEmbeddingAvailable,
        )
    }

    var importTarget by remember { mutableStateOf<ModelSpec?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val spec = importTarget
        importTarget = null
        if (uri == null || spec == null) return@rememberLauncherForActivityResult
        app.modelManager.startImport(spec, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "模型管理",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        managerState.lastMessage?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "native-status") {
                NativeStatusCard(nativeStatus)
            }

            items(items = specs, key = { it.id }) { spec ->
                ModelCard(
                    spec = spec,
                    installed = spec.id in managerState.installedIds,
                    task = managerState.activeTasks[spec.id],
                    onDownload = { app.modelManager.startDownload(spec) },
                    onImport = {
                        importTarget = spec
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    onCancel = { app.modelManager.cancelTask(spec.id) },
                    onDelete = { app.modelManager.deleteModel(spec) },
                )
            }

            item(key = "footer") {
                Text(
                    text = "提示：模型保存在应用私有目录，卸载应用会一并删除；" +
                        "下载服务器为 GitHub Releases，国内网络可能需要代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

private data class NativeStatus(
    val asr: Boolean,
    val vad: Boolean,
    val speaker: Boolean,
)

@Composable
private fun NativeStatusCard(status: NativeStatus) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "原生 sherpa-onnx",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            NativeStatusRow("离线识别 (OfflineRecognizer)", status.asr)
            NativeStatusRow("语音活动检测 (Silero VAD)", status.vad)
            NativeStatusRow("说话人嵌入 (SpeakerEmbedding)", status.speaker)
            Text(
                text = if (status.asr) {
                    "原生库已加载，可以开始识别。"
                } else {
                    "未检测到 AAR：将 sherpa-onnx-*.aar 放入 app/libs/ 后重新 Sync / 构建，" +
                        "识别与说话人分离能力才会启用。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NativeStatusRow(label: String, available: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (available) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "$label：${if (available) "可用" else "不可用"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chinese label of a [ModelTaskPhase]. */
private fun ModelTaskPhase.label(): String = when (this) {
    ModelTaskPhase.DOWNLOADING -> "下载中"
    ModelTaskPhase.EXTRACTING -> "正在解压"
    ModelTaskPhase.IMPORTING -> "正在导入"
    ModelTaskPhase.VERIFYING -> "正在校验文件"
}

/** Status line shown under the progress bar. */
private fun ModelTaskState.statusText(): String {
    val label = phase.label()
    return when {
        phase == ModelTaskPhase.VERIFYING -> "$label…"
        totalBytes > 0L -> {
            val percent = (progress * 100).toInt()
            val readText = if (bytesRead > 0L) formatBytes(bytesRead) else "0 MB"
            "$label $percent%（$readText / ${formatBytes(totalBytes)}）"
        }

        bytesRead > 0L -> "$label ${formatBytes(bytesRead)}"
        else -> label
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    installed: Boolean,
    task: ModelTaskState?,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = task != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spec.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = spec.requiredFiles.joinToString("、").ifBlank { spec.fileName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetaChip(
                    text = when {
                        busy -> "处理中"
                        installed -> "已安装"
                        else -> "未安装"
                    },
                    containerColor = if (installed) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (installed) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(text = formatBytes(spec.approxSizeBytes))
                MetaChip(
                    text = if (spec.isAsrModel) "ASR 模型" else "辅助模型",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            if (task != null) {
                if (task.isDeterminate) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = task.statusText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onDownload, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (installed) "重新下载" else "下载",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TextButton(onClick = onImport, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "本地导入", modifier = Modifier.padding(start = 6.dp))
                }
                if (busy) {
                    TextButton(onClick = onCancel) {
                        Text(text = "取消", color = MaterialTheme.colorScheme.error)
                    }
                } else if (installed) {
                    TextButton(onClick = onDelete) {
                        Text(text = "删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
