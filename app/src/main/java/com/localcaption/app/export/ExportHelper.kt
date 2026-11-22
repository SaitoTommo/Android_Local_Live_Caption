package com.localcaption.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android side of the export flow.
 *
 * Nothing here touches the database: callers format a transcript with
 * [formatSrt] / [formatTxt] and then either persist it to a user picked document
 * ([writeToUri]) or hand it to the share sheet ([createShareIntent]).
 */
object ExportHelper {

    /** MIME type of SubRip subtitle files. */
    const val MIME_SRT: String = "application/x-subrip"

    /** MIME type of plain text transcripts. */
    const val MIME_TXT: String = "text/plain"

    /** Directory below `cacheDir` that receives files shared through [createShareIntent]. */
    const val EXPORT_DIR_NAME: String = "exports"

    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val DEFAULT_FILE_NAME = "caption"
    private const val MAX_FILE_NAME_LENGTH = 96
    private const val MAX_TITLE_STEM_LENGTH = 64
    private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

    private val ILLEGAL_FILE_NAME_CHARS = charArrayOf(
        '\\', '/', ':', '*', '?', '"', '<', '>', '|',
    )

    /** Authority declared by the `FileProvider` entry of the manifest. */
    fun fileProviderAuthority(context: Context): String =
        context.packageName + FILE_PROVIDER_SUFFIX

    /** Directory that holds the cache files backing [createShareIntent]. */
    fun exportDir(context: Context): File =
        File(context.cacheDir, EXPORT_DIR_NAME).apply { mkdirs() }

    /**
     * Writes [content] as UTF-8 into the document behind [uri], truncating it first.
     *
     * This performs blocking IO and must be called from a background dispatcher.
     *
     * @return success, or the failure that prevented the write
     */
    fun writeToUri(context: Context, uri: Uri, content: String): Result<Unit> = runCatching {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val stream = openOutputStream(context, uri)
            ?: throw IOException("Cannot open an output stream for $uri")
        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Writes [content] into `cacheDir/exports/[fileName]` and builds an `ACTION_SEND`
     * intent that shares it through the app's `FileProvider`.
     *
     * The file is stored in the cache so it is cleaned up by the system; the returned
     * intent already carries the read grant and a `ClipData` entry, so receivers on
     * every supported API level can read it.
     */
    fun createShareIntent(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String = MIME_SRT,
        subject: String? = null,
    ): Intent {
        val file = writeToCache(context, fileName, content)
        val uri = FileProvider.getUriForFile(
            context,
            fileProviderAuthority(context),
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject ?: file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Writes [content] as UTF-8 into `cacheDir/exports/[sanitized fileName]`.
     *
     * This performs blocking IO and must be called from a background dispatcher.
     */
    fun writeToCache(context: Context, fileName: String, content: String): File {
        val directory = exportDir(context)
        val file = File(directory, sanitizeFileName(fileName, fallback = defaultFileName()))
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /**
     * Builds a filesystem safe export name such as
     * `会议记录_20261121_143000.srt`.
     *
     * @param title user visible session title
     * @param extension desired extension, with or without a leading dot
     * @param timestamp moment embedded into the name
     */
    fun buildExportFileName(
        title: String,
        extension: String,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val normalizedExtension = extension
            .trim()
            .trimStart('.')
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .ifBlank { "txt" }
        val stamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(timestamp))
        // Titles may contain path separators; keep both sides instead of treating the
        // text after a separator as a file name.
        val rawBase = title.replace('/', '_').replace('\\', '_')
        val base = sanitizeFileName(rawBase, fallback = DEFAULT_FILE_NAME)
            .take(MAX_TITLE_STEM_LENGTH)
        return sanitizeFileName(
            "${base}_$stamp.$normalizedExtension",
            fallback = "$DEFAULT_FILE_NAME.$normalizedExtension",
        )
    }

    /**
     * Keeps the file name component of [raw] (no directory separators), replaces
     * characters that are illegal on common file systems, removes control characters
     * and limits the result to a sane length.
     */
    fun sanitizeFileName(raw: String, fallback: String = DEFAULT_FILE_NAME): String {
        val nameOnly = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (character in nameOnly) {
                when {
                    character.isISOControl() -> append('_')
                    character in ILLEGAL_FILE_NAME_CHARS -> append('_')
                    else -> append(character)
                }
            }
        }
        val collapsed = cleaned
            .replace(Regex("_{2,}"), "_")
            .trim()
            .trim('.', ' ')
            .take(MAX_FILE_NAME_LENGTH)
            .trim('.', ' ')
        return collapsed.ifBlank { fallback }
    }

    private fun defaultFileName(): String = "$DEFAULT_FILE_NAME.srt"

    private fun openOutputStream(context: Context, uri: Uri) =
        runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
}
