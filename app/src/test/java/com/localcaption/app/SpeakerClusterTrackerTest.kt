package com.localcaption.app

import com.localcaption.app.asr.SpeakerClusterTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SpeakerClusterTrackerTest {

    private fun unitVector(vararg values: Float): FloatArray {
        var sum = 0.0
        values.forEach { sum += it.toDouble() * it }
        val norm = sqrt(sum).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    @Test
    fun `first embedding creates the first speaker`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)

        val match = tracker.assign(unitVector(1f, 0f, 0f, 0f))

        assertNotNull(match)
        match!!
        assertEquals(0, match.clusterIndex)
        assertEquals("说话人 1", match.label)
        assertTrue(match.isNewSpeaker)
        assertEquals(1f, match.similarity, 1e-5f)
        assertEquals(1, tracker.speakerCount)
    }

    @Test
    fun `similar embedding joins the existing speaker`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)
        tracker.assign(unitVector(1f, 0f, 0f, 0f))

        val match = tracker.assign(unitVector(0.99f, 0.05f, 0f, 0f))

        assertNotNull(match)
        match!!
        assertEquals(0, match.clusterIndex)
        assertFalse(match.isNewSpeaker)
        assertTrue(match.similarity >= 0.6f)
        assertEquals(1, tracker.speakerCount)
    }

    @Test
    fun `dissimilar embedding creates a new speaker`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)
        tracker.assign(unitVector(1f, 0f, 0f, 0f))

        val match = tracker.assign(unitVector(0f, 1f, 0f, 0f))

        assertNotNull(match)
        match!!
        assertEquals(1, match.clusterIndex)
        assertEquals("说话人 2", match.label)
        assertTrue(match.isNewSpeaker)
        assertEquals(2, tracker.speakerCount)
    }

    @Test
    fun `labels stay stable for each cluster`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)
        tracker.assign(unitVector(1f, 0f, 0f))
        tracker.assign(unitVector(0f, 1f, 0f))

        assertEquals("说话人 1", tracker.labelFor(0))
        assertEquals("说话人 2", tracker.labelFor(1))
        assertNull(tracker.labelFor(5))
    }

    @Test
    fun `reset forgets all speakers`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)
        tracker.assign(unitVector(1f, 0f))
        tracker.assign(unitVector(0f, 1f))
        assertEquals(2, tracker.speakerCount)

        tracker.reset()

        assertEquals(0, tracker.speakerCount)
        val match = tracker.assign(unitVector(0f, 1f))
        assertNotNull(match)
        assertEquals(0, match!!.clusterIndex)
        assertTrue(match.isNewSpeaker)
    }

    @Test
    fun `rejects empty and degenerate embeddings`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)

        assertNull(tracker.assign(FloatArray(0)))
        assertNull(tracker.assign(FloatArray(4)))
        assertNull(tracker.assign(floatArrayOf(Float.NaN, 1f, 0f)))
        assertEquals(0, tracker.speakerCount)
    }

    @Test
    fun `folds embeddings into the closest cluster when budget is exhausted`() {
        val tracker = SpeakerClusterTracker(threshold = 0.9f, maxSpeakers = 1)
        val first = tracker.assign(unitVector(1f, 0f))
        val second = tracker.assign(unitVector(0f, 1f))

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.clusterIndex, second!!.clusterIndex)
        assertFalse(second.isNewSpeaker)
        assertEquals(1, tracker.speakerCount)
    }

    @Test
    fun `keeps normalized centroids`() {
        val tracker = SpeakerClusterTracker(threshold = 0.6f)
        tracker.assign(unitVector(1f, 2f, 2f))
        tracker.assign(unitVector(1f, 2f, 2f))

        val centroid = tracker.centroids().single()
        var norm = 0.0
        centroid.forEach { norm += it.toDouble() * it }
        assertEquals(1.0, sqrt(norm), 1e-5)
    }
}
