package com.localcaption.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A (locally detected) speaker within a [SessionEntity].
 */
@Entity(
    tableName = "speakers",
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
        Index(value = ["sessionId", "label"], unique = true),
    ],
)
data class SpeakerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)
