package com.localcaption.app.data.repository

import com.localcaption.app.data.db.AppDatabase
import com.localcaption.app.data.db.SegmentEntity
import com.localcaption.app.data.db.SessionEntity
import com.localcaption.app.data.db.SpeakerEntity
import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for persisting transcription sessions, segments and speakers.
 */
class TranscriptionRepository(database: AppDatabase) {

    private val sessionDao = database.sessionDao()
    private val segmentDao = database.segmentDao()
    private val speakerDao = database.speakerDao()

    // region Sessions

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun observeSession(sessionId: Long): Flow<SessionEntity?> =
        sessionDao.observeById(sessionId)

    suspend fun getSession(sessionId: Long): SessionEntity? =
        sessionDao.getById(sessionId)

    suspend fun createSession(
        title: String,
        audioSource: AudioSourceType,
        asrModel: AsrModelType,
        language: String = "auto",
        startedAt: Long = System.currentTimeMillis(),
    ): Long = sessionDao.insert(
        SessionEntity(
            title = title,
            startedAt = startedAt,
            audioSource = audioSource,
            asrModel = asrModel,
            language = language,
        ),
    )

    suspend fun endSession(
        sessionId: Long,
        endedAt: Long = System.currentTimeMillis(),
    ) {
        sessionDao.markEnded(sessionId, endedAt)
    }

    suspend fun renameSession(sessionId: Long, title: String) {
        sessionDao.rename(sessionId, title)
    }

    suspend fun deleteSession(sessionId: Long) {
        // Segments and speakers are removed by ON DELETE CASCADE.
        sessionDao.deleteById(sessionId)
    }

    // endregion

    // region Segments

    fun observeSegments(sessionId: Long): Flow<List<SegmentEntity>> =
        segmentDao.observeBySession(sessionId)

    suspend fun getSegments(sessionId: Long): List<SegmentEntity> =
        segmentDao.getBySession(sessionId)

    suspend fun segmentCount(sessionId: Long): Int = segmentDao.countBySession(sessionId)

    suspend fun addSegment(segment: SegmentEntity): Long = segmentDao.insert(segment)

    suspend fun addSegment(
        sessionId: Long,
        text: String,
        startMs: Long,
        endMs: Long,
        audioSource: AudioSourceType,
        speakerId: Long? = null,
        isFinal: Boolean = true,
    ): Long = segmentDao.insert(
        SegmentEntity(
            sessionId = sessionId,
            speakerId = speakerId,
            startMs = startMs,
            endMs = endMs,
            text = text,
            isFinal = isFinal,
            audioSource = audioSource,
        ),
    )

    suspend fun addSegments(segments: List<SegmentEntity>): List<Long> =
        segmentDao.insertAll(segments)

    suspend fun updateSegmentText(segmentId: Long, text: String) {
        segmentDao.updateText(segmentId, text)
    }

    suspend fun assignSpeaker(segmentId: Long, speakerId: Long?) {
        segmentDao.assignSpeaker(segmentId, speakerId)
    }

    suspend fun clearSegments(sessionId: Long) {
        segmentDao.deleteBySession(sessionId)
    }

    // endregion

    // region Speakers

    fun observeSpeakers(sessionId: Long): Flow<List<SpeakerEntity>> =
        speakerDao.observeBySession(sessionId)

    suspend fun getSpeakers(sessionId: Long): List<SpeakerEntity> =
        speakerDao.getBySession(sessionId)

    /**
     * Returns the id of the speaker with [label] in the session, creating it if needed.
     */
    suspend fun getOrCreateSpeaker(sessionId: Long, label: String): Long {
        speakerDao.findByLabel(sessionId, label)?.let { return it.id }
        val id = speakerDao.insert(SpeakerEntity(sessionId = sessionId, label = label))
        return if (id != -1L) {
            id
        } else {
            requireNotNull(speakerDao.findByLabel(sessionId, label)) {
                "Speaker '$label' could not be created in session $sessionId"
            }.id
        }
    }

    suspend fun renameSpeaker(speakerId: Long, label: String) {
        speakerDao.rename(speakerId, label)
    }

    suspend fun deleteSpeaker(speakerId: Long) {
        speakerDao.deleteById(speakerId)
    }

    // endregion
}
