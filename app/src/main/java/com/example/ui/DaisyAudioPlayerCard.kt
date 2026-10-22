package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.DaisyAudioPlayerManager
import com.example.reader.DaisyParserEngine
import com.example.reader.DaisyPlaybackState
import java.util.Locale

/**
 * Playback Control Component for DAISY 2.02 & DAISY 3 audio tracks:
 * - Play / Pause, Previous / Next SMIL phrase, -15s / +15s seek
 * - Interactive timeline seek slider across the full DAISY book duration
 * - Variable playback speed chips (0.75x, 1.0x, 1.25x, 1.5x, 1.75x, 2.0x)
 * - Seamless toggle between Recorded DAISY Audio, Offline TTS Fallback, and Silent Reading mode
 * - Auto-turn pages toggle to follow active SMIL phrase highlights
 */
@Composable
fun DaisyPlaybackControlBar(
    daisyState: DaisyPlaybackState,
    activeTheme: ReaderTheme,
    isMinimized: Boolean,
    onToggleMinimized: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousPhrase: () -> Unit,
    onNextPhrase: () -> Unit,
    onSeekRelativeMs: (Long) -> Unit,
    onSeekGlobalMs: (Long) -> Unit,
    onChangePlaybackSpeed: (Float) -> Unit,
    onSelectAudioMode: (audioEnabled: Boolean, useTtsFallback: Boolean) -> Unit,
    onToggleAutoTurnPages: (Boolean) -> Unit,
    onCloseBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFF7C3AED)
    val amberAccent = if (activeTheme == ReaderTheme.DARK) Color(0xFFFBBF24) else Color(0xFFD97706)

    if (isMinimized) {
        Surface(
            modifier = modifier
                .border(BorderStroke(1.5.dp, accentColor), RoundedCornerShape(24.dp))
                .testTag("daisy_minimized_pill"),
            shape = RoundedCornerShape(24.dp),
            color = activeTheme.barColor,
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = "DAISY Audio",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${DaisyParserEngine.formatTimeMs(daisyState.globalPositionMs)} • ${String.format(Locale.US, "%.2gx", daisyState.playbackSpeed)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = activeTheme.textColor
                )
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                        .testTag("daisy_pill_play_pause_btn")
                ) {
                    Icon(
                        imageVector = if (daisyState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (daisyState.isPlaying) "Pause DAISY Audio" else "Play DAISY Audio",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onToggleMinimized,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("daisy_pill_expand_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandLess,
                        contentDescription = "Expand DAISY Player",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        return
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember(daisyState.globalPositionMs) {
        mutableFloatStateOf(daisyState.globalPositionMs.toFloat())
    }
    val totalDurationFloat = daisyState.totalBookDurationMs.coerceAtLeast(1000L).toFloat()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.5.dp, accentColor), RoundedCornerShape(16.dp))
            .testTag("daisy_playback_control_card"),
        shape = RoundedCornerShape(16.dp),
        color = activeTheme.barColor,
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Header: Format Badge + Active Chapter/SMIL Snippet + Minimize/Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = accentColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = daisyState.formatVersion,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = if (!daisyState.isAudioModeEnabled) {
                            "Silent Reading Mode (Audio Paused)"
                        } else if (daisyState.useTtsFallback) {
                            "Offline TTS Narration • Phrase ${daisyState.currentClipIndex + 1}/${daisyState.totalClips.coerceAtLeast(1)}"
                        } else {
                            "SMIL Phrase ${daisyState.currentClipIndex + 1}/${daisyState.totalClips.coerceAtLeast(1)} • p. ${daisyState.activePageNumber}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = activeTheme.secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onToggleMinimized,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("daisy_minimize_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Minimize DAISY Player",
                            tint = activeTheme.textColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onCloseBar,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("daisy_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close DAISY Player",
                            tint = activeTheme.textColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Active synchronized SMIL phrase preview banner
            if (daisyState.activeTextSnippet.isNotBlank() && daisyState.isAudioModeEnabled) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeTheme == ReaderTheme.DARK) Color(0xFF78350F).copy(alpha = 0.55f) else Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, amberAccent.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "“${daisyState.activeTextSnippet}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeTheme == ReaderTheme.DARK) Color(0xFFFEF3C7) else Color(0xFF18181B),
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("daisy_active_phrase_banner")
                    )
                }
            }

            // Reading Mode Selector: Recorded DAISY Audio / Offline TTS Fallback / Silent Reading
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (daisyState.hasRecordedAudio) {
                    FilterChip(
                        selected = daisyState.isAudioModeEnabled && !daisyState.useTtsFallback,
                        onClick = { onSelectAudioMode(true, false) },
                        label = { Text("Recorded DAISY Audio", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(15.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White
                        ),
                        modifier = Modifier.testTag("daisy_mode_recorded_chip")
                    )
                }
                FilterChip(
                    selected = daisyState.isAudioModeEnabled && daisyState.useTtsFallback,
                    onClick = { onSelectAudioMode(true, true) },
                    label = {
                        Text(
                            if (daisyState.hasRecordedAudio) "TTS Voice" else "TTS Fallback (No MP3)",
                            fontSize = 11.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2563EB),
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    modifier = Modifier.testTag("daisy_mode_tts_chip")
                )
                FilterChip(
                    selected = !daisyState.isAudioModeEnabled,
                    onClick = { onSelectAudioMode(false, false) },
                    label = { Text("Read Silently", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF27272A),
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    modifier = Modifier.testTag("daisy_mode_silent_chip")
                )
            }

            // Timeline Seek Bar & Monospace Timecodes
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = if (isScrubbing) scrubMs else daisyState.globalPositionMs.toFloat().coerceIn(0f, totalDurationFloat),
                    onValueChange = {
                        isScrubbing = true
                        scrubMs = it
                    },
                    onValueChangeFinished = {
                        isScrubbing = false
                        onSeekGlobalMs(scrubMs.toLong())
                    },
                    valueRange = 0f..totalDurationFloat,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = activeTheme.dividerColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .testTag("daisy_timeline_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = DaisyParserEngine.formatTimeMs(
                            if (isScrubbing) scrubMs.toLong() else daisyState.globalPositionMs
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = activeTheme.textColor
                    )
                    Text(
                        text = daisyState.activeChapterTitle.ifBlank { "DAISY Timeline" },
                        style = MaterialTheme.typography.labelSmall,
                        color = activeTheme.secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Text(
                        text = DaisyParserEngine.formatTimeMs(daisyState.totalBookDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = activeTheme.secondaryTextColor
                    )
                }
            }

            // Primary Transport Controls: Prev Phrase | -15s | Play/Pause | +15s | Next Phrase
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousPhrase,
                    modifier = Modifier.testTag("daisy_prev_phrase_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous SMIL Phrase",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { onSeekRelativeMs(-15_000L) },
                    modifier = Modifier.testTag("daisy_rewind_15s_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 15 seconds",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                        .clickable { onTogglePlayPause() }
                        .testTag("daisy_play_pause_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (daisyState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (daisyState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = { onSeekRelativeMs(15_000L) },
                    modifier = Modifier.testTag("daisy_forward_15s_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 15 seconds",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = onNextPhrase,
                    modifier = Modifier.testTag("daisy_next_phrase_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next SMIL Phrase",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Variable Playback Speed Selector & Auto-Turn Page Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Speed:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = activeTheme.secondaryTextColor
                )
                for (preset in DaisyAudioPlayerManager.SPEED_PRESETS) {
                    val isSelected = kotlin.math.abs(daisyState.playbackSpeed - preset) < 0.04f
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) accentColor else activeTheme.backgroundColor,
                        border = BorderStroke(1.dp, if (isSelected) accentColor else activeTheme.dividerColor),
                        modifier = Modifier
                            .clickable { onChangePlaybackSpeed(preset) }
                            .testTag("daisy_speed_chip_${preset}")
                    ) {
                        Text(
                            text = "${preset}x",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else activeTheme.textColor,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Auto-Page",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeTheme.secondaryTextColor
                    )
                    Switch(
                        checked = daisyState.autoTurnPages,
                        onCheckedChange = onToggleAutoTurnPages,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("daisy_auto_turn_pages_switch")
                    )
                }
            }
        }
    }
}
