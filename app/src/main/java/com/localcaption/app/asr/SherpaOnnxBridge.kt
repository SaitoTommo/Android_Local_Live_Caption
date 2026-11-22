package com.localcaption.app.asr

import android.util.Log
import com.localcaption.app.data.model.AsrModelType
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection-backed bridge to the `com.k2fsa.sherpa.onnx` Kotlin API.
 *
 * The project deliberately does not compile against the sherpa-onnx AAR so that it
 * builds cleanly before `sherpa-onnx-*.aar` is dropped into `app/libs/`. As soon as
 * the AAR is present the same code drives the native library without any source
 * change:
 *
 * - offline ASR: `OfflineRecognizer` + `OfflineQwen3AsrModelConfig` /
 *   `OfflineSenseVoiceModelConfig`
 * - voice activity detection: `VoiceActivityDetector` + `SileroVadModelConfig`
 * - speaker embeddings: `SpeakerEmbeddingExtractor`
 *
 * Every entry point returns `null` (and logs at debug level) when the AAR or the
 * requested configuration is unavailable, so callers can degrade gracefully.
 */
object SherpaOnnxBridge {

    private const val TAG = "SherpaOnnxBridge"
    private const val PACKAGE = "com.k2fsa.sherpa.onnx"

    private val classLoader: ClassLoader
        get() = SherpaOnnxBridge::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    /** `true` when the sherpa-onnx Kotlin classes and native library are usable. */
    val isAvailable: Boolean by lazy { loadClass("OfflineRecognizer") != null }

    /** `true` when offline ASR support is present. */
    val isAsrAvailable: Boolean
        get() = isAvailable

    /** `true` when Silero VAD support is present (`Vad` or `VoiceActivityDetector`). */
    val isVadAvailable: Boolean by lazy {
        isAvailable &&
            (loadClass("Vad") != null || loadClass("VoiceActivityDetector") != null)
    }

    /** `true` when speaker embedding support is present. */
    val isSpeakerEmbeddingAvailable: Boolean by lazy {
        isAvailable && loadClass("SpeakerEmbeddingExtractor") != null
    }

    // region Offline ASR

    /**
     * Creates an offline recognizer for [modelType] from the model files inside
     * [modelDir], or returns `null` when sherpa-onnx (or the model) is unavailable.
     */
    fun createOfflineRecognizer(
        modelType: AsrModelType,
        modelDir: File,
        numThreads: Int = 2,
        language: String = "auto",
        provider: String = "cpu",
        debug: Boolean = false,
    ): SherpaOfflineRecognizer? {
        if (!isAvailable) return null
        if (!modelDir.isDirectory) {
            Log.d(TAG, "Model directory does not exist: $modelDir")
            return null
        }

        val normalizedProvider = if (provider == "nnapi") "nnapi" else "cpu"
        val primary = createOfflineRecognizerInternal(
            modelType = modelType,
            modelDir = modelDir,
            numThreads = numThreads,
            language = language,
            provider = normalizedProvider,
            fellBackFromNnapi = false,
            debug = debug,
        )
        if (primary != null || normalizedProvider == "cpu") {
            return primary
        }
        Log.w(TAG, "NNAPI provider failed for ${modelType.name}, falling back to CPU")
        return createOfflineRecognizerInternal(
            modelType = modelType,
            modelDir = modelDir,
            numThreads = numThreads,
            language = language,
            provider = "cpu",
            fellBackFromNnapi = true,
            debug = debug,
        )
    }

    private fun createOfflineRecognizerInternal(
        modelType: AsrModelType,
        modelDir: File,
        numThreads: Int,
        language: String,
        provider: String,
        fellBackFromNnapi: Boolean,
        debug: Boolean,
    ): SherpaOfflineRecognizer? {
        return runCatching {
            val recognizerConfigClass = loadClass("OfflineRecognizerConfig") ?: return null
            val modelConfigClass = loadClass("OfflineModelConfig") ?: return null
            val modelConfig = bestEffortNewInstance(modelConfigClass) ?: return null

            when (modelType) {
                AsrModelType.QWEN3_ASR_0_6B_INT8 -> {
                    val qwenClass = loadClass("OfflineQwen3AsrModelConfig")
                        ?: loadClass("OfflineQwen3ASRModelConfig")
                    if (qwenClass == null) {
                        Log.d(TAG, "OfflineQwen3AsrModelConfig not available")
                        return null
                    }
                    val qwen = bestEffortNewInstance(qwenClass) ?: return null

                    // Qwen3-ASR ships conv_frontend.onnx, encoder.int8.onnx,
                    // decoder.int8.onnx and a tokenizer/ directory.
                    val convFrontend = File(modelDir, "conv_frontend.onnx").takeIf { it.isFile }
                        ?: findOnnxByKeyword(modelDir, "conv")
                        ?: findOnnxByKeyword(modelDir, "frontend")
                    val encoder = findOnnxByKeyword(modelDir, "encoder")
                    val decoder = findOnnxByKeyword(modelDir, "decoder")
                    val tokenizer = File(modelDir, "tokenizer").takeIf { it.isDirectory }
                    if (convFrontend == null || encoder == null || decoder == null || tokenizer == null) {
                        Log.w(TAG, "Qwen3-ASR model directory is incomplete: $modelDir")
                        return null
                    }

                    setProperty(
                        qwen,
                        listOf("convFrontend", "convFrontendModel", "convFrontendPath"),
                        convFrontend.absolutePath,
                    )
                    setProperty(qwen, listOf("encoder"), encoder.absolutePath)
                    setProperty(qwen, listOf("decoder"), decoder.absolutePath)
                    setProperty(qwen, listOf("tokenizer"), tokenizer.absolutePath)
                    setProperty(qwen, listOf("maxTotalLen"), 512)
                    setProperty(qwen, listOf("maxNewTokens"), 128)
                    setProperty(qwen, listOf("temperature"), 1e-6f)
                    setProperty(qwen, listOf("topP"), 0.8f)
                    setProperty(qwen, listOf("seed"), 42)

                    if (!setProperty(modelConfig, listOf("qwen3Asr", "qwen3AsrModel"), qwen)) {
                        setPropertyByType(modelConfig, qwen)
                    }
                }

                AsrModelType.SENSE_VOICE_SMALL_INT8 -> {
                    val senseVoiceClass = loadClass("OfflineSenseVoiceModelConfig")
                    if (senseVoiceClass == null) {
                        Log.d(TAG, "OfflineSenseVoiceModelConfig not available")
                        return null
                    }
                    val modelFile = findOnnxModel(modelDir)
                    if (modelFile == null) {
                        Log.d(TAG, "No SenseVoice .onnx model found in $modelDir")
                        return null
                    }
                    val tokensFile = File(modelDir, "tokens.txt")

                    val senseVoice = bestEffortNewInstance(senseVoiceClass) ?: return null
                    setProperty(senseVoice, listOf("model", "modelPath"), modelFile.absolutePath)
                    setProperty(senseVoice, listOf("language", "lang"), normalizeLanguage(language))
                    setProperty(
                        senseVoice,
                        listOf("useInverseTextNormalization", "useItn"),
                        true,
                    )
                    if (!setProperty(
                            modelConfig,
                            listOf("senseVoice", "senseVoiceModel", "senseVoiceConfig"),
                            senseVoice,
                        )
                    ) {
                        setPropertyByType(modelConfig, senseVoice)
                    }
                    setProperty(modelConfig, listOf("tokens"), tokensFile.absolutePath)
                }

                AsrModelType.PARAFORMER_ZH_SMALL_INT8 -> {
                    val paraformerClass = loadClass("OfflineParaformerModelConfig")
                    if (paraformerClass == null) {
                        Log.d(TAG, "OfflineParaformerModelConfig not available")
                        return null
                    }
                    val modelFile = findOnnxModel(modelDir)
                    if (modelFile == null) {
                        Log.d(TAG, "No Paraformer .onnx model found in $modelDir")
                        return null
                    }
                    val tokensFile = File(modelDir, "tokens.txt")
                    val paraformer = bestEffortNewInstance(paraformerClass) ?: return null
                    setProperty(paraformer, listOf("model", "modelPath"), modelFile.absolutePath)
                    if (!setProperty(modelConfig, listOf("paraformer"), paraformer)) {
                        setPropertyByType(modelConfig, paraformer)
                    }
                    setProperty(modelConfig, listOf("tokens"), tokensFile.absolutePath)
                }
            }

            setProperty(modelConfig, listOf("numThreads"), numThreads.coerceAtLeast(1))
            if (!setProperty(modelConfig, listOf("debug"), debug)) {
                setProperty(modelConfig, listOf("debug"), if (debug) 1 else 0)
            }
            setProperty(modelConfig, listOf("provider"), provider)

            val featureConfigClass = loadClass("FeatureConfig")
            val featureConfig = featureConfigClass?.let { bestEffortNewInstance(it) }
            if (featureConfig != null) {
                setProperty(featureConfig, listOf("sampleRate"), 16_000)
                setProperty(featureConfig, listOf("featureDim"), 80)
            }

            val recognizerConfig = bestEffortNewInstance(recognizerConfigClass) ?: return null
            if (featureConfig != null) {
                setProperty(recognizerConfig, listOf("featConfig", "featureConfig"), featureConfig)
            }
            setProperty(recognizerConfig, listOf("modelConfig"), modelConfig)
            setProperty(recognizerConfig, listOf("decodingMethod"), "greedy_search")

            createRecognizerWrapper(recognizerConfig, provider, fellBackFromNnapi)
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to create OfflineRecognizer for ${modelType.name}", throwable)
            null
        }
    }

    private fun createRecognizerWrapper(
        config: Any,
        activeProvider: String,
        fellBackFromNnapi: Boolean,
    ): SherpaOfflineRecognizer? {
        val recognizerClass = loadClass("OfflineRecognizer") ?: return null
        val streamClass = loadClass("OfflineStream") ?: return null

        val recognizer = constructWithConfig(recognizerClass, config) ?: run {
            Log.d(TAG, "No OfflineRecognizer(AssetManager?, config) constructor found")
            return null
        }

        val createStream = findMethod(recognizerClass, "createStream", 0)
        val decode = findMethod(recognizerClass, "decode", 1)
        val getResult = findMethod(recognizerClass, "getResult", 1)
        val acceptWaveform = findMethod(streamClass, "acceptWaveform", 2)
        if (createStream == null || decode == null || getResult == null || acceptWaveform == null) {
            Log.d(TAG, "OfflineRecognizer API is incomplete")
            releaseQuietly(recognizer)
            return null
        }

        return SherpaOfflineRecognizer(
            recognizer = recognizer,
            createStreamMethod = createStream,
            acceptWaveformMethod = acceptWaveform,
            decodeMethod = decode,
            getResultMethod = getResult,
            activeProvider = activeProvider,
            fellBackFromNnapi = fellBackFromNnapi,
        )
    }

    // endregion

    // region Silero VAD

    /**
     * Creates a Silero VAD detector.
     *
     * @param modelFile `silero_vad.onnx`
     * @param sampleRate audio sample rate, must be 8000 or 16000
     * @param silenceMs trailing silence that terminates a speech segment
     * @param maxSpeechMs hard upper bound of a speech segment
     */
    fun createVad(
        modelFile: File,
        sampleRate: Int = 16_000,
        silenceMs: Int = 600,
        maxSpeechMs: Int = 10_000,
        threshold: Float = 0.5f,
        numThreads: Int = 1,
        debug: Boolean = false,
    ): SherpaVadHandle? {
        if (!isVadAvailable || !modelFile.isFile) return null
        return runCatching {
            val sileroConfigClass = loadClass("SileroVadModelConfig") ?: return null
            val sileroConfig = bestEffortNewInstance(sileroConfigClass) ?: return null
            setProperty(sileroConfig, listOf("model", "modelPath"), modelFile.absolutePath)
            setProperty(sileroConfig, listOf("threshold"), threshold)
            setProperty(
                sileroConfig,
                listOf(
                    "minSilenceDuration",
                    "minSilenceDurationInSeconds",
                    "minSilence",
                ),
                silenceMs / 1000f,
            )
            setProperty(
                sileroConfig,
                listOf("minSpeechDuration", "minSpeechDurationInSeconds"),
                0.25f,
            )
            setProperty(sileroConfig, listOf("windowSize"), 512)
            setProperty(
                sileroConfig,
                listOf(
                    "maxSpeechDuration",
                    "maxSpeechDurationInSeconds",
                    "maxSpeech",
                ),
                maxSpeechMs / 1000f,
            )

            val vadConfigClass = loadClass("VadModelConfig") ?: return null
            val vadConfig = bestEffortNewInstance(vadConfigClass) ?: return null
            if (!setProperty(
                    vadConfig,
                    listOf("sileroVadModelConfig", "sileroVad", "sileroVadConfig"),
                    sileroConfig,
                )
            ) {
                setPropertyByType(vadConfig, sileroConfig)
            }
            setProperty(vadConfig, listOf("sampleRate"), sampleRate)
            setProperty(vadConfig, listOf("numThreads"), numThreads.coerceAtLeast(1))
            setProperty(vadConfig, listOf("provider"), "cpu")
            setProperty(vadConfig, listOf("debug"), if (debug) 1 else 0)

            val detectorClass = loadClass("VoiceActivityDetector") ?: loadClass("Vad")
            if (detectorClass == null) {
                Log.d(TAG, "VoiceActivityDetector/Vad class not available")
                return null
            }
            // Current sherpa-onnx exposes `Vad(assetManager, config)`; older builds used
            // `VoiceActivityDetector(config, bufferSizeInSeconds)`.
            val detector = constructWithConfig(detectorClass, vadConfig) ?: run {
                val constructor = detectorClass.constructors.firstOrNull { candidate ->
                    candidate.parameterCount == 2 &&
                        candidate.parameterTypes[0].isAssignableFrom(vadConfig.javaClass) &&
                        (
                            candidate.parameterTypes[1] == java.lang.Float.TYPE ||
                                candidate.parameterTypes[1] == java.lang.Float::class.java
                            )
                }
                if (constructor == null) {
                    Log.d(TAG, "No usable VoiceActivityDetector constructor found")
                    return null
                }
                constructor.isAccessible = true
                val bufferSeconds = (maxSpeechMs / 1000f + 2f).coerceAtLeast(3f)
                constructor.newInstance(vadConfig, bufferSeconds)
            } ?: return null

            SherpaVad(detector)
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to create VoiceActivityDetector", throwable)
            null
        }
    }

    // endregion

    // region Speaker embeddings

    /**
     * Creates a speaker embedding extractor from a 3D-Speaker / WeSpeaker ONNX model.
     */
    fun createSpeakerEmbeddingExtractor(
        modelFile: File,
        numThreads: Int = 1,
        debug: Boolean = false,
    ): SherpaSpeakerEmbeddingExtractor? {
        if (!isSpeakerEmbeddingAvailable || !modelFile.isFile) return null
        return runCatching {
            val configClass = loadClass("SpeakerEmbeddingExtractorConfig") ?: return null
            val config = bestEffortNewInstance(configClass) ?: return null
            setProperty(config, listOf("model", "modelPath"), modelFile.absolutePath)
            setProperty(config, listOf("numThreads"), numThreads.coerceAtLeast(1))
            setProperty(config, listOf("debug"), if (debug) 1 else 0)
            setProperty(config, listOf("provider"), "cpu")

            val extractorClass = loadClass("SpeakerEmbeddingExtractor") ?: return null

            val extractor = constructWithConfig(extractorClass, config) ?: run {
                Log.d(TAG, "No SpeakerEmbeddingExtractor(AssetManager?, config) constructor found")
                return null
            }

            val createStream = findMethod(extractorClass, "createStream", 0)
            val compute = findMethod(extractorClass, "compute", 1)
                ?: findMethod(extractorClass, "computeEmbedding", 1)
            // `createStream()` returns an OnlineStream in current sherpa-onnx releases.
            val streamClass = createStream?.returnType
                ?.takeIf { it != Void.TYPE }
                ?: loadClass("OnlineStream")
            val acceptWaveform = streamClass?.let { findMethod(it, "acceptWaveform", 2) }
            if (createStream == null || compute == null || acceptWaveform == null) {
                Log.d(TAG, "SpeakerEmbeddingExtractor API is incomplete")
                releaseQuietly(extractor)
                return null
            }

            SherpaSpeakerEmbeddingExtractor(
                extractor = extractor,
                createStreamMethod = createStream,
                computeMethod = compute,
                acceptWaveformMethod = acceptWaveform,
            )
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to create SpeakerEmbeddingExtractor", throwable)
            null
        }
    }

    // endregion

    // region Model helpers

    /** Finds the best `.onnx` file inside [directory]. */
    fun findOnnxModel(directory: File): File? {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return null
        return files.firstOrNull { it.name.contains("int8", ignoreCase = true) }
            ?: files.firstOrNull { it.name.startsWith("model") }
            ?: files.first()
    }

    /** Finds an `.onnx` file whose name contains [keyword] (INT8 preferred). */
    private fun findOnnxByKeyword(directory: File, keyword: String): File? =
        directory.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(".onnx", ignoreCase = true) &&
                    it.name.contains(keyword, ignoreCase = true)
            }
            ?.sortedWith(
                compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }
                    .thenBy { it.name },
            )
            ?.firstOrNull()

    private fun normalizeLanguage(language: String): String {
        val value = language.trim().lowercase()
        return when {
            value.isEmpty() || value == "auto" -> "auto"
            value.startsWith("zh") -> "zh"
            value.startsWith("en") -> "en"
            value.startsWith("ja") -> "ja"
            value.startsWith("ko") -> "ko"
            value.startsWith("yue") || value.startsWith("cantonese") -> "yue"
            else -> value
        }
    }

    // endregion

    // region Reflection utilities

    internal fun loadClass(shortName: String): Class<*>? = try {
        Class.forName("$PACKAGE.$shortName", true, classLoader)
    } catch (throwable: Throwable) {
        Log.d(TAG, "sherpa-onnx class '$shortName' unavailable: ${throwable.javaClass.simpleName}")
        null
    }

    internal fun bestEffortNewInstance(cls: Class<*>): Any? {
        runCatching {
            val constructor = cls.getDeclaredConstructor()
            constructor.isAccessible = true
            return constructor.newInstance()
        }

        val constructor = cls.constructors.minByOrNull { it.parameterCount } ?: return null
        val arguments = constructor.parameterTypes.map { defaultValue(it) }
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(*arguments.toTypedArray())
        }.getOrNull()
    }

    /**
     * Instantiates a sherpa-onnx wrapper class such as `OfflineRecognizer`,
     * `Vad` or `SpeakerEmbeddingExtractor`.
     *
     * These classes declare `(assetManager: AssetManager? = null, config)`; Kotlin
     * compiles that to a two-argument JVM constructor, so `null` is passed for the
     * asset manager (models are loaded from absolute file paths). A one-argument
     * `(config)` constructor is also supported for API variants that expose one.
     */
    internal fun constructWithConfig(cls: Class<*>, config: Any): Any? {
        val configType = config.javaClass
        cls.constructors
            .filter { candidate ->
                candidate.parameterCount == 2 &&
                    candidate.parameterTypes[1].isAssignableFrom(configType)
            }
            .forEach { candidate ->
                val instance = runCatching {
                    candidate.isAccessible = true
                    candidate.newInstance(null, config)
                }.getOrNull()
                if (instance != null) return instance
            }

        cls.constructors
            .filter { candidate ->
                candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0].isAssignableFrom(configType)
            }
            .forEach { candidate ->
                val instance = runCatching {
                    candidate.isAccessible = true
                    candidate.newInstance(config)
                }.getOrNull()
                if (instance != null) return instance
            }
        return null
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        String::class.java -> ""
        FloatArray::class.java -> FloatArray(0)
        IntArray::class.java -> IntArray(0)
        else -> null
    }

    /**
     * Sets the first matching property on [target] using either a Kotlin/Java setter or
     * a backing field. Returns `true` when the value was applied.
     */
    internal fun setProperty(
        target: Any,
        names: List<String>,
        value: Any?,
        required: Boolean = false,
    ): Boolean {
        val cls = target.javaClass
        for (name in names) {
            val setterName = "set" + name.replaceFirstChar { it.uppercaseChar() }
            val setter = cls.methods.firstOrNull { method ->
                method.name == setterName &&
                    method.parameterCount == 1 &&
                    accepts(method.parameterTypes[0], value)
            }
            if (setter != null) {
                val applied = runCatching { setter.invoke(target, value) }.isSuccess
                if (applied) return true
            }

            val field = findField(cls, name)
            if (field != null) {
                val applied = runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                }.isSuccess
                if (applied) return true
            }
        }
        if (required) {
            Log.w(TAG, "Property ${names.firstOrNull()} not found on ${cls.simpleName}")
        }
        return false
    }

    /** Fallback that assigns [value] to the first field/setter whose type matches. */
    internal fun setPropertyByType(target: Any, value: Any): Boolean {
        val valueType = value.javaClass
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type.isPrimitive) continue
                if (!field.type.isAssignableFrom(valueType)) continue
                val applied = runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                }.isSuccess
                if (applied) return true
            }
            for (method in current.declaredMethods) {
                if (method.parameterCount != 1 || !method.name.startsWith("set")) continue
                val parameter = method.parameterTypes[0]
                if (parameter.isPrimitive) continue
                if (!parameter.isAssignableFrom(valueType)) continue
                val applied = runCatching {
                    method.isAccessible = true
                    method.invoke(target, value)
                }.isSuccess
                if (applied) return true
            }
            current = current.superclass
        }
        return false
    }

    /** Returns the first one-argument method named [name] on [cls]. */
    internal fun findMethod(cls: Class<*>, name: String, parameterCount: Int): Method? =
        cls.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }

    /**
     * Invokes `acceptWaveform` regardless of whether the Kotlin wrapper declares
     * `(sampleRate, samples)` or `(samples, sampleRate)`.
     */
    internal fun acceptWaveform(
        method: Method,
        stream: Any,
        sampleRate: Int,
        samples: FloatArray,
    ) {
        val samplesFirst = method.parameterTypes.firstOrNull() == FloatArray::class.java
        if (samplesFirst) {
            method.invoke(stream, samples, sampleRate)
        } else {
            method.invoke(stream, sampleRate, samples)
        }
    }

    internal fun findField(cls: Class<*>, name: String): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun accepts(parameter: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameter.isPrimitive
        val boxed = when (parameter) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> parameter
        }
        return boxed.isInstance(value)
    }

    internal fun readText(result: Any): String {
        val getter = runCatching {
            result.javaClass.getMethod("getText").invoke(result)
        }.getOrNull()
        if (getter != null) return getter.toString()

        val field = findField(result.javaClass, "text")
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.get(result)?.toString().orEmpty()
            }.getOrDefault("")
        }
        return ""
    }

    internal fun readSamples(segment: Any): FloatArray? {
        val getter = runCatching {
            segment.javaClass.getMethod("getSamples").invoke(segment) as? FloatArray
        }.getOrNull()
        if (getter != null) return getter

        val field = findField(segment.javaClass, "samples")
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.get(segment) as? FloatArray
            }.getOrNull()
        }
        return null
    }

    internal fun readStartSample(segment: Any): Long {
        val getter = runCatching {
            (segment.javaClass.getMethod("getStart").invoke(segment) as? Number)?.toLong()
        }.getOrNull()
        if (getter != null && getter >= 0L) return getter

        val field = findField(segment.javaClass, "start")
        if (field != null) {
            val value = runCatching {
                field.isAccessible = true
                (field.get(segment) as? Number)?.toLong()
            }.getOrNull()
            if (value != null && value >= 0L) return value
        }
        return -1L
    }

    internal fun releaseQuietly(target: Any) {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 0 &&
                candidate.name in setOf("release", "close", "free", "dispose")
        }
        if (method != null) {
            runCatching { method.invoke(target) }
        }
    }

    // endregion
}

/**
 * Thin handle over a native `OfflineRecognizer` instance. Safe to call from multiple
 * threads; recognition is serialized because sherpa-onnx streams are not thread-safe.
 */
class SherpaOfflineRecognizer internal constructor(
    private val recognizer: Any,
    private val createStreamMethod: Method,
    private val acceptWaveformMethod: Method,
    private val decodeMethod: Method,
    private val getResultMethod: Method,
    val activeProvider: String = "cpu",
    val fellBackFromNnapi: Boolean = false,
) : AutoCloseable {

    private val lock = Any()

    @Volatile
    private var closed = false

    /** Transcribes one speech segment to text. Returns `""` on failure. */
    fun recognize(samples: FloatArray, sampleRate: Int = 16_000): String {
        if (samples.isEmpty() || closed) return ""
        synchronized(lock) {
            if (closed) return ""
            val stream = runCatching { createStreamMethod.invoke(recognizer) }.getOrNull()
                ?: return ""
            return try {
                SherpaOnnxBridge.acceptWaveform(acceptWaveformMethod, stream, sampleRate, samples)
                decodeMethod.invoke(recognizer, stream)
                val result = getResultMethod.invoke(recognizer, stream) ?: return ""
                SherpaOnnxBridge.readText(result).trim()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Speech recognition failed", throwable)
                ""
            } finally {
                SherpaOnnxBridge.releaseQuietly(stream)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            SherpaOnnxBridge.releaseQuietly(recognizer)
        }
    }

    private companion object {
        const val TAG = "SherpaOfflineRecognizer"
    }
}

/** One segment popped from Silero VAD together with its exact sample start index. */
class SherpaVadSegment(
    val startSample: Long,
    val samples: FloatArray,
)

/**
 * Handle over a Silero `VoiceActivityDetector`. Segments are popped in arrival order.
 */
interface SherpaVadHandle : AutoCloseable {

    /** Feeds mono float samples into the detector. */
    fun accept(samples: FloatArray)

    /** `true` while speech is currently detected. */
    fun isSpeechDetected(): Boolean

    /** `true` when at least one finished speech segment is ready. */
    fun hasSegment(): Boolean

    /** Pops the oldest finished speech segment, or `null` when none is ready. */
    fun popSegment(): FloatArray?

    /** Pops the oldest finished speech segment with its exact start sample index. */
    fun popSegmentWithStart(): SherpaVadSegment? =
        popSegment()?.let { SherpaVadSegment(startSample = -1L, samples = it) }

    /** Drains every finished speech segment. */
    fun flushSegments(): List<FloatArray>

    /** Clears the detector state so a new session starts with an empty buffer. */
    fun reset()

    override fun close()
}

private class SherpaVad(
    private val detector: Any,
) : SherpaVadHandle {

    private val acceptMethod = requireMethod("acceptWaveform", 1)
    private val emptyMethod = requireMethod("empty", 0)
    private val popMethod = requireMethod("pop", 0)
    private val speechMethod = requireMethod("isSpeechDetected", 0)
    private val frontMethod = requireMethod("front", 0)

    private val lock = Any()

    @Volatile
    private var closed = false

    private fun requireMethod(name: String, parameterCount: Int): Method =
        detector.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        } ?: error("VoiceActivityDetector.$name/$parameterCount is missing")

    override fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            runCatching { acceptMethod.invoke(detector, samples) }
                .onFailure { Log.w(TAG, "VAD acceptWaveform failed", it) }
        }
    }

    override fun isSpeechDetected(): Boolean = synchronized(lock) {
        if (closed) return false
        runCatching {
            speechMethod.invoke(detector) as? Boolean ?: false
        }.getOrDefault(false)
    }

    override fun hasSegment(): Boolean = synchronized(lock) {
        if (closed) return false
        runCatching {
            !(emptyMethod.invoke(detector) as? Boolean ?: true)
        }.getOrDefault(false)
    }

    override fun popSegment(): FloatArray? = popSegmentWithStart()?.samples

    override fun popSegmentWithStart(): SherpaVadSegment? = synchronized(lock) {
        if (closed) return null
        runCatching {
            if (!hasSegment()) return null
            val segment = frontMethod.invoke(detector) ?: return null
            val samples = SherpaOnnxBridge.readSamples(segment)
            val startSample = SherpaOnnxBridge.readStartSample(segment)
            popMethod.invoke(detector)
            if (samples != null && samples.isNotEmpty()) {
                SherpaVadSegment(startSample = startSample, samples = samples)
            } else {
                null
            }
        }.getOrNull()
    }

    override fun flushSegments(): List<FloatArray> {
        val segments = mutableListOf<FloatArray>()
        while (true) {
            segments += popSegment() ?: break
        }
        return segments
    }

    override fun reset() {
        synchronized(lock) {
            if (closed) return
            val resetMethod = detector.javaClass.methods.firstOrNull {
                it.name == "reset" && it.parameterCount == 0
            }
            if (resetMethod != null) {
                runCatching { resetMethod.invoke(detector) }
                    .onFailure { Log.w(TAG, "VAD reset failed", it) }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            SherpaOnnxBridge.releaseQuietly(detector)
        }
    }

    private companion object {
        const val TAG = "SherpaVad"
    }
}

/**
 * Handle over a `SpeakerEmbeddingExtractor`. Embeddings are L2-normalizable float
 * vectors; clustering is done in [SpeakerClusterTracker].
 */
class SherpaSpeakerEmbeddingExtractor internal constructor(
    private val extractor: Any,
    private val createStreamMethod: Method,
    private val computeMethod: Method,
    private val acceptWaveformMethod: Method,
) : AutoCloseable {

    private val lock = Any()

    @Volatile
    private var closed = false

    /** Computes a speaker embedding for [samples], or `null` on failure. */
    fun computeEmbedding(samples: FloatArray, sampleRate: Int = 16_000): FloatArray? {
        if (samples.isEmpty() || closed) return null
        synchronized(lock) {
            if (closed) return null
            val stream = runCatching { createStreamMethod.invoke(extractor) }.getOrNull()
                ?: return null
            return try {
                SherpaOnnxBridge.acceptWaveform(acceptWaveformMethod, stream, sampleRate, samples)
                val embedding = computeMethod.invoke(extractor, stream) as? FloatArray
                    ?: return null
                if (embedding.isEmpty()) null else embedding.copyOf()
            } catch (throwable: Throwable) {
                Log.w(TAG, "Speaker embedding extraction failed", throwable)
                null
            } finally {
                SherpaOnnxBridge.releaseQuietly(stream)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            SherpaOnnxBridge.releaseQuietly(extractor)
        }
    }

    private companion object {
        const val TAG = "SherpaSpeakerEmbedding"
    }
}
