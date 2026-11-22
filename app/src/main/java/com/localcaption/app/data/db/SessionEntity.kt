package com.localcaption.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType

/**
 * A single transcription session (all captions between start and stop).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val audioSource: AudioSourceType,
    val asrModel: AsrModelType,
    val language: String = "auto",
)
