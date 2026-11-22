package com.localcaption.app

import android.app.Application
import android.content.Context
import com.localcaption.app.asr.ModelManager
import com.localcaption.app.data.db.AppDatabase
import com.localcaption.app.data.prefs.SettingsRepository
import com.localcaption.app.data.repository.TranscriptionRepository

/**
 * Application container for the app-wide singletons.
 */
class LocalCaptionApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var transcriptionRepository: TranscriptionRepository
        private set

    lateinit var modelManager: ModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        transcriptionRepository = TranscriptionRepository(database)
        modelManager = ModelManager(this)
    }

    companion object {
        fun from(context: Context): LocalCaptionApp =
            context.applicationContext as LocalCaptionApp
    }
}
