package com.localcaption.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(speaker: SpeakerEntity): Long

    @Query("DELETE FROM speakers WHERE id = :speakerId")
    suspend fun deleteById(speakerId: Long)

    @Query("DELETE FROM speakers WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("SELECT * FROM speakers WHERE id = :speakerId")
    suspend fun getById(speakerId: Long): SpeakerEntity?

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getBySession(sessionId: Long): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeBySession(sessionId: Long): Flow<List<SpeakerEntity>>

    @Query("UPDATE speakers SET label = :label WHERE id = :speakerId")
    suspend fun rename(speakerId: Long, label: String)

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId AND label = :label LIMIT 1")
    suspend fun findByLabel(sessionId: Long, label: String): SpeakerEntity?
}
