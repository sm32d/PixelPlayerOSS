package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.Canvas
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import com.lostf1sh.pixelplayeross.data.model.VolumePreset
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.BeatGrid
import com.lostf1sh.pixelplayeross.data.media.WaveformData
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import com.lostf1sh.pixelplayeross.data.model.EffectPreset
import com.lostf1sh.pixelplayeross.data.model.EqPreset
import com.lostf1sh.pixelplayeross.data.model.FilterPreset
import com.lostf1sh.pixelplayeross.data.model.MixPreset
import com.lostf1sh.pixelplayeross.data.model.MixTransition
import com.lostf1sh.pixelplayeross.data.service.player.Beatmatch
import com.lostf1sh.pixelplayeross.data.service.player.MixStyles
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.VolumeKeyframe
import com.lostf1sh.pixelplayeross.data.service.player.MixEditing
import com.lostf1sh.pixelplayeross.data.service.player.MixLanes
import com.lostf1sh.pixelplayeross.data.service.player.MixPlanner
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MixEditorUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MixEditorViewModel
import com.lostf1sh.pixelplayeross.utils.formatDuration
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MixEditorScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: MixEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The app's floating nav bar and mini player sit over the screen; keep the last
    // controls scrollable above them.
    val playerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val useNavigationRail = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val bottomOverlay = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode, useNavigationRail) +
        (if (playerState.currentSong != null) MiniPlayerHeight else 0.dp) + 24.dp
    var showMenu by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            viewModel.consumeSaved()
            navController.navigateUp()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.dj_mix_editor_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = stringResource(R.string.dj_mix_beta),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    FilledIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        onClick = { navController.navigateUp() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dj_mix_close_cd))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dj_mix_more_cd))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dj_mix_reset_auto)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.resetToAuto()
                                },
                            )
                        }
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = state.isDirty,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(stringResource(R.string.dj_mix_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                ContainedLoadingIndicator()
            }

            state.loadFailed || state.mix == null || state.from == null || state.to == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.dj_mix_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            else -> MixEditorContent(state, viewModel, bottomOverlay, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixEditorContent(
    state: MixEditorUiState,
    viewModel: MixEditorViewModel,
    bottomOverlay: Dp,
    modifier: Modifier = Modifier,
) {
    val mix = checkNotNull(state.mix)
    val from = checkNotNull(state.from)
    val to = checkNotNull(state.to)
    val outgoingColor = MaterialTheme.colorScheme.primary
    val incomingColor = MaterialTheme.colorScheme.tertiary
    // Handed down as State (not read here): the playhead moves every frame during a preview and
    // only the timeline's draw step should react to it.
    val previewPosition = viewModel.previewPositionMs.collectAsStateWithLifecycle()
    val windowOptions = remember(state.from, state.fromBpm) { state.windowOptions.toImmutableList() }
    // The lane the timeline shows; its lines are drawn over the waveforms (only Volume's can be dragged).
    var focusedLane by rememberSaveable { mutableStateOf(MixLane.VOLUME) }
    // Fixed hues (Spotify draws EQ yellow, Filter pink and Effects white), nudged towards the theme.
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val laneColors = remember(primary, onSurface) {
        LaneColors(
            eq = androidx.compose.ui.graphics.lerp(Color(0xFFE8B730), primary, 0.12f),
            filter = androidx.compose.ui.graphics.lerp(Color(0xFFE56F9B), primary, 0.12f),
            effects = onSurface,
            loop = androidx.compose.ui.graphics.lerp(Color(0xFF8E7CF0), primary, 0.12f),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SongHeader(song = from, bpm = state.fromBpm, accent = outgoingColor)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (state.isAnalyzing) {
                // Choosing the automatic mix from the songs' audio takes a moment the first time:
                // wait for it rather than show a stand-in graph that then changes by itself.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ContainedLoadingIndicator()
                }
            } else {
                MixTimeline(
                    state = state,
                    mix = mix,
                    previewPositionMs = previewPosition,
                    outgoingColor = outgoingColor,
                    incomingColor = incomingColor,
                    focus = focusedLane,
                    laneColors = laneColors,
                    onShiftOutgoing = viewModel::shiftOutgoing,
                    onShiftIncoming = viewModel::shiftIncoming,
                    onMoveKeyframe = viewModel::moveKeyframe,
                    onAddKeyframe = viewModel::addKeyframe,
                    onRemoveKeyframe = viewModel::removeKeyframe,
                    modifier = Modifier.fillMaxSize(),
                )

                // Where a lane has no waveform, say so (cloud songs, unsupported files).
                Column(Modifier.fillMaxSize()) {
                    WaveformPlaceholder(
                        loading = state.fromWaveformLoading,
                        hasWaveform = state.fromWaveform != null,
                        unavailable = !state.fromWaveformLoading && state.fromWaveform == null,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    WaveformPlaceholder(
                        loading = state.toWaveformLoading,
                        hasWaveform = state.toWaveform != null,
                        unavailable = !state.toWaveformLoading && state.toWaveform == null,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                FilledIconButton(
                    onClick = viewModel::togglePreview,
                    enabled = state.canPreview,
                    modifier = Modifier.align(Alignment.Center).size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = if (state.isPreviewPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPreviewPlaying) R.string.dj_mix_stop_preview_cd else R.string.dj_mix_play_preview_cd
                        ),
                    )
                }

                WindowChip(
                    mix = mix,
                    options = windowOptions,
                    onSelect = viewModel::selectWindow,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }

        SongHeader(song = to, bpm = state.toBpm, accent = incomingColor)

        if (!state.canPreview) {
            Text(
                text = stringResource(R.string.dj_mix_preview_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Text(
            text = stringResource(R.string.dj_mix_presets),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        PresetStrip(selected = mix.preset, onSelect = viewModel::applyPreset)

        // One row per lane, as in Spotify: the lane's current preset, tap for the list. The lane
        // last tapped is the one the timeline draws.
        var openSheet by rememberSaveable { mutableStateOf<MixLane?>(null) }
        Column(Modifier.padding(top = 8.dp)) {
            MixLaneRow(
                label = stringResource(R.string.dj_mix_volume),
                accent = outgoingColor,
                // The lane's own preset; otherwise the style that drew the lines (e.g. "Auto");
                // "Custom" only once the lines were edited by hand.
                value = mix.volumePreset?.let { volumePresetLabel(it) }
                    ?: if (mix.preset != MixPreset.CUSTOM) presetLabel(mix.preset)
                    else stringResource(R.string.dj_mix_lane_custom),
                selected = focusedLane == MixLane.VOLUME,
                onClick = { focusedLane = MixLane.VOLUME; openSheet = MixLane.VOLUME },
            )
            MixLaneRow(
                label = stringResource(R.string.dj_mix_lane_eq),
                accent = laneColors.eq,
                value = eqPresetLabel(MixLanes.eqOf(mix)),
                selected = focusedLane == MixLane.EQ,
                onClick = { focusedLane = MixLane.EQ; openSheet = MixLane.EQ },
            )
            MixLaneRow(
                label = stringResource(R.string.dj_mix_lane_filter),
                accent = laneColors.filter,
                value = filterPresetLabel(MixLanes.filterOf(mix)),
                selected = focusedLane == MixLane.FILTER,
                onClick = { focusedLane = MixLane.FILTER; openSheet = MixLane.FILTER },
            )
            MixLaneRow(
                label = stringResource(R.string.dj_mix_lane_effects),
                accent = laneColors.effects,
                value = effectPresetLabel(MixLanes.effectsOf(mix)),
                selected = focusedLane == MixLane.EFFECTS,
                onClick = { focusedLane = MixLane.EFFECTS; openSheet = MixLane.EFFECTS },
            )
            MixLaneRow(
                label = stringResource(R.string.dj_mix_lane_looping),
                accent = laneColors.loop,
                value = loopLabel(MixLanes.loopOf(mix)),
                selected = focusedLane == MixLane.LOOPING,
                onClick = { focusedLane = MixLane.LOOPING; openSheet = MixLane.LOOPING },
            )
            BeatmatchRow(state = state, mix = mix, onCheckedChange = viewModel::setBeatmatch)
        }
        Text(
            stringResource(
                when (focusedLane) {
                    MixLane.VOLUME -> R.string.dj_mix_volume_hint
                    MixLane.EQ -> R.string.dj_mix_eq_hint
                    MixLane.FILTER -> R.string.dj_mix_filter_hint
                    MixLane.EFFECTS -> R.string.dj_mix_effects_hint
                    MixLane.LOOPING -> R.string.dj_mix_loop_hint
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        when (openSheet) {
            MixLane.VOLUME -> LanePresetSheet(
                title = stringResource(R.string.dj_mix_volume),
                accent = outgoingColor,
                options = VolumePreset.entries,
                selected = mix.volumePreset,
                label = { volumePresetLabel(it) },
                onSelect = {
                    viewModel.applyVolumePreset(it)
                    openSheet = null
                },
                onDismiss = { openSheet = null },
            )
            MixLane.EQ -> LanePresetSheet(
                title = stringResource(R.string.dj_mix_lane_eq),
                accent = laneColors.eq,
                options = EqPreset.entries,
                selected = MixLanes.eqOf(mix),
                label = { eqPresetLabel(it) },
                onSelect = {
                    viewModel.applyEqPreset(it)
                    openSheet = null
                },
                onDismiss = { openSheet = null },
            )
            MixLane.FILTER -> LanePresetSheet(
                title = stringResource(R.string.dj_mix_lane_filter),
                accent = laneColors.filter,
                options = FilterPreset.entries,
                selected = MixLanes.filterOf(mix),
                label = { filterPresetLabel(it) },
                onSelect = {
                    viewModel.applyFilterPreset(it)
                    openSheet = null
                },
                onDismiss = { openSheet = null },
            )
            MixLane.EFFECTS -> LanePresetSheet(
                title = stringResource(R.string.dj_mix_lane_effects),
                accent = laneColors.effects,
                options = EffectPreset.entries,
                selected = MixLanes.effectsOf(mix),
                label = { effectPresetLabel(it) },
                onSelect = {
                    viewModel.applyEffectPreset(it)
                    openSheet = null
                },
                onDismiss = { openSheet = null },
            )
            MixLane.LOOPING -> LanePresetSheet(
                title = stringResource(R.string.dj_mix_lane_looping),
                accent = laneColors.loop,
                options = MixLanes.LOOP_OPTIONS,
                selected = MixLanes.loopOf(mix),
                label = { loopLabel(it) },
                onSelect = {
                    viewModel.applyLoop(it)
                    openSheet = null
                },
                onDismiss = { openSheet = null },
            )
            null -> Unit
        }
        Spacer(Modifier.height(bottomOverlay))
    }
}

@Composable
private fun presetLabel(preset: MixPreset): String = stringResource(
    when (preset) {
        MixPreset.AUTO -> R.string.dj_mix_preset_auto
        MixPreset.CUSTOM -> R.string.dj_mix_preset_custom
        MixPreset.FADE -> R.string.dj_mix_preset_fade
        MixPreset.RISE -> R.string.dj_mix_preset_rise
        MixPreset.CUT -> R.string.dj_mix_preset_cut
        MixPreset.BLEND -> R.string.dj_mix_preset_blend
        MixPreset.WAVE -> R.string.dj_mix_preset_wave
        MixPreset.MELT -> R.string.dj_mix_preset_melt
        MixPreset.SLAM -> R.string.dj_mix_preset_slam
        MixPreset.DISSOLVE -> R.string.dj_mix_preset_dissolve
        MixPreset.SURGE -> R.string.dj_mix_preset_surge
        MixPreset.AFTERGLOW -> R.string.dj_mix_preset_afterglow
        MixPreset.NO_TRANSITION -> R.string.dj_mix_preset_no_transition
    }
)

@Composable
private fun volumePresetLabel(preset: VolumePreset): String = stringResource(
    when (preset) {
        VolumePreset.SMOOTH_CROSSFADE -> R.string.dj_mix_volume_smooth_crossfade
        VolumePreset.OVERLAP -> R.string.dj_mix_volume_overlap
        VolumePreset.FADE_IN_FADE_OUT -> R.string.dj_mix_volume_fade_in_fade_out
        VolumePreset.CUT_IN_FADE_OUT -> R.string.dj_mix_volume_cut_in_fade_out
        VolumePreset.FADE_IN_CUT_OUT -> R.string.dj_mix_volume_fade_in_cut_out
        VolumePreset.CENTRE_CUT -> R.string.dj_mix_volume_centre_cut
    }
)

/** The styles offered in the strip, in Spotify's order. [MixPreset.CUT] is kept only for old data. */
private val STYLE_PRESETS = listOf(
    MixPreset.AUTO, MixPreset.CUSTOM, MixPreset.FADE, MixPreset.RISE, MixPreset.BLEND, MixPreset.WAVE,
    MixPreset.MELT, MixPreset.SLAM, MixPreset.DISSOLVE, MixPreset.SURGE, MixPreset.AFTERGLOW,
    MixPreset.NO_TRANSITION,
)

/** Horizontally scrolling row of style presets (too many to fit a connected button group). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresetStrip(selected: MixPreset, onSelect: (MixPreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        STYLE_PRESETS.forEach { preset ->
            ToggleButton(
                checked = preset == selected,
                onCheckedChange = { onSelect(preset) },
                modifier = Modifier.height(56.dp).widthIn(min = 104.dp),
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (preset == MixPreset.AUTO) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(presetLabel(preset), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** The lanes that can be shown on the timeline and set from a sheet. */
private enum class MixLane { VOLUME, EQ, FILTER, EFFECTS, LOOPING }

private data class LaneColors(val eq: Color, val filter: Color, val effects: Color, val loop: Color)

/** A lane (Volume, EQ, Filter): its colour, name and current preset; [selected] is the one on the timeline. */
@Composable
private fun MixLaneRow(label: String, accent: Color, value: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(24.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Beatmatching on/off. Says what it does for this pair (the tempo it plays the incoming song at)
 * or why it can't: no steady beat in a song, or tempos too far apart.
 */
@Composable
private fun BeatmatchRow(state: MixEditorUiState, mix: MixTransition, onCheckedChange: (Boolean) -> Unit) {
    val unavailable = state.beatmatchUnavailable
    val loading = state.gridsLoading
    val enabled = !loading && unavailable == null
    val detail = when {
        loading -> stringResource(R.string.dj_mix_beatmatch_listening)
        unavailable == Beatmatch.Unavailable.NO_BEAT -> stringResource(R.string.dj_mix_beatmatch_no_beat)
        unavailable == Beatmatch.Unavailable.TEMPO_TOO_FAR -> stringResource(R.string.dj_mix_beatmatch_too_far)
        !mix.beatmatchOn -> stringResource(R.string.dj_mix_beatmatch_off)
        else -> {
            val inBpm = state.toGrid?.bpm ?: 0f
            val playedAt = inBpm * (state.beatmatchSpeed ?: 1.0)
            stringResource(R.string.dj_mix_beatmatch_detail, inBpm.roundToInt(), playedAt.roundToInt())
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onCheckedChange(!mix.beatmatchOn) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dj_mix_lane_beatmatching), style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = enabled && mix.beatmatchOn,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** "None" or "4-beat loop". */
@Composable
private fun loopLabel(beats: Int): String =
    if (beats == 0) stringResource(R.string.dj_mix_lane_none)
    else pluralStringResource(R.plurals.dj_mix_loop_beats, beats, beats)

@Composable
private fun eqPresetLabel(preset: EqPreset): String = stringResource(
    when (preset) {
        EqPreset.NONE -> R.string.dj_mix_lane_none
        EqPreset.CENTRE_BASS_SWAP -> R.string.dj_mix_eq_centre_bass_swap
        EqPreset.END_BASS_SWAP -> R.string.dj_mix_eq_end_bass_swap
        EqPreset.START_BASS_SWAP -> R.string.dj_mix_eq_start_bass_swap
        EqPreset.THREE_BAND_FADE -> R.string.dj_mix_eq_three_band_fade
        EqPreset.QUICK_BASS_CUT -> R.string.dj_mix_eq_quick_bass_cut
    }
)

@Composable
private fun filterPresetLabel(preset: FilterPreset): String = stringResource(
    when (preset) {
        FilterPreset.NONE -> R.string.dj_mix_lane_none
        FilterPreset.LOW_PASS_OUT -> R.string.dj_mix_filter_low_pass_out
        FilterPreset.LOW_PASS_IN -> R.string.dj_mix_filter_low_pass_in
        FilterPreset.LOW_PASS_IN_LOW_PASS_OUT -> R.string.dj_mix_filter_lp_in_lp_out
        FilterPreset.LOW_PASS_IN_HIGH_PASS_OUT -> R.string.dj_mix_filter_lp_in_hp_out
        FilterPreset.HIGH_PASS_IN_HIGH_PASS_OUT -> R.string.dj_mix_filter_hp_in_hp_out
        FilterPreset.HIGH_PASS_HALF_OUT -> R.string.dj_mix_filter_hp_half_out
    }
)

@Composable
private fun effectPresetLabel(preset: EffectPreset): String = stringResource(
    when (preset) {
        EffectPreset.NONE -> R.string.dj_mix_lane_none
        EffectPreset.REVERB_OUT_CENTRE -> R.string.dj_mix_fx_reverb_out_centre
        EffectPreset.REVERB_CUT_END -> R.string.dj_mix_fx_reverb_cut_end
        EffectPreset.REVERB_OUT_END -> R.string.dj_mix_fx_reverb_out_end
        EffectPreset.ECHO_HALF_CUT_END -> R.string.dj_mix_fx_echo_half_cut_end
        EffectPreset.ECHO_HALF_OUT_END -> R.string.dj_mix_fx_echo_half_out_end
    }
)

/** A lane's presets; the current one is ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> LanePresetSheet(
    title: String,
    accent: Color,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(24.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            options.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(preset) }
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label(preset), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (preset == selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SongHeader(song: Song, bpm: Float?, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(4.dp).height(44.dp).clip(CircleShape).background(accent))
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (bpm != null) {
                Text(stringResource(R.string.dj_mix_bpm, bpm.roundToInt()), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                formatDuration(song.duration),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaveformPlaceholder(
    loading: Boolean,
    hasWaveform: Boolean,
    unavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loading && !hasWaveform) {
            // The waveform is being decoded; say so instead of leaving the lane blank.
            LoadingIndicator(modifier = Modifier.size(32.dp))
        } else if (loading) {
            // A first look is drawn but the whole song is still decoding: parts of the lane may
            // not be known yet, so keep a small indicator in the corner.
            LoadingIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp))
        } else if (unavailable) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            ) {
                Text(
                    text = stringResource(R.string.dj_mix_waveform_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WindowChip(
    mix: MixTransition,
    options: ImmutableList<MixPlanner.WindowOption>,
    onSelect: (MixPlanner.WindowOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(windowLabel(mix.bars, mix.windowMs))
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(windowLabel(option.bars, option.windowMs)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun windowLabel(bars: Int?, windowMs: Long): String =
    if (bars != null) stringResource(R.string.dj_mix_bars_option, bars)
    else stringResource(R.string.dj_mix_seconds_option, (windowMs / 1000f).roundToInt())

// --- Timeline -------------------------------------------------------------------------------

/**
 * Maps the mix window (plus some context either side) onto the canvas. Time is milliseconds
 * relative to the window start; the top lane is the outgoing song, the bottom lane the incoming.
 */
private class TimelineGeometry(val width: Float, val height: Float, val windowMs: Long, val lanePad: Float) {
    val padMs = windowMs * CONTEXT_FRACTION
    val totalMs = windowMs + 2 * padMs
    val laneHeight = height / 2f

    /** Height the volume line really spans: the lane minus a margin so edge points stay whole. */
    val lineSpan = laneHeight - 2 * lanePad

    fun xOf(relMs: Float) = (relMs + padMs) / totalMs * width
    fun relOf(x: Float) = x / width * totalMs - padMs
    fun laneTop(outgoing: Boolean) = if (outgoing) 0f else laneHeight
    fun yOf(outgoing: Boolean, v: Float) = laneTop(outgoing) + lanePad + (1f - v) * lineSpan
    fun vOf(outgoing: Boolean, y: Float) = 1f - (y - laneTop(outgoing) - lanePad) / lineSpan
    fun isOutgoingLane(y: Float) = y < laneHeight

    /** Pixels per unit of keyframe time (0..1 across the window). */
    val pxPerT get() = width * windowMs / totalMs

    companion object {
        const val CONTEXT_FRACTION = 0.3f
    }
}

private sealed interface TimelineDrag {
    data class Keyframe(val outgoing: Boolean, val index: Int) : TimelineDrag
    data class Lane(val outgoing: Boolean) : TimelineDrag
}

@Composable
private fun MixTimeline(
    state: MixEditorUiState,
    mix: MixTransition,
    previewPositionMs: State<Long?>,
    outgoingColor: Color,
    incomingColor: Color,
    focus: MixLane,
    laneColors: LaneColors,
    onShiftOutgoing: (Long) -> Unit,
    onShiftIncoming: (Long) -> Unit,
    onMoveKeyframe: (outgoing: Boolean, index: Int, t: Float, v: Float) -> Unit,
    onAddKeyframe: (outgoing: Boolean, t: Float, v: Float) -> Unit,
    onRemoveKeyframe: (outgoing: Boolean, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val currentMix by rememberUpdatedState(mix)
    val currentFocus by rememberUpdatedState(focus)
    val eqLines = remember(MixLanes.eqOf(mix)) { MixLanes.eqLines(MixLanes.eqOf(mix)) }
    val filterLines = remember(MixLanes.filterOf(mix)) { MixLanes.filterLines(MixLanes.filterOf(mix)) }
    val effectLines = remember(MixLanes.effectsOf(mix)) { MixLanes.effectLines(MixLanes.effectsOf(mix)) }
    val onShiftOut by rememberUpdatedState(onShiftOutgoing)
    val onShiftIn by rememberUpdatedState(onShiftIncoming)
    val onMove by rememberUpdatedState(onMoveKeyframe)
    val onAdd by rememberUpdatedState(onAddKeyframe)
    val onRemove by rememberUpdatedState(onRemoveKeyframe)
    val hitRadiusPx = with(density) { 28.dp.toPx() }
    val lanePadPx = with(density) { 16.dp.toPx() }

    // Fixed hues (bass blue, mids teal, highs pale) so the bands read the same on any theme and
    // never match the songs' line colours; nudged towards the theme so they sit with it.
    val primary = MaterialTheme.colorScheme.primary
    val bandColors = remember(primary) {
        WaveColors(
            plain = Color.Unspecified,
            lows = androidx.compose.ui.graphics.lerp(Color(0xFF3D6FD6), primary, 0.12f),
            mids = androidx.compose.ui.graphics.lerp(Color(0xFF3FAE9E), primary, 0.12f),
            highs = androidx.compose.ui.graphics.lerp(Color(0xFFDDE3F0), primary, 0.08f),
        )
    }.copy(plain = MaterialTheme.colorScheme.onSurfaceVariant)
    val barLineColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val windowTint = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val handleRing = MaterialTheme.colorScheme.surfaceContainerHigh

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                var drag: TimelineDrag? = null
                detectDragGestures(
                    onDragStart = { pos ->
                        val m = currentMix
                        val g = TimelineGeometry(size.width.toFloat(), size.height.toFloat(), m.windowMs, lanePadPx)
                        val outgoing = g.isOutgoingLane(pos.y)
                        val line = if (outgoing) m.outVolume else m.inVolume
                        // Volume points are only grabbed while the Volume lane is shown.
                        val hit = if (currentFocus != MixLane.VOLUME) -1 else MixEditing.hitKeyframe(
                            line,
                            t = g.relOf(pos.x) / m.windowMs,
                            v = g.vOf(outgoing, pos.y),
                            maxDt = hitRadiusPx / g.pxPerT,
                            maxDv = hitRadiusPx / g.lineSpan,
                        )
                        drag = if (hit >= 0) TimelineDrag.Keyframe(outgoing, hit) else TimelineDrag.Lane(outgoing)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val m = currentMix
                        val g = TimelineGeometry(size.width.toFloat(), size.height.toFloat(), m.windowMs, lanePadPx)
                        when (val d = drag) {
                            is TimelineDrag.Keyframe -> onMove(
                                d.outgoing,
                                d.index,
                                g.relOf(change.position.x) / m.windowMs,
                                g.vOf(d.outgoing, change.position.y),
                            )
                            // Dragging pulls the song's waveform with the finger, so the point
                            // under the (fixed) window moves the opposite way in song time.
                            is TimelineDrag.Lane -> {
                                val deltaMs = (-amount.x / g.width * g.totalMs).toLong()
                                if (deltaMs != 0L) {
                                    if (d.outgoing) onShiftOut(deltaMs) else onShiftIn(deltaMs)
                                }
                            }
                            null -> Unit
                        }
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { pos ->
                        if (currentFocus != MixLane.VOLUME) return@detectTapGestures
                        val m = currentMix
                        val g = TimelineGeometry(size.width.toFloat(), size.height.toFloat(), m.windowMs, lanePadPx)
                        val outgoing = g.isOutgoingLane(pos.y)
                        val line = if (outgoing) m.outVolume else m.inVolume
                        val t = g.relOf(pos.x) / m.windowMs
                        val v = g.vOf(outgoing, pos.y)
                        val hit = MixEditing.hitKeyframe(
                            line, t, v,
                            maxDt = hitRadiusPx / g.pxPerT,
                            maxDv = hitRadiusPx / g.lineSpan,
                        )
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (hit >= 0) onRemove(outgoing, hit) else if (t in 0f..1f) onAdd(outgoing, t, v)
                    },
                )
            },
    ) {
        val g = TimelineGeometry(size.width, size.height, mix.windowMs, lanePadPx)

        // The mix window.
        drawRect(
            color = windowTint.copy(alpha = 0.07f),
            topLeft = Offset(g.xOf(0f), 0f),
            size = androidx.compose.ui.geometry.Size(g.xOf(mix.windowMs.toFloat()) - g.xOf(0f), size.height),
        )

        // Each song's own beats and bars, where its audio showed them; otherwise bars from the
        // outgoing song's tag tempo, counted from the window start.
        val fromGrid = state.fromGrid
        val toGrid = state.toGrid
        if (fromGrid == null && toGrid == null) {
            state.fromBpm?.let { bpm ->
                val barMs = 4 * 60_000f / bpm
                var rel = -(g.padMs / barMs).toInt() * barMs
                while (rel <= g.windowMs + g.padMs) {
                    val x = g.xOf(rel)
                    drawLine(gridColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    rel += barMs
                }
            }
        }

        drawWaveform(g, state.fromWaveform, mix.outPointMs, outgoing = true, colors = bandColors, windowMs = mix.windowMs)
        // Beatmatched, the incoming song plays faster or slower: drawn stretched the same way, its
        // bars sit under the outgoing song's.
        val inSpeed = state.beatmatchSpeed ?: 1.0
        drawWaveform(g, state.toWaveform, mix.inStartMs, outgoing = false, colors = bandColors, windowMs = mix.windowMs, speed = inSpeed)
        // Over the waveform, so the bars stay visible on loud passages; under the volume lines.
        fromGrid?.let { drawBeatGrid(g, it, mix.outPointMs, outgoing = true, color = barLineColor) }
        toGrid?.let { drawBeatGrid(g, it, mix.inStartMs, outgoing = false, color = barLineColor, speed = inSpeed) }

        // The loop: the beats recorded (filled), then each repeat (outlined) until the window ends.
        MixLanes.loopPlacement(mix, fromGrid, state.fromBpm?.takeIf { it > 0f }?.let { 60_000.0 / it })?.let { loop ->
            val top = g.laneTop(outgoing = true) + 4.dp.toPx()
            val height = g.laneHeight - 8.dp.toPx()
            val corner = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
            fun x(songMs: Double) = g.xOf((songMs - mix.outPointMs).toFloat())
            val focused = focus == MixLane.LOOPING
            drawRoundRect(
                color = laneColors.loop.copy(alpha = if (focused) 0.3f else 0.18f),
                topLeft = Offset(x(loop.startMs), top),
                size = androidx.compose.ui.geometry.Size(x(loop.endMs) - x(loop.startMs), height),
                cornerRadius = corner,
            )
            var repeatStart = loop.endMs
            val windowEnd = (mix.outPointMs + mix.windowMs).toDouble()
            while (repeatStart < windowEnd) {
                val end = minOf(repeatStart + loop.lengthMs, windowEnd)
                drawRoundRect(
                    color = laneColors.loop.copy(alpha = if (focused) 0.8f else 0.45f),
                    topLeft = Offset(x(repeatStart), top),
                    size = androidx.compose.ui.geometry.Size(x(end) - x(repeatStart), height),
                    cornerRadius = corner,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
                repeatStart += loop.lengthMs
            }
        }

        // Lane divider.
        drawLine(gridColor, Offset(0f, g.laneHeight), Offset(size.width, g.laneHeight), strokeWidth = 2f)

        if (focus == MixLane.VOLUME) {
            drawVolumeLine(g, mix.outVolume, outgoing = true, color = outgoingColor, ring = handleRing)
            drawVolumeLine(g, mix.inVolume, outgoing = false, color = incomingColor, ring = handleRing)
        } else {
            // Another lane in front: the volume lines stay as a faint reference.
            drawLaneLine(g, mix.outVolume, outgoing = true, color = outgoingColor.copy(alpha = 0.35f))
            drawLaneLine(g, mix.inVolume, outgoing = false, color = incomingColor.copy(alpha = 0.35f))
        }
        when (focus) {
            MixLane.EQ -> listOf(true to eqLines.outgoing, false to eqLines.incoming).forEach { (outgoing, lines) ->
                // Bass solid; mids and highs (only moved by the 3-band fade) dashed.
                drawLaneLine(g, lines.mid, outgoing, laneColors.eq.copy(alpha = 0.7f), dashed = true, skipFlat = true)
                drawLaneLine(g, lines.high, outgoing, laneColors.eq.copy(alpha = 0.45f), dashed = true, skipFlat = true)
                drawLaneLine(g, lines.low, outgoing, laneColors.eq)
            }
            MixLane.FILTER -> listOf(true to filterLines.outgoing, false to filterLines.incoming).forEach { (outgoing, line) ->
                // Centre = no filter; below it low-pass, above it high-pass.
                val y = g.yOf(outgoing, MixLanes.FILTER_NEUTRAL)
                drawLine(
                    laneColors.filter.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )
                drawLaneLine(g, line, outgoing, laneColors.filter)
            }
            MixLane.EFFECTS -> listOf(true to effectLines.outgoing, false to effectLines.incoming).forEach { (outgoing, lines) ->
                // The send solid (how much goes into the reverb or echo), the dry song dashed.
                drawLaneLine(g, lines.dry, outgoing, laneColors.effects.copy(alpha = 0.5f), dashed = true, skipFlat = true)
                drawLaneLine(g, lines.send, outgoing, laneColors.effects, skipFlat = true)
            }
            // The loop is drawn above, for every lane.
            MixLane.VOLUME, MixLane.LOOPING -> Unit
        }

        // Playhead: the window start when idle, the audible position while previewing.
        val previewMs = previewPositionMs.value
        val playheadX = g.xOf((previewMs ?: 0L).toFloat())
        drawLine(
            color = playheadColor.copy(alpha = if (previewMs != null) 1f else 0.5f),
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Waveform colours: [plain] without band data; otherwise bass, mids and highs. */
private data class WaveColors(val plain: Color, val lows: Color, val mids: Color, val highs: Color)

/**
 * A song's beats in its lane: a faint tick per beat and a line per bar, placed by the song's own
 * beat grid (so they line up with its hits wherever the song is slid to).
 */
private fun DrawScope.drawBeatGrid(
    g: TimelineGeometry,
    grid: BeatGrid,
    songTimeAtWindowStartMs: Long,
    outgoing: Boolean,
    color: Color,
    /** How fast the song plays (beatmatching): song time = window time x speed. */
    speed: Double = 1.0,
) {
    val top = g.laneTop(outgoing)
    val bottom = top + g.laneHeight
    val firstSongMs = songTimeAtWindowStartMs + g.relOf(0f) * speed
    val lastSongMs = songTimeAtWindowStartMs + g.relOf(size.width) * speed
    var index = grid.beatIndexAtOrBefore(firstSongMs)
    // Too dense to read when zoomed far out: then only bars.
    val beatPx = (grid.beatMs / speed * size.width / g.totalMs).toFloat()
    while (true) {
        val songMs = grid.beatTimeMs(index)
        if (songMs > lastSongMs) break
        if (songMs >= 0) {
            val x = g.xOf(((songMs - songTimeAtWindowStartMs) / speed).toFloat())
            if (grid.isDownbeat(index)) {
                drawLine(color.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            } else if (beatPx >= 4.dp.toPx()) {
                val tick = g.laneHeight * 0.08f
                drawLine(color.copy(alpha = 0.35f), Offset(x, top), Offset(x, top + tick), strokeWidth = 1f)
                drawLine(color.copy(alpha = 0.35f), Offset(x, bottom - tick), Offset(x, bottom), strokeWidth = 1f)
            }
        }
        index++
    }
}

private fun DrawScope.drawWaveform(
    g: TimelineGeometry,
    waveform: WaveformData?,
    songTimeAtWindowStartMs: Long,
    outgoing: Boolean,
    colors: WaveColors,
    windowMs: Long,
    speed: Double = 1.0,
) {
    if (waveform == null) return
    val barWidth = 3.dp.toPx()
    val step = 4.dp.toPx()
    val centerY = g.laneTop(outgoing) + g.laneHeight / 2f
    val maxHalf = g.laneHeight * 0.42f
    val minHalf = 1.5.dp.toPx()
    val corner = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
    var x = 0f
    while (x < size.width) {
        val rel = g.relOf(x)
        val songMs = songTimeAtWindowStartMs + (rel * speed).toLong()
        // A partly decoded waveform knows nothing about the rest of the song: draw nothing there
        // rather than bars that read as silence.
        if (waveform.isPartial && !waveform.covers(songMs)) {
            x += step
            continue
        }
        val peak = if (songMs < 0) 0f else waveform.peakAtMs(songMs)
        // The outgoing song stops after the window, the incoming one hasn't started before it.
        val audible = if (outgoing) rel <= windowMs else rel >= 0f
        val alpha = if (audible) 1f else 0.35f
        val half = (sqrt(peak) * maxHalf).coerceAtLeast(minHalf)
        fun bar(h: Float, color: Color, a: Float) = drawRoundRect(
            color = color.copy(alpha = a * alpha),
            topLeft = Offset(x, centerY - h),
            size = androidx.compose.ui.geometry.Size(barWidth, h * 2),
            cornerRadius = corner,
        )
        val bands = if (songMs < 0) null else waveform.bandsAtMs(songMs)
        val total = bands?.let { it.first + it.second + it.third } ?: 0f
        if (bands == null || total <= 0f) {
            bar(half, colors.plain, 0.45f)
        } else {
            // Nested bars: the whole height in the bass colour, the mids' and highs' share of the
            // sound inside it, so a bar's colours show what it is made of.
            bar(half, colors.lows, 0.6f)
            val upper = (bands.second + bands.third) / total
            if (upper > 0.05f) bar((half * upper).coerceAtLeast(minHalf / 2), colors.mids, 0.65f)
            val top = bands.third / total
            if (top > 0.05f) bar((half * top).coerceAtLeast(minHalf / 2), colors.highs, 0.6f)
        }
        x += step
    }
}

/** A lane's line without handles (not draggable); [skipFlat] leaves out a line that never moves. */
private fun DrawScope.drawLaneLine(
    g: TimelineGeometry,
    line: List<VolumeKeyframe>,
    outgoing: Boolean,
    color: Color,
    dashed: Boolean = false,
    skipFlat: Boolean = false,
) {
    if (line.isEmpty()) return
    if (skipFlat && line.all { it.v == line.first().v }) return
    val points = line.map { Offset(g.xOf(it.t * g.windowMs), g.yOf(outgoing, it.v)) }
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, points.first().y)
        points.forEach { lineTo(it.x, it.y) }
        lineTo(size.width, points.last().y)
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = 3.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null,
        ),
    )
}

private fun DrawScope.drawVolumeLine(
    g: TimelineGeometry,
    line: List<VolumeKeyframe>,
    outgoing: Boolean,
    color: Color,
    ring: Color,
) {
    if (line.isEmpty()) return
    val points = line.map { Offset(g.xOf(it.t * g.windowMs), g.yOf(outgoing, it.v)) }
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, points.first().y)
        points.forEach { lineTo(it.x, it.y) }
        lineTo(size.width, points.last().y)
    }
    drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    points.forEach {
        drawCircle(ring, radius = 9.dp.toPx(), center = it)
        drawCircle(color, radius = 6.dp.toPx(), center = it)
    }
}
