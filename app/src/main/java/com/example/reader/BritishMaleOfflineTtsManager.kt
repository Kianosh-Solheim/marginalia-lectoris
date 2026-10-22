package com.example.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class TtsVoiceDescriptor(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val isOffline: Boolean,
    val isMale: Boolean,
    val isBritish: Boolean,
    val isNorwegian: Boolean = false,
    val qualityScore: Int
)

data class TtsUiState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentenceText: String = "",
    val speechRate: Float = 1.05f,
    val pitch: Float = 0.92f,
    val autoAdvancePage: Boolean = true,
    val activeLanguage: String = "en-GB", // "en-GB" (British English) or "nb-NO" (Norwegian)
    val activeVoiceName: String = "British English Male (Offline)",
    val activeVoiceId: String = "",
    val availableVoices: List<TtsVoiceDescriptor> = emptyList(),
    val isOfflineVoiceActive: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Fast, offline-first Male British English (`en-GB`) & Natural Norwegian (`nb-NO` / `no-NO`)
 * Text-To-Speech controller for Lector Meus.
 *
 * - Supports both **British English Male (`en-GB`)** and **Natural Norwegian (`nb-NO` / `no-NO`)** offline synthesis.
 * - Automatically detects whether a page or selection is in Norwegian (via `æ`, `ø`, `å` or common Norwegian words)
 *   or respects the user's explicit language selection (`🇬🇧 EN-GB` / `🇳🇴 NORSK`).
 * - Prioritizes local embedded neural voices (`!voice.isNetworkConnectionRequired` / `local` / `embeddedTts`)
 *   for zero-latency offline playback.
 * - Splits page text into responsive sentence chunks so playback starts instantaneously,
 *   supports sentence-by-sentence skipping, and automatically turns to the next page when a page finishes speaking.
 */
class BritishMaleOfflineTtsManager(
    context: Context,
    initialRate: Float = 1.05f,
    initialPitch: Float = 0.92f,
    initialAutoAdvance: Boolean = true,
    initialPreferredVoiceId: String = "",
    initialLanguage: String = "en-GB",
    private val onPageFinishedAutoAdvance: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var isEngineInitialized = false

    private val _uiState = MutableStateFlow(
        TtsUiState(
            speechRate = initialRate.coerceIn(0.5f, 2.5f),
            pitch = initialPitch.coerceIn(0.6f, 1.5f),
            autoAdvancePage = initialAutoAdvance,
            activeLanguage = normalizeLanguageCode(initialLanguage),
            activeVoiceId = initialPreferredVoiceId,
            activeVoiceName = if (normalizeLanguageCode(initialLanguage) == "nb-NO") {
                "Norsk • Natural Offline (nb-NO)"
            } else {
                "British English Male (Offline)"
            }
        )
    )
    val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

    private var currentSentences: List<String> = emptyList()
    private var currentPageNumber: Int = 1
    private var pendingSpeakOnInit: Boolean = false
    private var userManuallyLockedLanguage: Boolean = false

    // Known Google TTS & Samsung/Sherpa/System British English & Norwegian Male/Natural voice identifiers
    private val knownMaleMarkers = listOf(
        "gbd", // Google UK English Male (Daniel / local neural)
        "rjs", // Google UK English Male (Oliver / local neural)
        "fis", // Google UK English Male
        "nb-no-x-no", // Google Norwegian neural voices
        " Arthur",
        "Daniel",
        "Oliver",
        "George",
        "Malcolm",
        "Henrik",
        "Finn",
        "Magnus",
        "male",
        "#male"
    )

    private val knownFemaleMarkers = listOf(
        "gba",
        "gbc",
        "gbg",
        "fema",
        "female",
        "#female",
        "Kate",
        "Serena",
        "Martha",
        "Hazel"
    )

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    isEngineInitialized = true
                    configureOfflineVoice(
                        engine = engine,
                        languageCode = _uiState.value.activeLanguage,
                        preferredVoiceId = _uiState.value.activeVoiceId
                    )
                    setupProgressListener(engine)
                    _uiState.value = _uiState.value.copy(
                        isReady = true,
                        errorMessage = null
                    )
                    if (pendingSpeakOnInit && currentSentences.isNotEmpty()) {
                        pendingSpeakOnInit = false
                        speakSentenceAt(_uiState.value.currentSentenceIndex)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isReady = false,
                        errorMessage = "Offline TTS engine unavailable on this device"
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isReady = false,
                errorMessage = "TTS initialization error: ${e.localizedMessage ?: "Unknown"}"
            )
        }
    }

    private fun configureOfflineVoice(
        engine: TextToSpeech,
        languageCode: String,
        preferredVoiceId: String
    ) {
        val normalizedLang = normalizeLanguageCode(languageCode)
        val wantNorwegian = normalizedLang == "nb-NO"

        try {
            if (wantNorwegian) {
                val nbLocale = Locale.forLanguageTag("nb-NO")
                val r1 = engine.setLanguage(nbLocale)
                if (r1 == TextToSpeech.LANG_MISSING_DATA || r1 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val noLocale = Locale.forLanguageTag("no-NO")
                    val r2 = engine.setLanguage(noLocale)
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale("no"))
                    }
                }
            } else {
                val ukLocale = Locale.UK
                val langResult = engine.setLanguage(ukLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.ENGLISH)
                }
            }

            val rawVoices: Set<Voice> = try {
                engine.voices ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }

            val descriptors = rawVoices
                .filter { v ->
                    val lang = v.locale?.language?.lowercase(Locale.ROOT) ?: ""
                    val nameLower = (v.name ?: "").lowercase(Locale.ROOT)
                    if (wantNorwegian) {
                        lang in listOf("nb", "no", "nn", "nob", "nno", "nor") ||
                            nameLower.contains("nb-no") ||
                            nameLower.contains("no-no") ||
                            nameLower.contains("nn-no")
                    } else {
                        lang == "en" || lang == "eng"
                    }
                }
                .map { v -> describeVoice(v) }
                .sortedWith(
                    if (wantNorwegian) {
                        compareByDescending<TtsVoiceDescriptor> { it.isNorwegian && it.isOffline && it.isMale }
                            .thenByDescending { it.isNorwegian && it.isOffline }
                            .thenByDescending { it.isNorwegian && it.isMale }
                            .thenByDescending { it.isOffline }
                            .thenByDescending { it.qualityScore }
                    } else {
                        compareByDescending<TtsVoiceDescriptor> { it.isBritish && it.isOffline && it.isMale }
                            .thenByDescending { it.isBritish && it.isOffline }
                            .thenByDescending { it.isBritish && it.isMale }
                            .thenByDescending { it.isOffline && it.isMale }
                            .thenByDescending { it.isBritish }
                            .thenByDescending { it.isOffline }
                            .thenByDescending { it.qualityScore }
                    }
                )

            val chosenDescriptor = if (preferredVoiceId.isNotBlank()) {
                descriptors.firstOrNull { it.id == preferredVoiceId } ?: descriptors.firstOrNull()
            } else {
                descriptors.firstOrNull()
            }

            if (chosenDescriptor != null) {
                val matchingVoice = rawVoices.firstOrNull { it.name == chosenDescriptor.id }
                if (matchingVoice != null) {
                    engine.voice = matchingVoice
                }
                _uiState.value = _uiState.value.copy(
                    activeLanguage = normalizedLang,
                    activeVoiceId = chosenDescriptor.id,
                    activeVoiceName = chosenDescriptor.displayName,
                    isOfflineVoiceActive = chosenDescriptor.isOffline,
                    availableVoices = descriptors
                )
            } else {
                val fallbackName = if (wantNorwegian) {
                    "Norsk • Natural Offline (nb-NO)"
                } else {
                    "British English Male (en-GB Offline)"
                }
                _uiState.value = _uiState.value.copy(
                    activeLanguage = normalizedLang,
                    activeVoiceId = if (wantNorwegian) "nb-NO-default" else "en-GB-default",
                    activeVoiceName = fallbackName,
                    isOfflineVoiceActive = true,
                    availableVoices = emptyList()
                )
            }

            engine.setSpeechRate(_uiState.value.speechRate)
            engine.setPitch(_uiState.value.pitch)
        } catch (_: Exception) {
            try {
                engine.setSpeechRate(_uiState.value.speechRate)
                engine.setPitch(_uiState.value.pitch)
            } catch (_: Exception) {}
        }
    }

    private fun describeVoice(voice: Voice): TtsVoiceDescriptor {
        val id = voice.name ?: "unknown"
        val lowerId = id.lowercase(Locale.ROOT)
        val features = voice.features ?: emptySet()
        val featuresJoined = features.joinToString(" ").lowercase(Locale.ROOT)
        val lang = voice.locale?.language?.lowercase(Locale.ROOT) ?: ""
        val country = voice.locale?.country?.uppercase(Locale.ROOT) ?: ""

        val isNorwegian = lang in listOf("nb", "no", "nn", "nob", "nno", "nor") ||
            country == "NO" ||
            lowerId.contains("nb-no") ||
            lowerId.contains("no-no") ||
            lowerId.contains("nn-no")

        val isBritish = !isNorwegian && (
            country == "GB" || country == "UK" || lowerId.contains("en-gb") || lowerId.contains("en_gb")
        )
        val isOffline = !voice.isNetworkConnectionRequired || lowerId.contains("local") || features.contains("embeddedTts")

        val hasFemaleMarker = knownFemaleMarkers.any { marker ->
            lowerId.contains(marker.lowercase(Locale.ROOT)) || featuresJoined.contains(marker.lowercase(Locale.ROOT))
        }
        val hasMaleMarker = knownMaleMarkers.any { marker ->
            lowerId.contains(marker.lowercase(Locale.ROOT)) || featuresJoined.contains(marker.lowercase(Locale.ROOT))
        }
        val isMale = hasMaleMarker || !hasFemaleMarker

        var score = voice.quality
        if (isNorwegian) score += 1200
        if (isBritish) score += 1000
        if (isOffline) score += 800
        if (isMale) score += 600
        if (lowerId.contains("gbd") || lowerId.contains("rjs") || lowerId.contains("fis")) score += 500
        if (lowerId.contains("local") || lowerId.contains("x-")) score += 300

        val regionLabel = when {
            isNorwegian -> "Norsk"
            isBritish -> "UK English"
            country == "AU" -> "AU English"
            country == "IE" -> "IE English"
            country == "US" -> "US English"
            else -> "English"
        }
        val genderLabel = if (isMale) "Male" else "Natural"
        val modeLabel = if (isOffline) "Offline" else "Network"
        val shortCode = when {
            lowerId.contains("gbd") -> "Daniel"
            lowerId.contains("rjs") -> "Oliver"
            lowerId.contains("fis") -> "Arthur"
            isNorwegian -> id.substringAfterLast("-").take(8).uppercase(Locale.ROOT).ifBlank { "Bokmål" }
            else -> id.substringAfterLast("-").take(8).uppercase(Locale.ROOT)
        }

        val displayName = "$regionLabel $genderLabel • $shortCode ($modeLabel)"
        return TtsVoiceDescriptor(
            id = id,
            displayName = displayName,
            localeTag = voice.locale?.toLanguageTag() ?: if (isNorwegian) "nb-NO" else "en-GB",
            isOffline = isOffline,
            isMale = isMale,
            isBritish = isBritish,
            isNorwegian = isNorwegian,
            qualityScore = score
        )
    }

    private fun setupProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val idx = utteranceId?.removePrefix("utt_")?.toIntOrNull() ?: return
                scope.launch {
                    val sentence = currentSentences.getOrNull(idx) ?: ""
                    _uiState.value = _uiState.value.copy(
                        isPlaying = true,
                        isPaused = false,
                        currentSentenceIndex = idx,
                        currentSentenceText = sentence
                    )
                }
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.removePrefix("utt_")?.toIntOrNull() ?: return
                scope.launch {
                    if (!_uiState.value.isPlaying) return@launch
                    val nextIdx = idx + 1
                    if (nextIdx < currentSentences.size) {
                        speakSentenceAt(nextIdx)
                    } else {
                        if (_uiState.value.autoAdvancePage) {
                            onPageFinishedAutoAdvance()
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isPlaying = false,
                                isPaused = false
                            )
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch {
                    _uiState.value = _uiState.value.copy(
                        isPlaying = false,
                        isPaused = true
                    )
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                scope.launch {
                    _uiState.value = _uiState.value.copy(
                        isPlaying = false,
                        isPaused = true
                    )
                }
            }
        })
    }

    /**
     * Switches between British English (`en-GB`) and Norwegian (`nb-NO`) offline voice profiles.
     */
    fun setLanguage(languageCode: String, userInitiated: Boolean = true) {
        if (userInitiated) {
            userManuallyLockedLanguage = true
        }
        val normalized = normalizeLanguageCode(languageCode)
        val defaultVoiceName = if (normalized == "nb-NO") {
            "Norsk • Natural Offline (nb-NO)"
        } else {
            "British English Male (Offline)"
        }
        _uiState.value = _uiState.value.copy(
            activeLanguage = normalized,
            activeVoiceId = "",
            activeVoiceName = defaultVoiceName
        )
        val engine = tts
        if (engine != null && isEngineInitialized) {
            configureOfflineVoice(engine, normalized, "")
            if (_uiState.value.isPlaying) {
                speakSentenceAt(_uiState.value.currentSentenceIndex)
            }
        }
    }

    /**
     * Loads the text of the active page (or selection) into fast sentence chunks.
     * Automatically switches to Norwegian (`nb-NO`) if Norwegian characters/words are detected
     * and the user hasn't manually locked a language during this session.
     */
    fun loadPageText(
        rawText: String,
        pageNumber: Int,
        startImmediately: Boolean = false
    ) {
        if (!userManuallyLockedLanguage && rawText.length >= 16) {
            val detectedLang = detectLanguageFromText(rawText)
            if (detectedLang != null && detectedLang != _uiState.value.activeLanguage) {
                setLanguage(detectedLang, userInitiated = false)
            }
        }

        val sentences = splitIntoNaturalSentences(rawText)
        val isSamePage = pageNumber == currentPageNumber && currentSentences == sentences
        currentPageNumber = pageNumber
        currentSentences = sentences

        val targetIndex = if (isSamePage) {
            _uiState.value.currentSentenceIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        } else {
            0
        }

        _uiState.value = _uiState.value.copy(
            currentSentenceIndex = targetIndex,
            totalSentences = sentences.size,
            currentSentenceText = sentences.getOrNull(targetIndex) ?: ""
        )

        if (startImmediately) {
            if (sentences.isNotEmpty()) {
                speakSentenceAt(targetIndex)
            } else if (_uiState.value.autoAdvancePage) {
                onPageFinishedAutoAdvance()
            }
        }
    }

    /**
     * Speaks a specific quote or selection immediately in British English or Norwegian voice.
     */
    fun speakSelection(quote: String, pageNumber: Int) {
        if (quote.isBlank()) return
        loadPageText(quote, pageNumber, startImmediately = true)
    }

    fun togglePlayPause(currentPageRawText: String, pageNumber: Int) {
        val state = _uiState.value
        if (state.isPlaying) {
            pause()
        } else {
            if (currentSentences.isEmpty() || currentPageNumber != pageNumber) {
                loadPageText(currentPageRawText, pageNumber, startImmediately = true)
            } else {
                val resumeIdx = state.currentSentenceIndex.coerceIn(0, (currentSentences.size - 1).coerceAtLeast(0))
                speakSentenceAt(resumeIdx)
            }
        }
    }

    fun playFromStartOfPage(currentPageRawText: String, pageNumber: Int) {
        currentPageNumber = -1
        loadPageText(currentPageRawText, pageNumber, startImmediately = true)
    }

    fun pause() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isPaused = true
        )
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isPaused = false,
            currentSentenceIndex = 0,
            currentSentenceText = currentSentences.firstOrNull() ?: ""
        )
    }

    fun nextSentence() {
        if (currentSentences.isEmpty()) return
        val nextIdx = (_uiState.value.currentSentenceIndex + 1)
        if (nextIdx < currentSentences.size) {
            speakSentenceAt(nextIdx)
        } else if (_uiState.value.autoAdvancePage) {
            onPageFinishedAutoAdvance()
        }
    }

    fun previousSentence() {
        if (currentSentences.isEmpty()) return
        val prevIdx = (_uiState.value.currentSentenceIndex - 1).coerceAtLeast(0)
        speakSentenceAt(prevIdx)
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.5f)
        _uiState.value = _uiState.value.copy(speechRate = clamped)
        try {
            tts?.setSpeechRate(clamped)
        } catch (_: Exception) {}
        if (_uiState.value.isPlaying) {
            speakSentenceAt(_uiState.value.currentSentenceIndex)
        }
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.6f, 1.5f)
        _uiState.value = _uiState.value.copy(pitch = clamped)
        try {
            tts?.setPitch(clamped)
        } catch (_: Exception) {}
        if (_uiState.value.isPlaying) {
            speakSentenceAt(_uiState.value.currentSentenceIndex)
        }
    }

    fun setAutoAdvancePage(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoAdvancePage = enabled)
    }

    fun selectVoice(voiceId: String) {
        val engine = tts ?: return
        configureOfflineVoice(engine, _uiState.value.activeLanguage, voiceId)
        if (_uiState.value.isPlaying) {
            speakSentenceAt(_uiState.value.currentSentenceIndex)
        }
    }

    fun openSystemTtsVoiceInstaller() {
        try {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
        } catch (_: Exception) {
            try {
                val settingsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(settingsIntent)
            } catch (_: Exception) {}
        }
    }

    private fun speakSentenceAt(index: Int) {
        if (currentSentences.isEmpty()) return
        val safeIdx = index.coerceIn(0, currentSentences.size - 1)
        val sentence = currentSentences[safeIdx]

        _uiState.value = _uiState.value.copy(
            isPlaying = true,
            isPaused = false,
            currentSentenceIndex = safeIdx,
            totalSentences = currentSentences.size,
            currentSentenceText = sentence
        )

        val engine = tts
        if (engine == null || !isEngineInitialized) {
            pendingSpeakOnInit = true
            return
        }

        try {
            engine.setSpeechRate(_uiState.value.speechRate)
            engine.setPitch(_uiState.value.pitch)
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utt_$safeIdx")
                putString("networkTts", "false")
                putBoolean("embeddedTts", true)
            }
            engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "utt_$safeIdx")
        } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        scope.cancel()
    }

    companion object {
        fun normalizeLanguageCode(code: String): String {
            val lower = code.trim().lowercase(Locale.ROOT)
            return if (lower.startsWith("nb") || lower.startsWith("no") || lower.startsWith("nn")) {
                "nb-NO"
            } else {
                "en-GB"
            }
        }

        /**
         * Fast offline heuristic to detect whether prose is Norwegian (`nb-NO` / `nn-NO`) vs English (`en-GB`).
         */
        fun detectLanguageFromText(text: String): String? {
            if (text.isBlank()) return null
            val sample = text.take(1200)
            // Norwegian letters æ, ø, å
            val norwegianCharCount = sample.count { it in "æøåÆØÅ" }
            if (norwegianCharCount >= 2) return "nb-NO"

            // Common high-frequency Norwegian words vs English words
            val words = sample.lowercase(Locale.ROOT)
                .split(Regex("[^a-zæøå]+"))
                .filter { it.isNotEmpty() }
            if (words.size < 4) return null

            val norwegianMarkers = setOf(
                "og", "det", "som", "ikke", "jeg", "har", "med", "for", "til", "av",
                "på", "en", "et", "den", "de", "han", "hun", "var", "ble", "skal", "eller", "etter"
            )
            val englishMarkers = setOf(
                "the", "and", "that", "with", "from", "this", "have", "was", "were", "which", "their", "would"
            )

            val noHits = words.count { it in norwegianMarkers }
            val enHits = words.count { it in englishMarkers }
            return when {
                noHits >= 3 && noHits > enHits * 2 -> "nb-NO"
                enHits >= 2 && norwegianCharCount == 0 -> "en-GB"
                else -> null
            }
        }

        /**
         * Splits raw page paragraphs into natural, fast-to-synthesize British English & Norwegian sentences
         * while avoiding false splits on common English & Norwegian abbreviations
         * (Mr., Mrs., Dr., St., Prof., f.eks., bl.a., osv., dvs., m.m., kap., nr.).
         */
        fun splitIntoNaturalSentences(rawText: String): List<String> {
            if (rawText.isBlank()) return emptyList()

            val cleaned = rawText
                .replace(Regex("(?m)^##\\s+"), "") // Strip markdown heading hashes
                .replace(Regex("\\[(\\d+)]"), "")  // Strip inline footnote brackets [1] so TTS doesn't read numbers mid-sentence
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.isEmpty()) return emptyList()

            // Protect common British English & Norwegian abbreviations before sentence boundary splitting
            val protectedText = cleaned
                .replace(
                    Regex("\\b(Mr|Mrs|Ms|Dr|Prof|St|Jr|Sr|vs|etc|Fig|No|Vol|Ch|pp|nr|kap|osv|dvs|ca|kl|kr|mfl|pga|jf)\\.", RegexOption.IGNORE_CASE),
                    "$1<DOT>"
                )
                .replace(
                    Regex("\\b(e\\.g|i\\.e|f\\.eks|bl\\.a|m\\.m|o\\.l|d\\.e)\\.", RegexOption.IGNORE_CASE),
                    "$1<DOT>"
                )

            val rawParts = protectedText.split(Regex("(?<=[.!?])\\s+"))
            val result = mutableListOf<String>()

            for (part in rawParts) {
                val restored = part.replace("<DOT>", ".").trim()
                if (restored.isEmpty()) continue
                if (restored.length > 280 && restored.contains(Regex("[;—]"))) {
                    val subParts = restored.split(Regex("(?<=[;—])\\s+"))
                    for (sub in subParts) {
                        val trimmedSub = sub.trim()
                        if (trimmedSub.isNotEmpty()) result.add(trimmedSub)
                    }
                } else {
                    result.add(restored)
                }
            }

            return if (result.isNotEmpty()) result else listOf(cleaned)
        }
    }
}
