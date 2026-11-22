package com.localcaption.app.asr

import kotlin.math.sqrt

/**
 * Outcome of assigning an embedding to a speaker cluster.
 *
 * @property clusterIndex zero-based cluster index
 * @property label human readable label such as `说话人 1`
 * @property similarity cosine similarity to the matched centroid (1.0 for a new cluster)
 * @property isNewSpeaker `true` when this call created a new cluster
 */
data class SpeakerMatch(
    val clusterIndex: Int,
    val label: String,
    val similarity: Float,
    val isNewSpeaker: Boolean,
)

/**
 * Pure Kotlin online speaker clustering based on cosine similarity to running
 * centroids.
 *
 * The tracker keeps the (L2 normalized) sum of all embeddings seen per speaker, so a
 * centroid is the normalized mean embedding. A new embedding joins the most similar
 * speaker when `similarity >= threshold`; otherwise it creates a new speaker until
 * [maxSpeakers] clusters exist, after which the closest cluster is used.
 */
class SpeakerClusterTracker(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val maxSpeakers: Int = DEFAULT_MAX_SPEAKERS,
) {

    private val centroidSums = ArrayList<FloatArray>()
    private val counts = ArrayList<Int>()
    private val labels = ArrayList<String>()

    /** Number of speakers discovered so far. */
    val speakerCount: Int
        get() = centroidSums.size

    /** Effective cosine similarity threshold. */
    val cosineThreshold: Float
        get() = threshold

    /**
     * Assigns [embedding] to an existing or new speaker cluster.
     *
     * @return the match, or `null` when [embedding] is empty/degenerate
     */
    fun assign(embedding: FloatArray): SpeakerMatch? {
        if (embedding.isEmpty()) return null
        val normalized = normalize(embedding) ?: return null

        var bestIndex = -1
        var bestSimilarity = -1f
        for (index in centroidSums.indices) {
            val sum = centroidSums[index]
            if (sum.size != normalized.size) continue
            val centroid = normalize(sum) ?: continue
            val similarity = dot(normalized, centroid)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestIndex = index
            }
        }

        if (bestIndex >= 0 && bestSimilarity >= threshold) {
            accumulate(bestIndex, normalized)
            return SpeakerMatch(
                clusterIndex = bestIndex,
                label = labels[bestIndex],
                similarity = bestSimilarity,
                isNewSpeaker = false,
            )
        }

        if (centroidSums.size >= maxSpeakers && bestIndex >= 0) {
            // Speaker budget exhausted: fold the embedding into the closest cluster.
            accumulate(bestIndex, normalized)
            return SpeakerMatch(
                clusterIndex = bestIndex,
                label = labels[bestIndex],
                similarity = bestSimilarity,
                isNewSpeaker = false,
            )
        }

        val index = centroidSums.size
        centroidSums.add(normalized.copyOf())
        counts.add(1)
        labels.add(defaultLabel(index))
        return SpeakerMatch(
            clusterIndex = index,
            label = labels[index],
            similarity = 1f,
            isNewSpeaker = true,
        )
    }

    /** Label of the cluster with [index], or `null` when unknown. */
    fun labelFor(index: Int): String? = labels.getOrNull(index)

    /** Clears all learned speakers. */
    fun reset() {
        centroidSums.clear()
        counts.clear()
        labels.clear()
    }

    /** Returns a copy of the current centroids (mostly useful for tests/debugging). */
    fun centroids(): List<FloatArray> = centroidSums.mapNotNull { normalize(it) }

    private fun accumulate(index: Int, normalized: FloatArray) {
        val sum = centroidSums.getOrNull(index) ?: return
        if (sum.size != normalized.size) return
        for (position in sum.indices) {
            sum[position] += normalized[position]
        }
        counts[index] = counts[index] + 1
    }

    private fun defaultLabel(index: Int): String = "说话人 ${index + 1}"

    companion object {
        const val DEFAULT_THRESHOLD = 0.58f
        const val DEFAULT_MAX_SPEAKERS = 12

        /** L2-normalizes [vector], returning `null` for empty/non-finite vectors. */
        internal fun normalize(vector: FloatArray): FloatArray? {
            var sumOfSquares = 0.0
            for (value in vector) {
                if (!value.isFinite()) return null
                sumOfSquares += value.toDouble() * value.toDouble()
            }
            val norm = sqrt(sumOfSquares)
            if (norm < 1e-6) return null
            val inverse = (1.0 / norm).toFloat()
            val result = FloatArray(vector.size)
            for (index in vector.indices) {
                result[index] = (vector[index] * inverse).coerceIn(-1f, 1f)
            }
            return result
        }

        /** Cosine similarity of two vectors. Returns `0f` for mismatched dimensions. */
        internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dotProduct = 0f
            for (index in a.indices) {
                dotProduct += a[index] * b[index]
            }
            return dotProduct.coerceIn(-1f, 1f)
        }

        private fun dot(a: FloatArray, b: FloatArray): Float = cosineSimilarity(a, b)
    }
}
