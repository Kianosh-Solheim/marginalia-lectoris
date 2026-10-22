package com.example.reader

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class DaisyPlaybackState(
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val isAudioModeEnabled: Boolean = true, // true = Recorded DAISY Audio / TTS active; false = Silent reading mode
    val useTtsFallback: Boolean = false,
    val hasRecordedAudio: Boolean = false,
    val formatVersion: String = "DAISY 2.02",
    val playbackSpeed: Float = 1.0f,
    val autoTurnPages: Boolean = true,
    val currentClipIndex: Int = 0,
    val totalClips: Int = 0,
    val currentClip: DaisySmilClip? = null,
    val currentClipPositionMs: Long = 0L,
    val currentClipDurationMs: Long = 0L,
    val globalPositionMs: Long = 0L,
    val totalBookDurationMs: Long = 0L,
    val activeAnchorId: String = "",
    val activeFileKey: String = "",
    val activeTextSnippet: String = "",
    val activePageNumber: Int = 1,
    val activeChapterTitle: String = "",
    val errorMessage: String? = null
)

/**
 * State management and audio playback controller for DAISY 2.02 & DAISY 3 books.
 * - Controls `MediaPlayer` playback with Play/Pause, timeline seek, `-15s`/`+15s` skip,
 *   clip-by-clip navigation, and variable speed (`0.75x` to `2.0x`) via `PlaybackParams`.
 * - Synchronizes SMIL `<par>` / `<audio clip-begin="..." clip-end="..."/>` timestamps with
 *   the reader's text highlighting and triggers automatic page turns when the spoken phrase
 *   crosses onto a new page.
 * - Seamlessly falls back to offline TTS (`BritishMaleOfflineTtsManager`) when a DAISY book
 *   or section has no recorded audio files, or lets the user toggle into Silent Reading mode.
 */
class DaisyAudioPlayerManager(
    context: Context,
    private val ttsManager: BritishMaleOfflineTtsManager,
    private val onAutoAdvanceToPage: (targetPageNumber: Int, clip: DaisySmilClip) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bookIndex: DaisyBookIndex? = null
    private var mediaPlayer: MediaPlayer? = null
    private var loadedAudioFilePath: String = ""
    private var syncTickerJob: Job? = null

    private val _state = MutableStateFlow(DaisyPlaybackState())
    val state: StateFlow<DaisyPlaybackState> = _state.asStateFlow()

    companion object {
        val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }

    fun attachBookIndex(index: DaisyBookIndex) {
        val previousClipIdx = _state.value.currentClipIndex
        bookIndex = index
        val clips = index.smilClips
        val safeIdx = previousClipIdx.coerceIn(0, (clips.size - 1).coerceAtLeast(0))
        val initialClip = clips.getOrNull(safeIdx)
        val useTts = !index.hasRecordedAudio

        _state.value = _state.value.copy(
            isLoaded = true,
            hasRecordedAudio = index.hasRecordedAudio,
            useTtsFallback = useTts,
            formatVersion = index.formatVersion,
            currentClipIndex = safeIdx,
            totalClips = clips.size,
            currentClip = initialClip,
            currentClipPositionMs = 0L,
            currentClipDurationMs = initialClip?.durationMs ?: 0L,
            globalPositionMs = initialClip?.globalStartMs ?: 0L,
            totalBookDurationMs = index.totalDurationMs,
            activeAnchorId = initialClip?.textAnchorId.orEmpty(),
            activeFileKey = initialClip?.textFileKey.orEmpty(),
            activeTextSnippet = initialClip?.textSnippet.orEmpty(),
            activePageNumber = initialClip?.pageNumber ?: 1,
            activeChapterTitle = initialClip?.chapterTitle ?: index.title,
            errorMessage = null
        )
    }

    fun setAudioModeEnabled(enabled: Boolean) {
        if (!enabled) {
            pause()
            _state.value = _state.value.copy(
                isAudioModeEnabled = false,
                isPlaying = false,
                activeAnchorId = "",
                activeTextSnippet = ""
            )
        } else {
            val clip = _state.value.currentClip
            _state.value = _state.value.copy(
                isAudioModeEnabled = true,
                activeAnchorId = clip?.textAnchorId.orEmpty(),
                activeFileKey = clip?.textFileKey.orEmpty(),
                activeTextSnippet = clip?.textSnippet.orEmpty(),
                activePageNumber = clip?.pageNumber ?: _state.value.activePageNumber
            )
        }
    }

    fun setUseTtsFallback(useTts: Boolean) {
        val wasPlaying = _state.value.isPlaying
        pause()
        _state.value = _state.value.copy(
            useTtsFallback = useTts || !_state.value.hasRecordedAudio,
            isAudioModeEnabled = true
        )
        if (wasPlaying) {
            play()
        }
    }

    fun setAutoTurnPages(enabled: Boolean) {
        _state.value = _state.value.copy(autoTurnPages = enabled)
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val index = bookIndex ?: return
        if (!_state.value.isAudioModeEnabled) {
            _state.value = _state.value.copy(isAudioModeEnabled = true)
        }
        val clips = index.smilClips
        if (clips.isEmpty()) return

        val clipIdx = _state.value.currentClipIndex.coerceIn(0, clips.size - 1)
        val clip = clips[clipIdx]
        val audioFile = if (clip.audioFile.isNotBlank()) File(clip.audioFile) else null
        if (audioFile != null && !audioFile.exists() && index.sourceArchivePath.isNotBlank()) {
            DaisyParserEngine.ensureAudioFileExtracted(index.sourceArchivePath, audioFile)
        }

        if (_state.value.useTtsFallback || !index.hasRecordedAudio || audioFile == null || !audioFile.exists()) {
            // Speak active SMIL phrase via offline TTS fallback
            _state.value = _state.value.copy(
                isPlaying = true,
                useTtsFallback = true,
                currentClipIndex = clipIdx,
                currentClip = clip,
                activeAnchorId = clip.textAnchorId,
                activeFileKey = clip.textFileKey,
                activeTextSnippet = clip.textSnippet,
                activePageNumber = clip.pageNumber,
                activeChapterTitle = clip.chapterTitle
            )
            ttsManager.setSpeechRate(_state.value.playbackSpeed)
            ttsManager.speakSelection(clip.textSnippet.ifBlank { clip.chapterTitle }, clip.pageNumber)
            startTtsFallbackMonitor()
            return
        }

        playClipWithMediaPlayer(clipIdx, resumeWithinClipOffsetMs = _state.value.currentClipPositionMs)
    }

    fun pause() {
        syncTickerJob?.cancel()
        syncTickerJob = null
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Throwable) {}
        if (_state.value.useTtsFallback) {
            ttsManager.stop()
        }
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun stop() {
        pause()
        _state.value = _state.value.copy(
            isPlaying = false,
            currentClipPositionMs = 0L
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _state.value = _state.value.copy(playbackSpeed = clamped)
        ttsManager.setSpeechRate(clamped)
        val mp = mediaPlayer
        if (mp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val wasPlaying = mp.isPlaying
                val params = (mp.playbackParams ?: PlaybackParams()).setSpeed(clamped)
                mp.playbackParams = params
                if (!wasPlaying && !_state.value.isPlaying) {
                    mp.pause()
                }
            } catch (_: Throwable) {}
        }
    }

    fun nextPhrase() {
        val clips = bookIndex?.smilClips ?: return
        if (clips.isEmpty()) return
        val nextIdx = (_state.value.currentClipIndex + 1).coerceAtMost(clips.size - 1)
        seekToClipIndex(nextIdx, autoPlay = _state.value.isPlaying)
    }

    fun previousPhrase() {
        val clips = bookIndex?.smilClips ?: return
        if (clips.isEmpty()) return
        val prevIdx = (_state.value.currentClipIndex - 1).coerceAtLeast(0)
        seekToClipIndex(prevIdx, autoPlay = _state.value.isPlaying)
    }

    fun seekRelativeMs(deltaMs: Long) {
        val targetGlobal = (_state.value.globalPositionMs + deltaMs)
            .coerceIn(0L, _state.value.totalBookDurationMs.coerceAtLeast(1L))
        seekToGlobalPositionMs(targetGlobal)
    }

    fun seekToGlobalPositionMs(targetGlobalMs: Long) {
        val index = bookIndex ?: return
        val clips = index.smilClips
        if (clips.isEmpty()) return
        val clampedGlobal = targetGlobalMs.coerceIn(0L, index.totalDurationMs.coerceAtLeast(1L))
        val targetIdx = clips.indexOfFirst { clampedGlobal in it.globalStartMs..it.globalEndMs }
            .takeIf { it >= 0 }
            ?: clips.indexOfLast { it.globalStartMs <= clampedGlobal }.coerceAtLeast(0)
        val clip = clips[targetIdx]
        val offsetInClip = (clampedGlobal - clip.globalStartMs).coerceIn(0L, clip.durationMs)
        seekToClipIndex(targetIdx, offsetInClipMs = offsetInClip, autoPlay = _state.value.isPlaying)
    }

    fun seekToNavItem(navItem: DaisyNavItem, autoPlay: Boolean = true) {
        val index = bookIndex ?: return
        val clips = index.smilClips
        if (clips.isEmpty()) return
        val smilName = navItem.smilHref.substringBefore("#").substringAfterLast("/")
        val matchedIdx = clips.indexOfFirst { c ->
            (navItem.smilAnchor.isNotBlank() && (c.parId.equals(navItem.smilAnchor, ignoreCase = true) || c.id.equals(navItem.smilAnchor, ignoreCase = true))) ||
                (navItem.textAnchorId.isNotBlank() && c.textAnchorId.equals(navItem.textAnchorId, ignoreCase = true))
        }.takeIf { it >= 0 } ?: clips.indexOfFirst { c ->
            smilName.isNotBlank() && c.smilFile.equals(smilName, ignoreCase = true)
        }.takeIf { it >= 0 } ?: clips.indexOfFirst { c ->
            c.pageNumber == navItem.pageNumber
        }.coerceAtLeast(0)

        seekToClipIndex(matchedIdx, offsetInClipMs = 0L, autoPlay = autoPlay)
    }

    fun seekToPageOrAnchor(
        pageNumber: Int,
        anchorIds: List<String> = emptyList(),
        textSnippet: String = "",
        autoPlay: Boolean = false
    ) {
        val index = bookIndex ?: return
        val clips = index.smilClips
        if (clips.isEmpty()) return

        val byAnchor = if (anchorIds.isNotEmpty()) {
            clips.indexOfFirst { c ->
                c.textAnchorId.isNotBlank() && anchorIds.any { it.equals(c.textAnchorId, ignoreCase = true) }
            }.takeIf { it >= 0 }
        } else null

        val bySnippet = if (byAnchor == null && textSnippet.trim().length >= 10) {
            val probe = textSnippet.trim().take(32)
            clips.indexOfFirst { c ->
                c.textSnippet.contains(probe, ignoreCase = true) || probe.contains(c.textSnippet.take(24), ignoreCase = true)
            }.takeIf { it >= 0 }
        } else null

        val byPage = clips.indexOfFirst { it.pageNumber == pageNumber }.takeIf { it >= 0 }
        val resolvedIdx = byAnchor ?: bySnippet ?: byPage ?: return
        seekToClipIndex(resolvedIdx, offsetInClipMs = 0L, autoPlay = autoPlay)
    }

    fun seekToClipIndex(clipIndex: Int, offsetInClipMs: Long = 0L, autoPlay: Boolean = _state.value.isPlaying) {
        val index = bookIndex ?: return
        val clips = index.smilClips
        if (clips.isEmpty()) return
        val safeIdx = clipIndex.coerceIn(0, clips.size - 1)
        val clip = clips[safeIdx]
        val clampedOffset = offsetInClipMs.coerceIn(0L, clip.durationMs)
        val prevPage = _state.value.activePageNumber

        _state.value = _state.value.copy(
            currentClipIndex = safeIdx,
            currentClip = clip,
            currentClipPositionMs = clampedOffset,
            currentClipDurationMs = clip.durationMs,
            globalPositionMs = clip.globalStartMs + clampedOffset,
            activeAnchorId = if (_state.value.isAudioModeEnabled) clip.textAnchorId else "",
            activeFileKey = clip.textFileKey,
            activeTextSnippet = if (_state.value.isAudioModeEnabled) clip.textSnippet else "",
            activePageNumber = clip.pageNumber,
            activeChapterTitle = clip.chapterTitle
        )

        if (_state.value.autoTurnPages && clip.pageNumber != prevPage && clip.pageNumber >= 1) {
            onAutoAdvanceToPage(clip.pageNumber, clip)
        }

        if (autoPlay) {
            if (_state.value.useTtsFallback || !index.hasRecordedAudio) {
                play()
            } else {
                playClipWithMediaPlayer(safeIdx, resumeWithinClipOffsetMs = clampedOffset)
            }
        } else {
            val mp = mediaPlayer
            if (mp != null && loadedAudioFilePath == clip.audioFile) {
                try {
                    mp.seekTo((clip.clipBeginMs + clampedOffset).toInt())
                } catch (_: Throwable) {}
            }
        }
    }

    private fun playClipWithMediaPlayer(clipIndex: Int, resumeWithinClipOffsetMs: Long = 0L) {
        val index = bookIndex ?: return
        val clips = index.smilClips
        if (clipIndex !in clips.indices) {
            stop()
            return
        }
        val clip = clips[clipIndex]
        val audioFile = File(clip.audioFile)
        if (!audioFile.exists() && clip.audioFile.isNotBlank() && index.sourceArchivePath.isNotBlank()) {
            DaisyParserEngine.ensureAudioFileExtracted(index.sourceArchivePath, audioFile)
        }
        if (!audioFile.exists()) {
            // Automatic fallback to TTS if audio file is missing
            _state.value = _state.value.copy(useTtsFallback = true)
            play()
            return
        }

        val prevPage = _state.value.activePageNumber
        _state.value = _state.value.copy(
            isPlaying = true,
            isAudioModeEnabled = true,
            currentClipIndex = clipIndex,
            currentClip = clip,
            currentClipPositionMs = resumeWithinClipOffsetMs,
            currentClipDurationMs = clip.durationMs,
            globalPositionMs = clip.globalStartMs + resumeWithinClipOffsetMs,
            activeAnchorId = clip.textAnchorId,
            activeFileKey = clip.textFileKey,
            activeTextSnippet = clip.textSnippet,
            activePageNumber = clip.pageNumber,
            activeChapterTitle = clip.chapterTitle,
            errorMessage = null
        )

        if (_state.value.autoTurnPages && clip.pageNumber != prevPage && clip.pageNumber >= 1) {
            onAutoAdvanceToPage(clip.pageNumber, clip)
        }

        try {
            val targetAudioSeekMs = (clip.clipBeginMs + resumeWithinClipOffsetMs).toInt()
            if (mediaPlayer == null || loadedAudioFilePath != audioFile.absolutePath) {
                mediaPlayer?.release()
                val mp = MediaPlayer()
                mp.setDataSource(audioFile.absolutePath)
                mp.prepare()
                loadedAudioFilePath = audioFile.absolutePath
                mediaPlayer = mp
            }
            val mp = mediaPlayer ?: return
            mp.seekTo(targetAudioSeekMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val params = (mp.playbackParams ?: PlaybackParams()).setSpeed(_state.value.playbackSpeed)
                    mp.playbackParams = params
                } catch (_: Throwable) {}
            }
            if (!mp.isPlaying) {
                mp.start()
            }
            startMediaPlayerTicker()
        } catch (e: Throwable) {
            e.printStackTrace()
            _state.value = _state.value.copy(
                errorMessage = "Audio playback error (${e.localizedMessage}), switching to TTS fallback",
                useTtsFallback = true
            )
            play()
        }
    }

    private fun startMediaPlayerTicker() {
        syncTickerJob?.cancel()
        syncTickerJob = scope.launch {
            while (isActive && _state.value.isPlaying && !_state.value.useTtsFallback) {
                val mp = mediaPlayer
                val index = bookIndex
                if (mp != null && index != null) {
                    val currentClip = _state.value.currentClip
                    if (currentClip != null) {
                        val posMs = try { mp.currentPosition.toLong() } catch (_: Throwable) { currentClip.clipBeginMs }
                        val isTrackFinished = !mp.isPlaying && posMs >= (currentClip.clipEndMs - 120L)

                        if (posMs >= currentClip.clipEndMs || isTrackFinished) {
                            // Advance to next SMIL clip!
                            val nextIdx = _state.value.currentClipIndex + 1
                            if (nextIdx < index.smilClips.size) {
                                val nextClip = index.smilClips[nextIdx]
                                val isContiguousSameAudio = nextClip.audioFile == currentClip.audioFile &&
                                    kotlin.math.abs(nextClip.clipBeginMs - currentClip.clipEndMs) <= 250L
                                if (isContiguousSameAudio && mp.isPlaying) {
                                    // Seamless transition without re-seeking MediaPlayer!
                                    val prevPage = _state.value.activePageNumber
                                    _state.value = _state.value.copy(
                                        currentClipIndex = nextIdx,
                                        currentClip = nextClip,
                                        currentClipPositionMs = (posMs - nextClip.clipBeginMs).coerceAtLeast(0L),
                                        currentClipDurationMs = nextClip.durationMs,
                                        globalPositionMs = nextClip.globalStartMs,
                                        activeAnchorId = nextClip.textAnchorId,
                                        activeFileKey = nextClip.textFileKey,
                                        activeTextSnippet = nextClip.textSnippet,
                                        activePageNumber = nextClip.pageNumber,
                                        activeChapterTitle = nextClip.chapterTitle
                                    )
                                    if (_state.value.autoTurnPages && nextClip.pageNumber != prevPage && nextClip.pageNumber >= 1) {
                                        onAutoAdvanceToPage(nextClip.pageNumber, nextClip)
                                    }
                                } else {
                                    playClipWithMediaPlayer(nextIdx, resumeWithinClipOffsetMs = 0L)
                                    break
                                }
                            } else {
                                stop()
                                break
                            }
                        } else {
                            val offsetInClip = (posMs - currentClip.clipBeginMs).coerceIn(0L, currentClip.durationMs)
                            _state.value = _state.value.copy(
                                currentClipPositionMs = offsetInClip,
                                globalPositionMs = currentClip.globalStartMs + offsetInClip
                            )
                        }
                    }
                }
                delay(75L)
            }
        }
    }

    private fun startTtsFallbackMonitor() {
        syncTickerJob?.cancel()
        syncTickerJob = scope.launch {
            delay(350L)
            while (isActive && _state.value.isPlaying && _state.value.useTtsFallback) {
                val ttsPlaying = ttsManager.uiState.value.isPlaying
                if (!ttsPlaying) {
                    val index = bookIndex
                    val nextIdx = _state.value.currentClipIndex + 1
                    if (index != null && nextIdx < index.smilClips.size) {
                        val nextClip = index.smilClips[nextIdx]
                        val prevPage = _state.value.activePageNumber
                        _state.value = _state.value.copy(
                            currentClipIndex = nextIdx,
                            currentClip = nextClip,
                            currentClipPositionMs = 0L,
                            currentClipDurationMs = nextClip.durationMs,
                            globalPositionMs = nextClip.globalStartMs,
                            activeAnchorId = nextClip.textAnchorId,
                            activeFileKey = nextClip.textFileKey,
                            activeTextSnippet = nextClip.textSnippet,
                            activePageNumber = nextClip.pageNumber,
                            activeChapterTitle = nextClip.chapterTitle
                        )
                        if (_state.value.autoTurnPages && nextClip.pageNumber != prevPage && nextClip.pageNumber >= 1) {
                            onAutoAdvanceToPage(nextClip.pageNumber, nextClip)
                        }
                        ttsManager.speakSelection(nextClip.textSnippet.ifBlank { nextClip.chapterTitle }, nextClip.pageNumber)
                        delay(400L)
                    } else {
                        _state.value = _state.value.copy(isPlaying = false)
                        break
                    }
                }
                delay(200L)
            }
        }
    }

    fun release() {
        syncTickerJob?.cancel()
        syncTickerJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {}
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null
        scope.cancel()
    }
}
