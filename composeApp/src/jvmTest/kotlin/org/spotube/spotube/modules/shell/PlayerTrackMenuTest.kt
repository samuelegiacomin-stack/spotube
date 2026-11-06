/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.ui.component.AdaptiveMenuItem
import org.spotube.spotube.core.ui.component.TrackOptionsAction
import org.spotube.spotube.core.ui.component.TrackOptionsState
import org.spotube.spotube.core.ui.component.buildTrackMenuItems
import org.spotube.spotube.core.ui.theming.SpotubeTheme
import org.spotube.spotube.modules.settings.Theme
import org.spotube.spotube.modules.settings.UserSettings
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxRepeatMusic
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PlayerTrackMenuTest {
    private val track = MetadataTrack("track", "Song", 1000, null, null, emptyList(),
        null, null, false, null, null, "spotify:track:track")

    @Test fun sharedDefinitionsExposeAllTrackActions() {
        val actions = mutableSetOf<TrackOptionsAction>()
        var items = emptyList<AdaptiveMenuItem>()
        ImageComposeScene(400, 800) {
            SpotubeTheme(UserSettings(theme = Theme.DARK)) {
                items = buildTrackMenuItems(track, TrackOptionsState(), { actions += it }, {})
            }
        }.use { scene ->
            scene.render().close()
            items.filter { it.enabled }.forEach { it.onClick() }
            assertEquals(setOf(TrackOptionsAction.StartRadio, TrackOptionsAction.PlayNext,
                TrackOptionsAction.AddToQueue, TrackOptionsAction.ToggleFavorite,
                TrackOptionsAction.AddToPlaylist, TrackOptionsAction.Download,
                TrackOptionsAction.ToggleBlacklist, TrackOptionsAction.Share), actions)
        }
    }

    @Test fun playingTrackAndJamGuestsHaveSafeQueueActions() {
        val actions = mutableSetOf<TrackOptionsAction>()
        var items = emptyList<AdaptiveMenuItem>()
        ImageComposeScene(400, 800) {
            SpotubeTheme(UserSettings(theme = Theme.DARK)) {
                items = buildTrackMenuItems(track,
                    TrackOptionsState(isInQueue = true, isCurrentlyPlaying = true),
                    { actions += it }, {}, isInJam = true, isJamGuest = true)
            }
        }.use { scene ->
            scene.render().close()
            items.filter { it.enabled }.forEach { it.onClick() }
            assertTrue(TrackOptionsAction.AddToJam in actions)
            assertTrue(TrackOptionsAction.AddToPlaylist in actions)
            assertFalse(TrackOptionsAction.StartRadio in actions)
            assertFalse(TrackOptionsAction.PlayNext in actions)
            assertFalse(TrackOptionsAction.RemoveFromQueue in actions)
            assertFalse(TrackOptionsAction.AddToQueue in actions)
        }
    }

    @Test fun selectionRetainsButtonSizeAndRepeatOneHasVisibleNumber() {
        fun flatten(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::flatten)
        ImageComposeScene(160, 64) {
            SpotubeTheme(UserSettings(theme = Theme.DARK)) {
                Row {
                    for (active in listOf(false, true)) {
                        Box(Modifier.size(48.dp).playerModeSelection(active)) {
                            PlayerModeIcon(Iconsax.IconsaxRepeatMusic, "Repeat", active, repeatOne = active)
                        }
                    }
                }
            }
        }.use { scene ->
            scene.render().close()
            val nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            val modes = nodes.filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
            assertEquals(listOf(false, true), modes.map { it.config[SemanticsProperties.Selected] })
            assertEquals(modes[0].boundsInRoot.size, modes[1].boundsInRoot.size)
            assertTrue(nodes.any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "1" }
            })
        }
    }
}
