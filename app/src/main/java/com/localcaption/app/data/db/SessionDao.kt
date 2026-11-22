package com.localcaption.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeById(sessionId: Long): Flow<SessionEntity?>

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun markEnded(sessionId: Long, endedAt: Long)

    @Query("UPDATE sessions SET title = :title WHERE id = :sessionId")
    suspend fun rename(sessionId: Long, title: String)

    @Query("UPDATE sessions SET audioFilePath = :audioFilePath WHERE id = :sessionId")
    suspend fun updateAudioFilePath(sessionId: Long, audioFilePath: String?)
}
