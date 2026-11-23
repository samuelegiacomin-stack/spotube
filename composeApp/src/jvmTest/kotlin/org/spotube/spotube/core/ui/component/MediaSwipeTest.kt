/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.spotube.spotube.core.ui.theming.SpotubeTheme
import org.spotube.spotube.modules.settings.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class MediaSwipeTest {
    @Test
    fun queueSwipeAndCoverSwipeRespectDirectionAndLeaveTapsAndVerticalScrollingAlone() {
        val actions = mutableListOf<String>()
        var scrollPosition = { 0 }
        ImageComposeScene(390, 500) {
            SpotubeTheme(UserSettings()) {
                val scroll = rememberScrollState()
                scrollPosition = { scroll.value }
                Column(Modifier.verticalScroll(scroll)) {
                    QueueSwipeRow(onEnqueue = { actions.add("queue") }, enabled = true) {
                        Box(Modifier.fillMaxWidth().height(136.dp).clickable { actions.add("tap") })
                    }
                    Box(
                        Modifier.fillMaxWidth().height(230.dp).coverTrackSwipe(
                            onLeft = { actions.add("next") },
                            onRight = { actions.add("previous") },
                        ),
                    )
                    Box(Modifier.fillMaxWidth().height(420.dp))
                }
            }
        }.use { scene ->
            var millis = 0L
            fun step(count: Int = 1) = repeat(count) {
                millis += 16
                scene.render(millis * 1_000_000).close()
            }
            fun event(type: PointerEventType, pos: Offset) {
                scene.sendPointerEvent(type, pos, type = PointerType.Touch, timeMillis = millis)
                step()
            }
            fun swipe(start: Offset, delta: Offset) {
                event(PointerEventType.Press, start)
                repeat(5) { index ->
                    event(PointerEventType.Move, start + delta * ((index + 1) / 5f))
                }
                event(PointerEventType.Release, start + delta)
                step(30)
            }
            step(10)
            val row = Offset(185f, 60f)
            swipe(row, Offset(0f, 0f))
            swipe(row, Offset(125f, 0f))
            swipe(row, Offset(-125f, 0f))
            swipe(row, Offset(38f, 0f))
            assertEquals(listOf("tap", "queue"), actions)

            val cover = Offset(185f, 290f)
            swipe(cover, Offset(-130f, 0f))
            swipe(cover, Offset(130f, 0f))
            swipe(cover, Offset(5f, -145f))
            assertEquals(listOf("tap", "queue", "next", "previous"), actions)
            kotlin.test.assertTrue(scrollPosition() > 40, "Scrolling toward lyrics should still move the page")
        }
    }
}
