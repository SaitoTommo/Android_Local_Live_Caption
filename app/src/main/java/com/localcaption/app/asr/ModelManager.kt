package com.localcaption.app.asr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.localcaption.app.data.model.AsrModelType
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** How a model archive is packaged for download/import. */
enum class ModelArchiveType {
    /** A single file (for example `silero_vad.onnx`) downloaded as-is. */
    NONE,

    /** A `.tar.bz2` archive (the format used by sherpa-onnx model releases). */
    TAR_BZ2,

    /** A `.zip` archive. */
    ZIP,
}

/**
 * One downloadable model bundle.
 *
 * @property id stable identifier used for preferences/UI
 * @property displayName human readable name
 * @property directoryName directory under `filesDir/models/` that receives the files
 * @property downloadUrl official release URL
 * @property archiveType packaging of [downloadUrl]
 * @property fileName destination file name for [ModelArchiveType.NONE]
 * @property requiredFiles files expected after installation
 * @property approxSizeBytes approximate download size for the UI
 * @property asrModelType ASR enum value for ASR bundles, `null` for helper models
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val directoryName: String,
    val downloadUrl: String,
    val archiveType: ModelArchiveType,
    val fileName: String = "",
    val requiredFiles: List<String> = emptyList(),
    val approxSizeBytes: Long = 0L,
    val asrModelType: AsrModelType? = null,
) {
    val isAsrModel: Boolean
        get() = asrModelType != null
}

/** Download/import progress callback payload. */
data class DownloadProgress(
    val modelId: String,
    val bytesRead: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isIndeterminate: Boolean
        get() = totalBytes <= 0L
}

/**
 * Phase of an in-flight download/import task.
 *
 * The phases are surfaced to the UI so a 880 MB download that is still unpacking never
 * looks "stuck at 100%".
 */
enum class ModelTaskPhase {
    DOWNLOADING,
    EXTRACTING,
    IMPORTING,
    VERIFYING,
}

/**
 * Snapshot of one running model task.
 *
 * @property modelId catalog id the task belongs to
 * @property phase current stage of the task
 * @property progress `0f..1f` progress of [phase], `0f` when the size is unknown
 * @property bytesRead bytes consumed in [phase]
 * @property totalBytes total bytes of [phase], `0` when unknown
 */
data class ModelTaskState(
    val modelId: String,
    val phase: ModelTaskPhase,
    val progress: Float,
    val bytesRead: Long,
    val totalBytes: Long,
) {
    /** `true` when the phase has a meaningful progress ratio. */
    val isDeterminate: Boolean
        get() = totalBytes > 0L && phase != ModelTaskPhase.VERIFYING
}

/**
 * Observable snapshot of the model manager.
 *
 * @property installedIds ids of fully installed bundles
 * @property activeTasks running tasks keyed by model id
 * @property lastMessage last terminal status line for the UI
 */
data class ModelManagerState(
    val installedIds: Set<String> = emptySet(),
    val activeTasks: Map<String, ModelTaskState> = emptyMap(),
    val lastMessage: String? = null,
)

/**
 * Catalog and installer for the offline models used by the transcription pipeline.
 *
 * All models live under `filesDir/models/<directoryName>/`. Downloads are performed
 * with OkHttp (progress reported through [DownloadProgress]) and `.tar.bz2`/`.zip`
 * bundles are unpacked with Apache Commons Compress. Users can also sideload a model
 * from Android's Storage Access Framework with [importFromUri].
 */
class ModelManager(
    context: Context,
    private val httpClient: OkHttpClient = defaultModelHttpClient(),
) {

    private val appContext: Context = context.applicationContext

    /**
     * App-scoped worker. It is intentionally not tied to a screen lifecycle: leaving the
     * model manager tab must not cancel (and reset) an in-flight download.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val taskLock = Any()
    private val taskJobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(ModelManagerState())

    /** Observable installation and task snapshot for the UI. */
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    /** Root directory that holds every installed model. */
    val modelsDir: File = File(appContext.filesDir, MODELS_DIR_NAME).apply { mkdirs() }

    init {
        refreshInstalled()
    }

    // region Catalog

    /** Every known model bundle, including optional helper models. */
    fun specs(): List<ModelSpec> = CATALOG

    /** Looks up a bundle by [id]. */
    fun spec(id: String): ModelSpec? = CATALOG.firstOrNull { it.id == id }

    /** Returns the ASR bundle that belongs to [modelType]. */
    fun specFor(modelType: AsrModelType): ModelSpec = when (modelType) {
        AsrModelType.QWEN3_ASR_0_6B_INT8 -> QWEN3_ASR_0_6B_INT8
        AsrModelType.SENSE_VOICE_SMALL_INT8 -> SENSE_VOICE_SMALL_INT8
        AsrModelType.PARAFORMER_ZH_SMALL_INT8 -> PARAFORMER_ZH_SMALL_INT8
        AsrModelType.WHISPER_TINY_INT8 -> WHISPER_TINY_INT8
    }

    /** Directory of [spec] inside [modelsDir]. */
    fun modelDir(spec: ModelSpec): File = File(modelsDir, spec.directoryName)

    /** Directory of the ASR model backing [modelType]. */
    fun modelDir(modelType: AsrModelType): File = modelDir(specFor(modelType))

    /** `tokens.txt` of the ASR model backing [modelType]. */
    fun tokensFile(modelType: AsrModelType): File = File(modelDir(modelType), "tokens.txt")

    /** The `.onnx` model file of [modelType], if installed. */
    fun modelFile(modelType: AsrModelType): File? = SherpaOnnxBridge.findOnnxModel(modelDir(modelType))

    // endregion

    // region Installed state

    /** `true` when every file for [spec] is present. */
    fun isInstalled(spec: ModelSpec): Boolean {
        val dir = modelDir(spec)
        if (!dir.isDirectory) return false

        if (spec.archiveType == ModelArchiveType.NONE) {
            return File(dir, spec.fileName).isFile
        }

        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return false

        val hasOnnx = files.any { it.name.endsWith(".onnx", ignoreCase = true) }
        val requiredPresent = spec.requiredFiles.all { required ->
            File(dir, required).exists() || files.any { it.name.equals(required, ignoreCase = true) }
        }
        return hasOnnx && requiredPresent
    }

    /** `true` when the ASR model backing [modelType] is installed. */
    fun isInstalled(modelType: AsrModelType): Boolean = isInstalled(specFor(modelType))

    /** Installed bundles, in catalog order. */
    fun installedSpecs(): List<ModelSpec> = CATALOG.filter { isInstalled(it) }

    /** Silero VAD model file, if installed. */
    fun sileroVadFile(): File? {
        val directory = modelDir(SILERO_VAD)
        return File(directory, SILERO_VAD_FILE).takeIf { it.isFile }
            ?: SherpaOnnxBridge.findOnnxModel(directory)
    }

    /** 3D-Speaker embedding model file, if installed. */
    fun speakerEmbeddingFile(): File? {
        val directory = modelDir(SPEAKER_EMBEDDING_3DSPEAKER)
        return File(directory, SPEAKER_EMBEDDING_FILE).takeIf { it.isFile }
            ?: SherpaOnnxBridge.findOnnxModel(directory)
    }

    /** Deletes every file of [spec]. */
    fun delete(spec: ModelSpec): Boolean = runCatching {
        modelDir(spec).deleteRecursively()
    }.getOrDefault(false)

    // endregion

    // region Task API

    /**
     * Starts downloading [spec] on the app-scoped [scope].
     *
     * The task survives tab switches and is observable through [state]. Starting a task
     * for a model that already has one cancels the previous task first.
     */
    fun startDownload(spec: ModelSpec) {
        launchTask(spec) { reporter ->
            downloadInternal(spec, reporter)
        }
    }

    /** Starts importing a user-selected archive/model file into the app storage. */
    fun startImport(spec: ModelSpec, uri: Uri) {
        launchTask(spec) { reporter ->
            importInternal(spec, uri, reporter)
        }
    }

    /** Cancels the running task of [modelId], if any. */
    fun cancelTask(modelId: String) {
        val job = synchronized(taskLock) { taskJobs.remove(modelId) }
        job?.cancel()
        _state.update { current ->
            current.copy(activeTasks = current.activeTasks - modelId)
        }
    }

    /** Deletes every file of [spec] and refreshes [state]. */
    fun deleteModel(spec: ModelSpec) {
        cancelTask(spec.id)
        scope.launch {
            val deleted = delete(spec)
            refreshInstalled()
            publishMessage(
                if (deleted) "已删除 ${spec.displayName}" else "删除 ${spec.displayName} 失败",
            )
        }
    }

    /** Recomputes [ModelManagerState.installedIds] from disk. */
    fun refreshInstalled() {
        val installed = CATALOG.filter { isInstalled(it) }.map { it.id }.toSet()
        _state.update { current -> current.copy(installedIds = installed) }
    }

    // endregion

    // region Download / import

    /**
     * Downloads [spec] into [modelsDir] and unpacks it when necessary.
     *
     * Low-level variant kept for callers that want to await the result; the UI uses
     * [startDownload]. Runs on [Dispatchers.IO]; [onProgress] is invoked from there.
     */
    suspend fun download(
        spec: ModelSpec,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            downloadInternal(spec, TaskReporter(spec.id, onProgress))
        }
    }

    /**
     * Imports a user-selected model archive or single model file from a SAF [uri].
     *
     * Supported inputs: `.tar.bz2`, `.zip` and single `.onnx` files. Low-level variant;
     * the UI uses [startImport].
     */
    suspend fun importFromUri(
        spec: ModelSpec,
        uri: Uri,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            importInternal(spec, uri, TaskReporter(spec.id, onProgress))
        }
    }

    private fun launchTask(spec: ModelSpec, block: suspend (TaskReporter) -> Unit) {
        synchronized(taskLock) {
            taskJobs.remove(spec.id)?.cancel()
        }
        _state.update { current ->
            current.copy(
                activeTasks = current.activeTasks - spec.id,
                lastMessage = null,
            )
        }
        val reporter = TaskReporter(spec.id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(reporter)
                publishMessage("已安装 ${spec.displayName}")
            } catch (cancelled: CancellationException) {
                publishMessage("已取消 ${spec.displayName}")
                throw cancelled
            } catch (throwable: Throwable) {
                Log.w(TAG, "Model task for ${spec.id} failed", throwable)
                publishMessage(
                    "${spec.displayName} 处理失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
            } finally {
                val self = coroutineContext[Job]
                synchronized(taskLock) {
                    if (taskJobs[spec.id] == self) taskJobs.remove(spec.id)
                }
                _state.update { current ->
                    current.copy(activeTasks = current.activeTasks - spec.id)
                }
                refreshInstalled()
            }
        }
        synchronized(taskLock) { taskJobs[spec.id] = job }
        job.start()
    }

    private suspend fun downloadInternal(spec: ModelSpec, reporter: TaskReporter): File {
        val tempDir = File(modelsDir, ".download-${spec.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val archiveFile = File(tempDir, archiveFileName(spec))
            val request = Request.Builder()
                .url(spec.downloadUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} while downloading ${spec.displayName}")
                }
                val body = response.body ?: error("Empty response for ${spec.displayName}")
                val totalBytes = body.contentLength()
                reporter.report(ModelTaskPhase.DOWNLOADING, 0L, totalBytes, force = true)
                body.byteStream().use { input ->
                    FileOutputStream(archiveFile).use { output ->
                        copyWithProgress(input, output) { readTotal ->
                            reporter.report(ModelTaskPhase.DOWNLOADING, readTotal, totalBytes)
                        }
                    }
                }
            }

            install(spec, archiveFile, reporter)
            verifyInstalled(spec, reporter)
            return modelDir(spec)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun importInternal(
        spec: ModelSpec,
        uri: Uri,
        reporter: TaskReporter,
    ): File {
        val displayName = queryDisplayName(uri) ?: "import-${spec.id}"
        val tempDir = File(modelsDir, ".import-${spec.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val tempFile = File(tempDir, sanitizeFileName(displayName))
            val totalBytes = querySize(uri)

            reporter.report(ModelTaskPhase.IMPORTING, 0L, totalBytes, force = true)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyWithProgress(input, output) { readTotal ->
                        reporter.report(ModelTaskPhase.IMPORTING, readTotal, totalBytes)
                    }
                }
            } ?: error("Cannot open $uri")

            val effectiveSpec = adaptSpecToFile(spec, tempFile)
            install(effectiveSpec, tempFile, reporter)
            verifyInstalled(effectiveSpec, reporter)
            return modelDir(effectiveSpec)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun verifyInstalled(spec: ModelSpec, reporter: TaskReporter) {
        reporter.verifying()
        currentCoroutineContext().ensureActive()
        check(isInstalled(spec)) {
            "Model files for ${spec.displayName} are incomplete after installation"
        }
    }

    private fun publishMessage(message: String) {
        _state.update { current -> current.copy(lastMessage = message) }
    }

    /**
     * Publishes task state to [state] and, for the low-level entry points, forwards
     * download progress to their callback. Progress updates are throttled so a
     * multi-gigabyte extraction does not flood the Compose UI.
     */
    private inner class TaskReporter(
        private val modelId: String,
        private val legacyProgress: ((DownloadProgress) -> Unit)? = null,
    ) {
        private var lastPhase: ModelTaskPhase? = null
        private var lastBytes = -1L
        private var lastTotalBytes = 0L

        fun report(
            phase: ModelTaskPhase,
            bytesRead: Long,
            totalBytes: Long,
            force: Boolean = false,
        ) {
            lastTotalBytes = totalBytes
            val stepping = phase != lastPhase || bytesRead - lastBytes >= PROGRESS_STEP_BYTES
            if (!force && !stepping) return
            lastPhase = phase
            lastBytes = bytesRead

            val fraction = if (totalBytes > 0L) {
                (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val task = ModelTaskState(
                modelId = modelId,
                phase = phase,
                progress = fraction,
                bytesRead = bytesRead,
                totalBytes = totalBytes,
            )
            _state.update { current ->
                current.copy(activeTasks = current.activeTasks + (modelId to task))
            }
            if (phase == ModelTaskPhase.DOWNLOADING || phase == ModelTaskPhase.IMPORTING) {
                legacyProgress?.invoke(DownloadProgress(modelId, bytesRead, totalBytes))
            }
        }

        fun verifying() {
            report(ModelTaskPhase.VERIFYING, lastTotalBytes, lastTotalBytes, force = true)
        }
    }

    // endregion

    // region Installation internals

    private suspend fun install(spec: ModelSpec, archiveFile: File, reporter: TaskReporter) {
        val targetDir = modelDir(spec).apply { mkdirs() }
        when (spec.archiveType) {
            ModelArchiveType.NONE -> {
                val totalBytes = archiveFile.length()
                reporter.report(ModelTaskPhase.IMPORTING, 0L, totalBytes, force = true)
                val destinationName = spec.fileName.ifBlank { archiveFile.name }
                val destination = File(targetDir, destinationName)
                if (destination.absolutePath != archiveFile.absolutePath) {
                    archiveFile.copyTo(destination, overwrite = true)
                }
                reporter.report(
                    ModelTaskPhase.IMPORTING,
                    totalBytes,
                    totalBytes,
                    force = true,
                )
            }

            ModelArchiveType.TAR_BZ2, ModelArchiveType.ZIP -> {
                val staging = File(modelsDir, ".extract-${spec.id}").apply {
                    deleteRecursively()
                    mkdirs()
                }
                try {
                    extractArchive(archiveFile, staging, spec.archiveType, reporter)
                    promoteExtracted(staging, targetDir, spec)
                } finally {
                    staging.deleteRecursively()
                }
                reporter.report(
                    ModelTaskPhase.EXTRACTING,
                    archiveFile.length(),
                    archiveFile.length(),
                    force = true,
                )
            }
        }
    }

    private suspend fun extractArchive(
        archive: File,
        destination: File,
        type: ModelArchiveType,
        reporter: TaskReporter,
    ) {
        val archiveLength = archive.length()
        reporter.report(ModelTaskPhase.EXTRACTING, 0L, archiveLength, force = true)
        when (type) {
            ModelArchiveType.TAR_BZ2 -> {
                val countingInput = CountingInputStream(
                    BufferedInputStream(FileInputStream(archive)),
                ) { readTotal ->
                    reporter.report(ModelTaskPhase.EXTRACTING, readTotal, archiveLength)
                }
                BZip2CompressorInputStream(countingInput, true).use { compressed ->
                    TarArchiveInputStream(compressed).use { tar ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) {
                                safeChild(destination, entry.name)?.mkdirs()
                                continue
                            }
                            if (!entry.isFile) continue
                            val outputFile = safeChild(destination, entry.name) ?: continue
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { output -> tar.copyTo(output) }
                        }
                    }
                }
            }

            ModelArchiveType.ZIP -> {
                val countingInput = CountingInputStream(
                    BufferedInputStream(FileInputStream(archive)),
                ) { readTotal ->
                    reporter.report(ModelTaskPhase.EXTRACTING, readTotal, archiveLength)
                }
                ZipInputStream(countingInput).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            safeChild(destination, entry.name)?.mkdirs()
                            continue
                        }
                        val outputFile = safeChild(destination, entry.name) ?: continue
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output -> zip.copyTo(output) }
                    }
                }
            }

            ModelArchiveType.NONE -> Unit
        }
    }

    /**
     * Copies the directory that contains the model payload out of [staging]. Model
     * archives usually wrap everything in a top-level folder whose name differs from
     * the catalog directory name, so the payload is located by its files instead.
     */
    private suspend fun promoteExtracted(staging: File, targetDir: File, spec: ModelSpec) {
        val sourceDir = findModelRoot(staging, spec) ?: staging
        sourceDir.listFiles()?.forEach { child ->
            currentCoroutineContext().ensureActive()
            val destination = File(targetDir, child.name)
            if (child.isDirectory) {
                child.copyRecursively(destination, overwrite = true)
            } else {
                child.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun findModelRoot(root: File, spec: ModelSpec): File? {
        val queue = ArrayDeque<File>()
        queue.addLast(root)
        var fallback: File? = null

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            val children = directory.listFiles()?.toList().orEmpty()
            val hasOnnx = children.any { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
            if (hasOnnx) {
                val containsRequired = spec.requiredFiles.all { required ->
                    children.any { it.name.equals(required, ignoreCase = true) }
                }
                if (containsRequired) return directory
                if (fallback == null) fallback = directory
            }
            children.filter { it.isDirectory }.forEach { queue.addLast(it) }
        }
        return fallback
    }

    private fun safeChild(root: File, entryName: String): File? {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            val candidate = File(root, cleaned).canonicalFile
            if (candidate.toPath().startsWith(rootPath)) candidate else null
        }.getOrNull()
    }

    private fun adaptSpecToFile(spec: ModelSpec, file: File): ModelSpec {
        val detected = detectArchiveType(file.name)
        return when {
            detected == ModelArchiveType.NONE &&
                spec.archiveType != ModelArchiveType.NONE &&
                file.name.endsWith(".onnx", ignoreCase = true) ->
                spec.copy(archiveType = ModelArchiveType.NONE, fileName = file.name)

            detected != ModelArchiveType.NONE && spec.archiveType == ModelArchiveType.NONE ->
                spec.copy(archiveType = detected)

            else -> spec
        }
    }

    private fun archiveFileName(spec: ModelSpec): String {
        val fromUrl = spec.downloadUrl.substringAfterLast('/').substringBefore('?')
        return fromUrl.ifBlank { "${spec.id}.bin" }
    }

    private fun detectArchiveType(fileName: String): ModelArchiveType {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ->
                ModelArchiveType.TAR_BZ2

            lower.endsWith(".zip") -> ModelArchiveType.ZIP
            else -> ModelArchiveType.NONE
        }
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        onBytes: (Long) -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var readTotal = 0L
        var lastReported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            readTotal += read
            if (readTotal - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = readTotal
                onBytes(readTotal)
            }
        }
        onBytes(readTotal)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use -1L
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
            } ?: -1L
    }.getOrDefault(-1L)

    private fun sanitizeFileName(name: String): String =
        name.substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "model.bin" }

    // endregion

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR_NAME = "models"
        private const val USER_AGENT = "LocalLiveCaption/1.0 (Android)"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024L

        const val ID_QWEN3_ASR_0_6B_INT8 = "qwen3_asr_0_6b_int8"
        const val ID_SENSE_VOICE_SMALL_INT8 = "sense_voice_small_int8"
        const val ID_PARAFORMER_ZH_SMALL_INT8 = "paraformer_zh_small_int8"
        const val ID_WHISPER_TINY_INT8 = "whisper_tiny_int8"
        const val ID_SILERO_VAD = "silero_vad"
        const val ID_SPEAKER_EMBEDDING_3DSPEAKER = "speaker_embedding_3dspeaker"

        private const val SILERO_VAD_FILE = "silero_vad.onnx"
        private const val SPEAKER_EMBEDDING_FILE =
            "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"

        /** Qwen3-ASR 0.6B, INT8 quantized. */
        val QWEN3_ASR_0_6B_INT8: ModelSpec = ModelSpec(
            id = ID_QWEN3_ASR_0_6B_INT8,
            displayName = AsrModelType.QWEN3_ASR_0_6B_INT8.displayName,
            directoryName = AsrModelType.QWEN3_ASR_0_6B_INT8.modelDirName,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            archiveType = ModelArchiveType.TAR_BZ2,
            requiredFiles = listOf(
                "conv_frontend.onnx",
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "tokenizer",
            ),
            approxSizeBytes = 880_000_000L,
            asrModelType = AsrModelType.QWEN3_ASR_0_6B_INT8,
        )

        /** SenseVoice Small, INT8 quantized. */
        val SENSE_VOICE_SMALL_INT8: ModelSpec = ModelSpec(
            id = ID_SENSE_VOICE_SMALL_INT8,
            displayName = AsrModelType.SENSE_VOICE_SMALL_INT8.displayName,
            directoryName = AsrModelType.SENSE_VOICE_SMALL_INT8.modelDirName,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            archiveType = ModelArchiveType.TAR_BZ2,
            requiredFiles = listOf("tokens.txt"),
            approxSizeBytes = 250_000_000L,
            asrModelType = AsrModelType.SENSE_VOICE_SMALL_INT8,
        )

        /** Paraformer-zh Small, ultra-fast lightweight Chinese+English INT8 model. */
        val PARAFORMER_ZH_SMALL_INT8: ModelSpec = ModelSpec(
            id = ID_PARAFORMER_ZH_SMALL_INT8,
            displayName = AsrModelType.PARAFORMER_ZH_SMALL_INT8.displayName,
            directoryName = AsrModelType.PARAFORMER_ZH_SMALL_INT8.modelDirName,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
            archiveType = ModelArchiveType.TAR_BZ2,
            requiredFiles = listOf("tokens.txt"),
            approxSizeBytes = 80_000_000L,
            asrModelType = AsrModelType.PARAFORMER_ZH_SMALL_INT8,
        )

        /** Whisper Tiny, lightweight multilingual model (great for English videos). */
        val WHISPER_TINY_INT8: ModelSpec = ModelSpec(
            id = ID_WHISPER_TINY_INT8,
            displayName = AsrModelType.WHISPER_TINY_INT8.displayName,
            directoryName = AsrModelType.WHISPER_TINY_INT8.modelDirName,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-whisper-tiny.tar.bz2",
            archiveType = ModelArchiveType.TAR_BZ2,
            requiredFiles = listOf("tiny-tokens.txt"),
            approxSizeBytes = 115_000_000L,
            asrModelType = AsrModelType.WHISPER_TINY_INT8,
        )

        /** Silero VAD used to split the stream into speech segments. */
        val SILERO_VAD: ModelSpec = ModelSpec(
            id = ID_SILERO_VAD,
            displayName = "Silero VAD",
            directoryName = "silero-vad",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                SILERO_VAD_FILE,
            archiveType = ModelArchiveType.NONE,
            fileName = SILERO_VAD_FILE,
            requiredFiles = listOf(SILERO_VAD_FILE),
            approxSizeBytes = 2_200_000L,
        )

        /** 3D-Speaker ERES2Net speaker embedding model. */
        val SPEAKER_EMBEDDING_3DSPEAKER: ModelSpec = ModelSpec(
            id = ID_SPEAKER_EMBEDDING_3DSPEAKER,
            displayName = "3D-Speaker (ERES2Net)",
            directoryName = "speaker-embedding-3dspeaker",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "speaker-recongition-models/$SPEAKER_EMBEDDING_FILE",
            archiveType = ModelArchiveType.NONE,
            fileName = SPEAKER_EMBEDDING_FILE,
            requiredFiles = listOf(SPEAKER_EMBEDDING_FILE),
            approxSizeBytes = 40_000_000L,
        )

        /** Full catalog in display order. */
        val CATALOG: List<ModelSpec> = listOf(
            QWEN3_ASR_0_6B_INT8,
            SENSE_VOICE_SMALL_INT8,
            PARAFORMER_ZH_SMALL_INT8,
            WHISPER_TINY_INT8,
            SILERO_VAD,
            SPEAKER_EMBEDDING_3DSPEAKER,
        )
    }
}

private fun defaultModelHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Minimal counting stream used to report real extraction progress.
 *
 * Wrapping the raw archive stream (instead of guessing from the output size) means
 * `.tar.bz2`/`.zip` extraction shows genuine `bytesRead / archive.length()` progress.
 */
private class CountingInputStream(
    private val delegate: InputStream,
    private val onBytesRead: (Long) -> Unit,
) : InputStream() {

    private var bytesRead = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            bytesRead += 1
            onBytesRead(bytesRead)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (read > 0) {
            bytesRead += read
            onBytesRead(bytesRead)
        }
        return read
    }

    override fun skip(count: Long): Long {
        val skipped = delegate.skip(count)
        if (skipped > 0) {
            bytesRead += skipped
            onBytesRead(bytesRead)
        }
        return skipped
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }
}
