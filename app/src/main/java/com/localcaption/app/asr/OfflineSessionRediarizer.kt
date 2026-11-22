package com.localcaption.app.asr

import android.util.Log
import com.localcaption.app.audio.AudioCaptureSource
import com.localcaption.app.audio.SessionWavRecorder
import com.localcaption.app.data.db.SegmentEntity
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whole-session offline speaker re-diarization.
 *
 * Unlike online per-segment clustering (which drifts when background noise changes between
 * short segments), this processes the ENTIRE session recording at once:
 * 1. High-pass pre-emphasis + global RMS normalization to suppress low-frequency hum/noise.
 * 2. Overlapping sliding-window speaker embeddings (1.6 s window, 0.8 s hop) across speech.
 * 3. Session-level embedding mean-centering + L2 normalization to strip shared room/channel noise.
 * 4. Global Agglomerative Hierarchical Clustering (average linkage) across all windows.
 * 5. Weighted segment voting to assign clean chronological speaker labels (`说话人 1`, `说话人 2`…).
 */
object OfflineSessionRediarizer {

    private const val TAG = "OfflineRediarizer"
    private const val SAMPLE_RATE = AudioCaptureSource.SAMPLE_RATE
    private const val WINDOW_SAMPLES = (SAMPLE_RATE * 2.4f).toInt()
    private const val HOP_SAMPLES = (SAMPLE_RATE * 1.2f).toInt()
    private const val MIN_WINDOW_SAMPLES = (SAMPLE_RATE * 0.6f).toInt()
    private const val CONTEXT_PAD_SAMPLES = (SAMPLE_RATE * 0.25f).toInt()

    data class RediarizeResult(
        val segmentToSpeakerLabel: Map<Long, String>,
        val speakerCount: Int,
    )

    private data class WindowEmbedding(
        val startSample: Int,
        val endSample: Int,
        var embedding: FloatArray,
    )

    suspend fun rediarize(
        audioFile: File,
        segments: List<SegmentEntity>,
        modelManager: ModelManager,
        expectedSpeakers: Int? = null,
        similarityThreshold: Float = 0.50f,
    ): Result<RediarizeResult> = withContext(Dispatchers.Default) {
        runCatching {
            require(segments.isNotEmpty()) { "当前会话没有字幕段落" }
            val modelFile = modelManager.speakerEmbeddingFile()
                ?: error("未安装 3D-Speaker 声纹模型，请先到「模型管理」下载")
            val rawSamples = SessionWavRecorder.readWavToFloats(audioFile)
                ?: error("无法读取本次会话的原始音频文件")

            val cleaned = preprocessAudio(rawSamples)
            val extractor = SherpaOnnxBridge.createSpeakerEmbeddingExtractor(
                modelFile = modelFile,
                numThreads = 2,
            ) ?: error("sherpa-onnx 声纹引擎不可用")

            val windows = mutableListOf<WindowEmbedding>()
            try {
                val regions = buildMergedSpeechRegions(segments, cleaned.size)
                for ((regionStart, regionEnd) in regions) {
                    val length = regionEnd - regionStart
                    if (length < MIN_WINDOW_SAMPLES) continue
                    if (length <= WINDOW_SAMPLES) {
                        val slice = cleaned.copyOfRange(regionStart, regionEnd)
                        val emb = extractor.computeEmbedding(slice, SAMPLE_RATE)
                        if (emb != null && emb.isNotEmpty()) {
                            windows += WindowEmbedding(regionStart, regionEnd, emb)
                        }
                    } else {
                        var pos = regionStart
                        while (pos < regionEnd) {
                            val end = min(pos + WINDOW_SAMPLES, regionEnd)
                            val winStart = if (end - pos < MIN_WINDOW_SAMPLES) {
                                max(regionStart, end - WINDOW_SAMPLES)
                            } else {
                                pos
                            }
                            val slice = cleaned.copyOfRange(winStart, end)
                            val emb = extractor.computeEmbedding(slice, SAMPLE_RATE)
                            if (emb != null && emb.isNotEmpty()) {
                                windows += WindowEmbedding(winStart, end, emb)
                            }
                            if (end >= regionEnd) break
                            pos += HOP_SAMPLES
                        }
                    }
                }
            } finally {
                runCatching { extractor.close() }
            }

            if (windows.isEmpty()) {
                error("有效语音时长不足，无法提取说话人声纹")
            }

            // Pure L2 normalization (do NOT subtract session mean: on 1-speaker recordings
            // subtracting the session mean removes the speaker's own voice vector!).
            normalizeAll(windows)

            val assignments = clusterAgglomerative(
                embeddings = windows.map { it.embedding },
                expectedSpeakers = expectedSpeakers,
                similarityThreshold = similarityThreshold.coerceIn(0.15f, 0.90f),
            )

            // Assign each segment to the cluster with maximum time-weighted overlap.
            val sortedSegments = segments.sortedBy { it.startMs }
            val rawClusterPerSegment = LinkedHashMap<Long, Int>()
            for (seg in sortedSegments) {
                val segStart = ((seg.startMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, cleaned.size)
                val segEnd = ((seg.endMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(segStart, cleaned.size)

                val votes = mutableMapOf<Int, Long>()
                for (i in windows.indices) {
                    val w = windows[i]
                    val overlap = (min(segEnd, w.endSample) - max(segStart, w.startSample)).toLong()
                    if (overlap > 0L) {
                        val cluster = assignments[i]
                        votes[cluster] = (votes[cluster] ?: 0L) + overlap
                    }
                }

                val chosenCluster = votes.maxByOrNull { it.value }?.key ?: run {
                    // Fallback: nearest window in time.
                    val segMid = (segStart + segEnd) / 2
                    val nearestIdx = windows.indices.minByOrNull { idx ->
                        val wMid = (windows[idx].startSample + windows[idx].endSample) / 2
                        kotlin.math.abs(wMid - segMid)
                    } ?: 0
                    assignments[nearestIdx]
                }
                rawClusterPerSegment[seg.id] = chosenCluster
            }

            // Relabel clusters in chronological order of first appearance (说话人 1, 说话人 2, ...).
            val clusterToLabel = LinkedHashMap<Int, String>()
            val segmentLabels = LinkedHashMap<Long, String>()
            for (seg in sortedSegments) {
                val cluster = rawClusterPerSegment[seg.id] ?: continue
                val label = clusterToLabel.getOrPut(cluster) {
                    "说话人 ${clusterToLabel.size + 1}"
                }
                segmentLabels[seg.id] = label
            }

            Log.i(
                TAG,
                "Rediarized ${segments.size} segments (${windows.size} windows) into ${clusterToLabel.size} speakers",
            )
            RediarizeResult(
                segmentToSpeakerLabel = segmentLabels,
                speakerCount = clusterToLabel.size,
            )
        }
    }

    /**
     * Removes DC offset and gently normalizes global RMS loudness across the full session
     * recording. Does NOT apply extra pre-emphasis because 3D-Speaker's internal Kaldi
     * Fbank extractor already applies pre-emphasis + CMVN.
     */
    internal fun preprocessAudio(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var mean = 0.0
        for (s in samples) mean += s
        mean /= samples.size

        val out = FloatArray(samples.size)
        var sumSq = 0.0
        for (i in samples.indices) {
            val centered = (samples[i] - mean).toFloat()
            out[i] = centered
            sumSq += (centered * centered).toDouble()
        }

        val rms = sqrt(sumSq / samples.size).toFloat()
        if (rms > 1e-4f) {
            val gain = (0.08f / rms).coerceIn(0.25f, 6f)
            for (i in out.indices) {
                out[i] = (out[i] * gain).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    /**
     * Merges nearby segment spans (with padding) into continuous speech regions so
     * sliding windows capture full speaker context across pauses.
     */
    private fun buildMergedSpeechRegions(
        segments: List<SegmentEntity>,
        totalSamples: Int,
    ): List<Pair<Int, Int>> {
        val raw = segments.sortedBy { it.startMs }.map { seg ->
            val start = (((seg.startMs * SAMPLE_RATE) / 1000L).toInt() - CONTEXT_PAD_SAMPLES)
                .coerceIn(0, totalSamples)
            val end = (((seg.endMs * SAMPLE_RATE) / 1000L).toInt() + CONTEXT_PAD_SAMPLES)
                .coerceIn(start, totalSamples)
            start to end
        }
        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf<Pair<Int, Int>>()
        var curStart = raw[0].first
        var curEnd = raw[0].second
        val mergeGapSamples = (SAMPLE_RATE * 0.8f).toInt()
        for (i in 1 until raw.size) {
            val (s, e) = raw[i]
            if (s <= curEnd + mergeGapSamples) {
                curEnd = max(curEnd, e)
            } else {
                merged += curStart to curEnd
                curStart = s
                curEnd = e
            }
        }
        merged += curStart to curEnd
        return merged
    }

    private fun normalizeAll(windows: List<WindowEmbedding>) {
        for (w in windows) {
            l2NormalizeInPlace(w.embedding)
        }
    }

    /**
     * Global Agglomerative Hierarchical Clustering (centroid cosine linkage) on L2-normalized
     * vectors. Pure Kotlin so it is fast, deterministic, and unit-testable.
     */
    internal fun clusterAgglomerative(
        embeddings: List<FloatArray>,
        expectedSpeakers: Int? = null,
        similarityThreshold: Float = 0.45f,
    ): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1) { 0 }

        val targetK = expectedSpeakers?.coerceIn(1, n)
        val dim = embeddings[0].size

        class Cluster(val members: MutableList<Int>, val sumVec: FloatArray, val centroid: FloatArray)

        val clusters = MutableList(n) { idx ->
            val v = embeddings[idx].copyOf()
            l2NormalizeInPlace(v)
            Cluster(mutableListOf(idx), v.copyOf(), v)
        }

        while (clusters.size > 1) {
            if (targetK != null && clusters.size <= targetK) break

            var bestA = -1
            var bestB = -1
            var bestSim = Float.NEGATIVE_INFINITY

            for (a in 0 until clusters.size) {
                for (b in a + 1 until clusters.size) {
                    val sim = dot(clusters[a].centroid, clusters[b].centroid)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestA = a
                        bestB = b
                    }
                }
            }

            if (bestA < 0 || bestB < 0) break
            if (targetK == null && bestSim < similarityThreshold) break

            val target = clusters[bestA]
            val merged = clusters[bestB]
            target.members.addAll(merged.members)
            for (d in 0 until dim) {
                target.sumVec[d] += merged.sumVec[d]
                target.centroid[d] = target.sumVec[d]
            }
            l2NormalizeInPlace(target.centroid)
            clusters.removeAt(bestB)
        }

        val result = IntArray(n)
        clusters.forEachIndexed { clusterIdx, cluster ->
            for (m in cluster.members) {
                result[m] = clusterIdx
            }
        }
        return result
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val len = min(a.size, b.size)
        for (i in 0 until len) s += a[i] * b[i]
        return s
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var sumSq = 0.0
        for (x in v) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat()
        if (norm > 1e-8f) {
            val inv = 1f / norm
            for (i in v.indices) v[i] *= inv
        }
    }
}
