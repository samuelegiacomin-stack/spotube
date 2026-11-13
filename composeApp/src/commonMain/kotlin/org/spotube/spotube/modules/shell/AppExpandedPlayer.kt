/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.spotube.spotube.modules.shell


import org.spotube.millennium.composables.MillenniumIcon
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import org.spotube.spotube.core.ui.theming.AppearanceIllustration
import org.spotube.spotube.core.ui.theming.appearanceBackground
import org.spotube.spotube.core.ui.theming.appearanceTransportSurface
import org.spotube.spotube.core.ui.theming.isIllustrated
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.millennium.composables.MillenniumSnackbarHost
import org.spotube.millennium.composables.MillenniumSnackbarHostState
import org.spotube.millennium.composables.MillenniumIconButton
import org.spotube.millennium.composables.MillenniumIconButtonStyle
import org.spotube.millennium.composables.MillenniumCard
import org.spotube.millennium.composables.MillenniumSlider
import org.spotube.millennium.theme.MillenniumTheme

import com.composeunstyled.Text
import com.composeunstyled.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.LoopState
import org.spotube.spotube.core.jam.JamRole
import org.spotube.spotube.core.jam.JamRoomService
import org.spotube.spotube.core.navigation.NavigationCommands
import org.spotube.spotube.core.navigation.Routes
import org.spotube.spotube.modules.lyrics.LyricsViewModel
import org.spotube.spotube.modules.lyrics.SyncedLyricsList
import org.spotube.spotube.modules.saved_tracks.SAVED_TRACKS_COLLECTION_ID
import org.spotube.spotube.modules.saved_tracks.SavedTracksViewModel
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxArrowDown4
import org.spotube.spotube.resources.iconsax.IconsaxArrowSquareUp
import org.spotube.spotube.resources.iconsax.IconsaxMusicFilter
import org.spotube.spotube.resources.iconsax.IconsaxNext
import org.spotube.spotube.resources.iconsax.IconsaxPause
import org.spotube.spotube.resources.iconsax.IconsaxPlay
import org.spotube.spotube.resources.iconsax.IconsaxPrevious
import org.spotube.spotube.resources.iconsax.IconsaxRepeatMusic
import org.spotube.spotube.resources.iconsax.IconsaxShuffle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.spotube.spotube.core.ui.component.playerPressFeedback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.spotube.spotube.core.remote.RemotePlaybackController
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.stringResource
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_collapse_content_description
import spotube.composeapp.generated.resources.player_disable_shuffle_content_description
import spotube.composeapp.generated.resources.player_enable_shuffle_content_description
import spotube.composeapp.generated.resources.player_expand_lyrics_content_description
import spotube.composeapp.generated.resources.player_loop_mode_all
import spotube.composeapp.generated.resources.player_loop_mode_none
import spotube.composeapp.generated.resources.player_loop_mode_one
import spotube.composeapp.generated.resources.player_lyrics
import spotube.composeapp.generated.resources.player_next_content_description
import spotube.composeapp.generated.resources.player_now_playing
import spotube.composeapp.generated.resources.player_pause_content_description
import spotube.composeapp.generated.resources.player_play_content_description
import spotube.composeapp.generated.resources.player_previous_content_description
import spotube.composeapp.generated.resources.player_seek_label
import spotube.composeapp.generated.resources.queue_title

// The expanded player on small screens
// on the top left it has a angle/chevron down that can be used to collapse the player back to the
// floating player
// on the top right it has a three dots for options
// The top bar can be used to swipe down and close the player back to the floating player. It should
// follow the user's finger while swiping down and should have a nice animation when collapsing back
// to the floating player
// On the middle the album art stays rounded, centered and takes up most of the space
// Below the album art, on the left the song title and artists are shown and on the right the like
// button is shown
// below that we have the playback controls and the progress bar, which should be similar to the
// large player but with a different layout to fit the smaller screen
// And at the very end we will have the volume slider
@Composable
fun AppExpandedPlayer(
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onCollapse: () -> Unit = {},
    onQueue: () -> Unit = {},
    onAlternativeSource: () -> Unit = {},
    onExpandLyrics: () -> Unit = {},
    onDownloadTrack: () -> Unit = {},
    onGoToAlbum: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    audioPlayer: AudioPlayerInterface = koinInject(),
    audioPlayerQueue: AudioPlayerQueue = koinInject(),
    playerOptionsViewModel: PlayerOptionsViewModel = koinViewModel(),
    savedTracksViewModel: SavedTracksViewModel = koinViewModel<SavedTracksViewModel>(
        key = SAVED_TRACKS_COLLECTION_ID,
        parameters = { parametersOf() }
    )
) {
    val playerUiState = rememberPlayerUiState(audioPlayer, audioPlayerQueue)
    val jamRoomService: JamRoomService = koinInject()
    val isJamGuest by jamRoomService.role
        .map { it == JamRole.Guest }
        .collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val navigationCommands: NavigationCommands = koinInject()
    val playerOptionsUiState by playerOptionsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { MillenniumSnackbarHostState() }
    val playbackController: RemotePlaybackController = koinInject()
    LaunchedEffect(playbackController) {
        merge(playbackController.events, playerOptionsViewModel.events)
            .collect { snackbarHostState.showSnackbar(it) }
    }
    val albumArtModifier =
        rememberSharedAlbumArtModifier(sharedTransitionScope, animatedVisibilityScope)
    val coverModel = playerUiState.coverModel
    var isSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(playerUiState.progress) }
    PlayerOptionDialogs(viewModel = playerOptionsViewModel)

    LaunchedEffect(playerUiState.progress, isSeeking) {
        if (!isSeeking) {
            seekProgress = playerUiState.progress
        }
    }

    fun onPlayPause() {
        scope.launch {
            if (playerUiState.isPlaying) {
                audioPlayer.pause()
            } else {
                audioPlayer.play()
            }
        }
    }

    fun onSkipPrevious() {
        if (isJamGuest) return
        scope.launch { audioPlayer.skipToPrevious() }
    }

    fun onSkipNext() {
        if (isJamGuest) return
        scope.launch { audioPlayer.skipToNext() }
    }

    fun onShuffleToggle() {
        if (isJamGuest) return
        scope.launch { audioPlayer.shuffle(!audioPlayer.shuffleModeFlow.value) }
    }

    fun onLoopToggle() {
        if (isJamGuest) return
        scope.launch { audioPlayer.loop(playerUiState.loopState.next()) }
    }

    fun onSeekFinished() {
        val durationMillis = playerUiState.seekDuration.inWholeMilliseconds
        if (durationMillis <= 0L) return
        scope.launch {
            audioPlayer.seekTo(
                (durationMillis * seekProgress.coerceIn(
                    0f,
                    1f
                )).toLong().milliseconds
            )
        }
    }

    val appearance = AppTheme.appearance
    val illustrated = appearance.isIllustrated
    val skipButtonStyle = if (illustrated) {
        MillenniumIconButtonStyle.Secondary
    } else MillenniumIconButtonStyle.Quiet
    Box(modifier = modifier.fillMaxSize().appearanceBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MillenniumIconButton(
                    modifier = Modifier.playerPressFeedback(),
                    onClick = onCollapse,
                    style = MillenniumIconButtonStyle.Quiet
                ) {
                    MillenniumIcon(
                        Iconsax.IconsaxArrowDown4,
                        contentDescription = stringResource(Res.string.player_collapse_content_description)
                    )
                }
                Text(
                    text = stringResource(Res.string.player_now_playing),
                    style = AppTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                AppPlayerOptions(
                    state = playerOptionsUiState,
                    viewModel = playerOptionsViewModel,
                    onAlternativeSource = onAlternativeSource,
                    onGoToAlbum = { onCollapse(); onGoToAlbum() },
                    onSleepTimer = onSleepTimer)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(1f)
                        .then(albumArtModifier)
                        .clip(if (illustrated) AppTheme.shapes.large else RoundedCornerShape(22.dp))
                        .background(AppTheme.colors.surfaceContainerHighest)
                ) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = playerUiState.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerHeartButton(
                    audioPlayerQueue = audioPlayerQueue,
                    savedTracksViewModel = savedTracksViewModel
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = playerUiState.title,
                        style = AppTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    PlayerArtistsText(
                        artists = playerUiState.artistList,
                        onArtistClick = { artistId ->
                            navigationCommands.navigateTo(Routes.Artist(artistId))
                            // The page opens beneath the player, so get out of the way
                            onCollapse()
                        },
                        style = AppTheme.typography.bodyLarge,
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                MillenniumIconButton(
                    modifier = Modifier.playerPressFeedback(),
                    onClick = onQueue,
                    style = MillenniumIconButtonStyle.Secondary
                ) {
                    MillenniumIcon(Iconsax.IconsaxMusicFilter, contentDescription = stringResource(Res.string.queue_title))
                }
            }

            Column(modifier = Modifier.padding(top = 20.dp)) {
                MillenniumSlider(
                    value = seekProgress,
                    onValueChange = {
                        seekProgress = it
                        isSeeking = true
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        onSeekFinished()
                    },
                    enabled = playerUiState.seekDuration.inWholeMilliseconds > 0L,
                    label = stringResource(Res.string.player_seek_label),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatPlayerTime(playerUiState.position),
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                    Text(
                        text = formatPlayerTime(playerUiState.displayDuration),
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp)
                        .appearanceTransportSurface(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MillenniumIconButton(
                        modifier = Modifier.playerModeSelection(playerUiState.isShuffling).playerPressFeedback(!isJamGuest),
                        onClick = ::onShuffleToggle,
                        enabled = !isJamGuest,
                        style = MillenniumIconButtonStyle.Quiet
                    ) {
                        PlayerModeIcon(
                            icon = Iconsax.IconsaxShuffle,
                            contentDescription = stringResource(if (playerUiState.isShuffling) Res.string.player_disable_shuffle_content_description else Res.string.player_enable_shuffle_content_description),
                            active = playerUiState.isShuffling)
                    }
                    MillenniumIconButton(
                        modifier = Modifier.playerPressFeedback(!isJamGuest),
                        onClick = ::onSkipPrevious,
                        enabled = !isJamGuest,
                        style = skipButtonStyle
                    ) {
                        MillenniumIcon(Iconsax.IconsaxPrevious, contentDescription = stringResource(Res.string.player_previous_content_description))
                    }
                    MillenniumIconButton(
                        modifier = Modifier.playerPressFeedback(),
                        onClick = ::onPlayPause,
                        style = MillenniumIconButtonStyle.Primary
                    ) {
                        MillenniumIcon(
                            if (playerUiState.isPlaying) Iconsax.IconsaxPause else Iconsax.IconsaxPlay,
                            contentDescription = stringResource(if (playerUiState.isPlaying) Res.string.player_pause_content_description else Res.string.player_play_content_description),
                            modifier = Modifier.size(30.dp),
                            tint = LocalTextStyle.current.color
                        )
                    }
                    MillenniumIconButton(
                        modifier = Modifier.playerPressFeedback(!isJamGuest),
                        onClick = ::onSkipNext,
                        enabled = !isJamGuest,
                        style = skipButtonStyle
                    ) {
                        MillenniumIcon(Iconsax.IconsaxNext, contentDescription = stringResource(Res.string.player_next_content_description))
                    }
                    MillenniumIconButton(
                        modifier = Modifier.playerModeSelection(playerUiState.loopState != LoopState.NONE).playerPressFeedback(!isJamGuest),
                        onClick = ::onLoopToggle,
                        enabled = !isJamGuest,
                        style = MillenniumIconButtonStyle.Quiet
                    ) {
                        PlayerModeIcon(
                            icon = Iconsax.IconsaxRepeatMusic,
                            contentDescription = stringResource(when (playerUiState.loopState) { LoopState.NONE -> Res.string.player_loop_mode_none; LoopState.ONE -> Res.string.player_loop_mode_one; LoopState.ALL -> Res.string.player_loop_mode_all }),
                            active = playerUiState.loopState != LoopState.NONE,
                            repeatOne = playerUiState.loopState == LoopState.ONE)
                    }
                }
            }

            if (illustrated) {
                AppearanceIllustration(
                    appearance,
                    modifier = Modifier.widthIn(max = 460.dp).clip(AppTheme.shapes.large),
                )
            }

            LyricsPreviewCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                onExpand = onExpandLyrics
            )
        }

        MillenniumSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberSharedAlbumArtModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return Modifier

    with(sharedTransitionScope) {
        return Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = "player_album_art"),
            animatedVisibilityScope = animatedVisibilityScope
        )
    }
}

@Composable
private fun LyricsPreviewCard(
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    viewModel: LyricsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncedLyrics = uiState.syncedLyrics ?: emptyList()
    val isLoading = uiState.isLoading
    if (syncedLyrics.isEmpty() || isLoading) return

    MillenniumCard(
        modifier = modifier.clickable(onClick = onExpand),
        contentPadding = PaddingValues(12.dp),
        contentSpacing = 8.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.player_lyrics),
                style = AppTheme.typography.labelMedium,
                color = MillenniumTheme.colors.muted
            )
            MillenniumIconButton(
                modifier = Modifier.playerPressFeedback(),
                onClick = onExpand,
                style = MillenniumIconButtonStyle.Quiet
            ) {
                MillenniumIcon(
                    Iconsax.IconsaxArrowSquareUp,
                    contentDescription = stringResource(Res.string.player_expand_lyrics_content_description),
                    tint = MillenniumTheme.colors.muted
                )
            }
        }

        // Passive preview: no onLineClick, so lines never seek playback.
        SyncedLyricsList(
            lines = syncedLyrics,
            currentIndex = uiState.currentIndex,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
    }
}
