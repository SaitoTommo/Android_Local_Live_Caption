package com.localcaption.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Insert
    suspend fun insert(segment: SegmentEntity): Long

    @Insert
    suspend fun insertAll(segments: List<SegmentEntity>): List<Long>

    @Query("DELETE FROM segments WHERE id = :segmentId")
    suspend fun deleteById(segmentId: Long)

    @Query("DELETE FROM segments WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("SELECT * FROM segments WHERE id = :segmentId")
    suspend fun getById(segmentId: Long): SegmentEntity?

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT COUNT(*) FROM segments WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    @Query("UPDATE segments SET text = :text WHERE id = :segmentId")
    suspend fun updateText(segmentId: Long, text: String)

    @Query("UPDATE segments SET speakerId = :speakerId WHERE id = :segmentId")
    suspend fun assignSpeaker(segmentId: Long, speakerId: Long?)

    @Query(
        "UPDATE segments SET speakerId = :toSpeakerId " +
            "WHERE sessionId = :sessionId AND speakerId = :fromSpeakerId",
    )
    suspend fun reassignSpeaker(sessionId: Long, fromSpeakerId: Long, toSpeakerId: Long)

    @Query("UPDATE segments SET speakerId = NULL WHERE sessionId = :sessionId")
    suspend fun clearSpeakersBySession(sessionId: Long)
}
