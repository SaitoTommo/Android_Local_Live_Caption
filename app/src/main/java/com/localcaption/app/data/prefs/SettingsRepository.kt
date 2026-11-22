package com.localcaption.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

/**
 * Reads and writes [AppSettings] from a Preferences DataStore.
 */
class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> =
        context.applicationContext.appSettingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences -> preferences.toSettings() }

    /** Applies every field of [settings] in a single transaction. */
    suspend fun update(settings: AppSettings) {
        val sanitized = settings.sanitized()
        dataStore.edit { preferences ->
            preferences[Keys.AUDIO_SOURCE] = sanitized.audioSource.name
            preferences[Keys.ASR_MODEL] = sanitized.asrModel.name
            preferences[Keys.LANGUAGE] = sanitized.language.ifBlank { AppSettings.LANGUAGE_AUTO }
            preferences[Keys.SHOW_TIMESTAMPS] = sanitized.showTimestamps
            preferences[Keys.FONT_SIZE_SP] = sanitized.fontSizeSp
            preferences[Keys.NUM_THREADS] = sanitized.numThreads
            preferences[Keys.EXECUTION_PROVIDER] = sanitized.executionProvider
            preferences[Keys.ENABLE_DIARIZATION] = sanitized.enableDiarization
            preferences[Keys.DIARIZATION_THRESHOLD] = sanitized.diarizationThreshold
            preferences[Keys.VAD_SILENCE_MS] = sanitized.vadSilenceMs
            preferences[Keys.MAX_SEGMENT_DURATION_MS] = sanitized.maxSegmentDurationMs
            preferences[Keys.OVERLAY_BG_ALPHA] = sanitized.overlayBgAlpha
            preferences[Keys.OVERLAY_MAX_LINES] = sanitized.overlayMaxLines
            preferences[Keys.EXPORT_INCLUDE_SPEAKER] = sanitized.exportIncludeSpeaker
            preferences[Keys.EXPORT_INCLUDE_TIMESTAMPS] = sanitized.exportIncludeTimestamps
        }
    }

    suspend fun updateAudioSource(audioSource: AudioSourceType) {
        dataStore.edit { it[Keys.AUDIO_SOURCE] = audioSource.name }
    }

    suspend fun updateAsrModel(asrModel: AsrModelType) {
        dataStore.edit { it[Keys.ASR_MODEL] = asrModel.name }
    }

    suspend fun updateLanguage(language: String) {
        dataStore.edit {
            it[Keys.LANGUAGE] = language.ifBlank { AppSettings.LANGUAGE_AUTO }
        }
    }

    suspend fun updateShowTimestamps(showTimestamps: Boolean) {
        dataStore.edit { it[Keys.SHOW_TIMESTAMPS] = showTimestamps }
    }

    suspend fun updateFontSizeSp(fontSizeSp: Float) {
        dataStore.edit { it[Keys.FONT_SIZE_SP] = fontSizeSp.coerceIn(10f, 96f) }
    }

    suspend fun updateNumThreads(numThreads: Int) {
        dataStore.edit {
            it[Keys.NUM_THREADS] = numThreads.coerceIn(
                AppSettings.MIN_NUM_THREADS,
                AppSettings.MAX_NUM_THREADS,
            )
        }
    }

    suspend fun updateEnableDiarization(enableDiarization: Boolean) {
        dataStore.edit { it[Keys.ENABLE_DIARIZATION] = enableDiarization }
    }

    suspend fun updateDiarizationThreshold(diarizationThreshold: Float) {
        dataStore.edit { it[Keys.DIARIZATION_THRESHOLD] = diarizationThreshold.coerceIn(0f, 1f) }
    }

    suspend fun updateVadSilenceMs(vadSilenceMs: Int) {
        dataStore.edit {
            it[Keys.VAD_SILENCE_MS] = vadSilenceMs.coerceIn(
                AppSettings.MIN_VAD_SILENCE_MS,
                AppSettings.MAX_VAD_SILENCE_MS,
            )
        }
    }

    suspend fun updateMaxSegmentDurationMs(maxSegmentDurationMs: Int) {
        dataStore.edit {
            it[Keys.MAX_SEGMENT_DURATION_MS] = maxSegmentDurationMs.coerceIn(
                AppSettings.MIN_SEGMENT_DURATION_MS,
                AppSettings.MAX_SEGMENT_DURATION_MS,
            )
        }
    }

    suspend fun updateOverlayBgAlpha(overlayBgAlpha: Float) {
        dataStore.edit {
            it[Keys.OVERLAY_BG_ALPHA] = overlayBgAlpha.coerceIn(
                AppSettings.MIN_OVERLAY_ALPHA,
                AppSettings.MAX_OVERLAY_ALPHA,
            )
        }
    }

    suspend fun updateOverlayMaxLines(overlayMaxLines: Int) {
        dataStore.edit {
            it[Keys.OVERLAY_MAX_LINES] = overlayMaxLines.coerceIn(
                AppSettings.MIN_OVERLAY_LINES,
                AppSettings.MAX_OVERLAY_LINES,
            )
        }
    }

    suspend fun updateExportIncludeSpeaker(exportIncludeSpeaker: Boolean) {
        dataStore.edit { it[Keys.EXPORT_INCLUDE_SPEAKER] = exportIncludeSpeaker }
    }

    suspend fun updateExportIncludeTimestamps(exportIncludeTimestamps: Boolean) {
        dataStore.edit { it[Keys.EXPORT_INCLUDE_TIMESTAMPS] = exportIncludeTimestamps }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings.DEFAULTS
        return AppSettings(
            audioSource = this[Keys.AUDIO_SOURCE]
                ?.let { name -> runCatching { AudioSourceType.valueOf(name) }.getOrNull() }
                ?: defaults.audioSource,
            asrModel = this[Keys.ASR_MODEL]
                ?.let { name -> runCatching { AsrModelType.valueOf(name) }.getOrNull() }
                ?: defaults.asrModel,
            language = this[Keys.LANGUAGE] ?: defaults.language,
            showTimestamps = this[Keys.SHOW_TIMESTAMPS] ?: defaults.showTimestamps,
            fontSizeSp = this[Keys.FONT_SIZE_SP] ?: defaults.fontSizeSp,
            numThreads = this[Keys.NUM_THREADS] ?: defaults.numThreads,
            executionProvider = this[Keys.EXECUTION_PROVIDER] ?: defaults.executionProvider,
            enableDiarization = this[Keys.ENABLE_DIARIZATION] ?: defaults.enableDiarization,
            diarizationThreshold = this[Keys.DIARIZATION_THRESHOLD]
                ?: defaults.diarizationThreshold,
            vadSilenceMs = this[Keys.VAD_SILENCE_MS] ?: defaults.vadSilenceMs,
            maxSegmentDurationMs = this[Keys.MAX_SEGMENT_DURATION_MS]
                ?: defaults.maxSegmentDurationMs,
            overlayBgAlpha = this[Keys.OVERLAY_BG_ALPHA] ?: defaults.overlayBgAlpha,
            overlayMaxLines = this[Keys.OVERLAY_MAX_LINES] ?: defaults.overlayMaxLines,
            exportIncludeSpeaker = this[Keys.EXPORT_INCLUDE_SPEAKER]
                ?: defaults.exportIncludeSpeaker,
            exportIncludeTimestamps = this[Keys.EXPORT_INCLUDE_TIMESTAMPS]
                ?: defaults.exportIncludeTimestamps,
        ).sanitized()
    }

    private object Keys {
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val ASR_MODEL = stringPreferencesKey("asr_model")
        val LANGUAGE = stringPreferencesKey("language")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
        val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val NUM_THREADS = intPreferencesKey("num_threads")
        val EXECUTION_PROVIDER = stringPreferencesKey("execution_provider")
        val ENABLE_DIARIZATION = booleanPreferencesKey("enable_diarization")
        val DIARIZATION_THRESHOLD = floatPreferencesKey("diarization_threshold")
        val VAD_SILENCE_MS = intPreferencesKey("vad_silence_ms")
        val MAX_SEGMENT_DURATION_MS = intPreferencesKey("max_segment_duration_ms")
        val OVERLAY_BG_ALPHA = floatPreferencesKey("overlay_bg_alpha")
        val OVERLAY_MAX_LINES = intPreferencesKey("overlay_max_lines")
        val EXPORT_INCLUDE_SPEAKER = booleanPreferencesKey("export_include_speaker")
        val EXPORT_INCLUDE_TIMESTAMPS = booleanPreferencesKey("export_include_timestamps")
    }
}
