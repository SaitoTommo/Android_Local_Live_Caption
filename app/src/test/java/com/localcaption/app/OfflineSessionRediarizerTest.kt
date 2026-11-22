package com.localcaption.app

import com.localcaption.app.asr.OfflineSessionRediarizer
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionRediarizerTest {

    private fun normalized(vararg values: Float): FloatArray {
        val arr = floatArrayOf(*values)
        var sumSq = 0.0
        for (v in arr) sumSq += v * v
        val norm = sqrt(sumSq).toFloat()
        return FloatArray(arr.size) { arr[it] / norm }
    }

    @Test
    fun `agglomerative clustering merges noisy variants of same speaker and separates distinct speakers`() {
        val embeddings = listOf(
            normalized(1.0f, 0.05f, 0.0f),
            normalized(0.95f, 0.12f, 0.03f),
            normalized(0.05f, 1.0f, 0.0f),
            normalized(0.10f, 0.96f, -0.02f),
        )
        val clusters = OfflineSessionRediarizer.clusterAgglomerative(
            embeddings = embeddings,
            expectedSpeakers = null,
            similarityThreshold = 0.55f,
        )

        assertEquals(clusters[0], clusters[1])
        assertEquals(clusters[2], clusters[3])
        assertNotEquals(clusters[0], clusters[2])
    }

    @Test
    fun `agglomerative clustering respects explicit expected speaker count`() {
        val embeddings = listOf(
            normalized(1.0f, 0.0f, 0.0f),
            normalized(0.8f, 0.4f, 0.0f),
            normalized(0.0f, 1.0f, 0.0f),
            normalized(0.0f, 0.0f, 1.0f),
        )
        val clusters = OfflineSessionRediarizer.clusterAgglomerative(
            embeddings = embeddings,
            expectedSpeakers = 2,
        )

        assertEquals(2, clusters.toSet().size)
    }

    @Test
    fun `single speaker across 16 noisy windows merges cleanly into 1 speaker`() {
        val embeddings = List(16) { idx ->
            normalized(
                0.90f,
                (idx % 5 - 2) * 0.08f,
                ((idx * 3) % 5 - 2) * 0.07f,
            )
        }
        val clusters = OfflineSessionRediarizer.clusterAgglomerative(
            embeddings = embeddings,
            expectedSpeakers = null,
            similarityThreshold = 0.45f,
        )
        assertEquals(1, clusters.toSet().size)
    }

    @Test
    fun `preprocessAudio removes DC offset and normalizes RMS`() {
        val samples = FloatArray(1600) { i -> 0.5f + if (i % 2 == 0) 0.01f else -0.01f }
        val processed = OfflineSessionRediarizer.preprocessAudio(samples)
        assertEquals(samples.size, processed.size)
        val mean = processed.average()
        assertTrue("Expected near-zero mean, got $mean", kotlin.math.abs(mean) < 0.02)
    }
}
