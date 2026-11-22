package com.localcaption.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType

/**
 * Room type converters for all enums persisted in the database.
 * Enums are stored by name so that the schema stays readable and stable.
 */
class AppTypeConverters {

    @TypeConverter
    fun fromAudioSourceType(value: AudioSourceType): String = value.name

    @TypeConverter
    fun toAudioSourceType(value: String): AudioSourceType =
        runCatching { AudioSourceType.valueOf(value) }.getOrDefault(AudioSourceType.PLAYBACK_CAPTURE)

    @TypeConverter
    fun fromAsrModelType(value: AsrModelType): String = value.name

    @TypeConverter
    fun toAsrModelType(value: String): AsrModelType =
        runCatching { AsrModelType.valueOf(value) }.getOrDefault(AsrModelType.QWEN3_ASR_0_6B_INT8)
}

@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun segmentDao(): SegmentDao

    abstract fun speakerDao(): SpeakerDao

    companion object {
        private const val DATABASE_NAME = "local_live_caption.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
