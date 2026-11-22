package com.localcaption.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.localcaption.app.data.model.AudioSourceType

/**
 * One recognized caption segment belonging to a [SessionEntity].
 *
 * Times are milliseconds relative to the start of the session.
 */
@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("speakerId"),
    ],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long,
    val speakerId: Long? = null,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isFinal: Boolean = true,
    val audioSource: AudioSourceType,
    val createdAt: Long = System.currentTimeMillis(),
)
