package com.localcaption.app.asr

import kotlin.math.max

/**
 * Speaker assigned to one speech segment.
 *
 * @property label display label (`说话人 1`, `说话人 2`, ...)
 * @property clusterIndex zero-based cluster index within the session
 * @property similarity cosine similarity to the cluster centroid
 * @property isNewSpeaker `true` when the segment introduced a new speaker
 */
data class SpeakerIdentity(
    val label: String,
    val clusterIndex: Int,
    val similarity: Float,
    val isNewSpeaker: Boolean,
)

/**
 * Wraps the sherpa-onnx speaker embedding extractor and a [SpeakerClusterTracker] to
 * attribute recognized segments to speakers.
 *
 * Segments shorter than [minSegmentMs] are skipped because short embeddings are too
 * noisy to cluster reliably; they are still transcribed, just without a speaker.
 */
class SpeakerDiarizer(
    private val extractor: SherpaSpeakerEmbeddingExtractor?,
    private val tracker: SpeakerClusterTracker = SpeakerClusterTracker(),
    private val minSegmentMs: Long = DEFAULT_MIN_SEGMENT_MS,
    private val sampleRate: Int = 16_000,
) : AutoCloseable {

    /** `true` when an embedding extractor is ready to be used. */
    val isAvailable: Boolean
        get() = extractor != null && SherpaOnnxBridge.isSpeakerEmbeddingAvailable

    /** Number of speakers discovered so far. */
    val speakerCount: Int
        get() = tracker.speakerCount

    /**
     * Identifies the speaker of [samples].
     *
     * @return the matched speaker, or `null` when diarization is unavailable or the
     * segment is too short
     */
    fun identify(samples: FloatArray, startMs: Long, endMs: Long): SpeakerIdentity? {
        val activeExtractor = extractor ?: return null
        if (!isAvailable) return null
        if (samples.isEmpty()) return null

        val minSamples = max(1, (sampleRate.toLong() * minSegmentMs / 1000L).toInt())
        if (samples.size < minSamples) return null
        if (endMs - startMs < minSegmentMs) return null

        val embedding = activeExtractor.computeEmbedding(samples, sampleRate) ?: return null
        val match = tracker.assign(embedding) ?: return null
        return SpeakerIdentity(
            label = match.label,
            clusterIndex = match.clusterIndex,
            similarity = match.similarity,
            isNewSpeaker = match.isNewSpeaker,
        )
    }

    /** Convenience overload for [SpeechSegment]. */
    fun identify(segment: SpeechSegment): SpeakerIdentity? =
        identify(segment.samples, segment.startMs, segment.endMs)

    /** Label of an already discovered speaker cluster. */
    fun labelFor(clusterIndex: Int): String? = tracker.labelFor(clusterIndex)

    /** Forgets all speaker clusters. */
    fun reset() {
        tracker.reset()
    }

    override fun close() {
        extractor?.let { runCatching { it.close() } }
    }

    companion object {
        const val DEFAULT_MIN_SEGMENT_MS = 800L
    }
}
