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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsLine
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.core.navigation.Navigator
import org.spotube.spotube.core.navigation.Routes
import org.spotube.spotube.core.navigation.TOP_LEVEL_ROUTES
import org.spotube.spotube.core.navigation.rememberNavigationState
import org.spotube.spotube.core.ui.theming.SpotubeTheme
import org.spotube.spotube.modules.settings.AppearanceStyle
import org.spotube.spotube.modules.settings.Theme
import org.spotube.spotube.modules.settings.UserSettings
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
class IllustratedPlayerTest {
    @Test
    fun illustratedThemesCoverTheDockAndRenderTheDragAndRestPoses() {
        val output = File("build/reports/animated-player").apply { mkdirs() }
        val cases = listOf(
            Triple(AppearanceStyle.TORMENTIL, Theme.DARK, 390),
            Triple(AppearanceStyle.BLUE_POND, Theme.DARK, 320),
            Triple(AppearanceStyle.STRAWBERRIES, Theme.LIGHT, 390),
        )
        val height = 844
        for ((style, theme, width) in cases) {
            val art = assertNotNull(style.illustratedPlayerArt())
            val viewModel = AppShellViewModel()
            var illustratedMotion: IllustratedPlayerMotion? = null
            val lifecycleOwner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            }
            val background = mutableStateOf(Color(0xFFFF00FF))
            ImageComposeScene(width, height) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    SpotubeTheme(UserSettings(theme = theme, appearanceStyle = style)) {
                        val navigation = rememberNavigationState(Routes.Home, TOP_LEVEL_ROUTES)
                        val navigator = remember(navigation) { Navigator(navigation) }
                        CompactPlayerOverlay(
                            navigator = navigator,
                            navigationState = navigation,
                            viewModel = viewModel,
                            onQueue = {}, onAlternativeSource = {}, onExpandLyrics = {},
                            illustratedArt = art,
                            floatingPlayer = { modifier, expand ->
                                illustratedMotion = LocalIllustratedPlayer.current
                                FloatingPlayerContent(
                                    playerUiState = PlayerUiState(),
                                    modifier = modifier,
                                    onPlayPause = {},
                                    onExpand = expand,
                                    heartButton = {},
                                )
                            },
                            expandedPlayer = { modifier, _ ->
                                Column(modifier.fillMaxSize().background(Color.DarkGray)) {
                                    Spacer(Modifier.fillMaxWidth().height(650.dp))
                                    IllustratedPlayerScene(assertNotNull(LocalIllustratedPlayer.current), 194.dp)
                                }
                            },
                            bottomBar = { modifier ->
                                AppBottombar(navigator, navigation, modifier, libraryBadge = {})
                            },
                        ) { Box(Modifier.fillMaxSize().background(background.value)) }
                    }
                }
            }.use { scene ->
                var time = 0L
                fun step(count: Int) = repeat(count) {
                    scene.render(time).close()
                    time += 16_000_000L
                }
                fun screenshot(label: String? = null): BufferedImage {
                    val png = scene.render(time).use { image ->
                        assertNotNull(image.encodeToData()).use { it.bytes }
                    }
                    if (label != null) output.resolve("${style.name.lowercase()}-${label}.png").writeBytes(png)
                    return ImageIO.read(ByteArrayInputStream(png))
                }
                step(15)
                val dock = screenshot("dock")
                val motion = assertNotNull(illustratedMotion)
                val firstPageColor = background.value.toArgb()
                assertNoPageAtLowerEdges(dock, firstPageColor, label = style.name)
                assertEquals(dock.getRGB(0, height - 100), dock.getRGB(0, height - 2),
                    "${style.name} should use the same surface at the edges of both rows")
                if (style == AppearanceStyle.TORMENTIL) {
                    val dockHeight = assertNotNull(viewModel.compactChromeUiState.value.measuredBottomBarHeight)
                    val dockTop = height - dockHeight.value.toInt()
                    val peekX = motion.peekBounds.center.x.toInt()
                    val showsPaws = (dockTop + 1..dockTop + 6).any { y ->
                        (peekX - 19..peekX + 19).any { x -> dock.getRGB(x, y) != dock.getRGB(0, y) }
                    }
                    assertTrue(showsPaws, "The fox's paws should overlap the dock")
                }
                background.value = Color.Cyan
                step(8)
                val differentPage = screenshot("dock-second-background")
                assertEquals(Color.Cyan.toArgb(), differentPage.getRGB(5, 20))
                for (y in height - 105 until height) {
                    for (x in listOf(0, width - 1)) {
                        assertEquals(dock.getRGB(x, y), differentPage.getRGB(x, y),
                            "${style.name} dock edge should be opaque at (${x}, ${y})")
                    }
                }

                val start = Offset(width / 2f, height - 104f)
                fun pointer(type: PointerEventType, pos: Offset) =
                    scene.sendPointerEvent(type, pos, type = PointerType.Touch, timeMillis = time / 1_000_000L)
                pointer(PointerEventType.Press, start)
                step(3)
                pointer(PointerEventType.Move, start.copy(y = start.y - 24f))
                step(3)
                val gripX = motion.peekBounds.center.x.toInt()
                assertTrue(gripX >= width * .57f, "The mascot's resting anchor should be on the right")
                fun verifyGrabAt(pull: Float, label: String): Int {
                    pointer(PointerEventType.Move, start.copy(y = start.y - pull))
                    step(4)
                    val hanging = screenshot(label)
                    val pageColor = background.value.toArgb()
                    // Find the actual visible edge in the render, outside the mascot's lane.
                    val sheetTop = assertNotNull((20 until height - 105).firstOrNull { y ->
                        hanging.getRGB(5, y) != pageColor
                    })
                    assertTrue(sheetTop in 40 until height - 110,
                        "${style.name} should still be partway open at $label")
                    val showsPaws = (sheetTop - 6 until sheetTop - 1).any { y ->
                        (gripX - 18..gripX + 18).any { x -> hanging.getRGB(x, y) != pageColor }
                    }
                    assertTrue(showsPaws, "${style.name} should grab the visible sheet edge at y=$sheetTop ($label)")
                    return sheetTop
                }
                val firstGrip = verifyGrabAt(120f, "hang-low")
                val middleGrip = verifyGrabAt(300f, "hang")
                val highGrip = verifyGrabAt(465f, "hang-high")
                val returningGrip = verifyGrabAt(310f, "hang-reversed")
                assertTrue(firstGrip > middleGrip && middleGrip > highGrip,
                    "${style.name} should climb with the sheet")
                assertTrue(returningGrip > highGrip && abs(middleGrip - returningGrip) < 20,
                    "${style.name} should stay attached when the drag reverses")

                pointer(PointerEventType.Move, start.copy(y = 50f))
                step(8)
                pointer(PointerEventType.Release, start.copy(y = 50f))
                // Allow both the sheet spring and the strawberry's 1.8 s arrival to finish.
                step(182)
                val resting = screenshot("rest")
                assertEquals(Color.DarkGray.toArgb(), resting.getRGB(5, 20), "${style.name} should be open")
                assertTrue(resting.getRGB(2, height - 2) != Color.DarkGray.toArgb(), "scene should reach bottom-left")
                assertTrue(resting.getRGB(width - 3, height - 2) != Color.DarkGray.toArgb(), "scene should reach bottom-right")
                if (style == AppearanceStyle.STRAWBERRIES) {
                    val sceneBounds = motion.sceneBounds
                    assertTrue(abs(sceneBounds.width / sceneBounds.height - 3f) < .03f,
                        "The art should keep its original aspect ratio")
                }
                if (style == AppearanceStyle.BLUE_POND) {
                    val paddedCrop = (width * .50f).toInt() until (width * .77f).toInt()
                    var strongest = resting
                    var mostChanged = 0
                    repeat(16) {
                        step(9)
                        val sample = screenshot()
                        val changed = (height - 139 until height - 39).sumOf { y ->
                            paddedCrop.count { x -> sample.getRGB(x, y) != resting.getRGB(x, y) }
                        }
                        if (changed > mostChanged) {
                            strongest = sample
                            mostChanged = changed
                        }
                    }
                    assertTrue(mostChanged > 8, "The frog's throat should pulse while the player is open")
                    ImageIO.write(strongest, "png", output.resolve("blue_pond-croak.png"))
                }

                val bounds = motion.sceneBounds
                val mascot = Offset(
                    bounds.left + bounds.width * art.landingX,
                    bounds.top + bounds.height * art.landingY - 22f,
                )
                val beforeTap = screenshot()
                pointer(PointerEventType.Press, mascot)
                step(2)
                pointer(PointerEventType.Release, mascot)
                step(25)
                assertEquals(1, motion.reactionRequests, "${style.name} should respond to a tap on the mascot")
                val reacting = screenshot("reaction")
                val changed = ((mascot.y.toInt() - 36)..(mascot.y.toInt() + 24)).sumOf { y ->
                    ((mascot.x.toInt() - 36)..(mascot.x.toInt() + 36)).count { x ->
                        reacting.getRGB(x, y) != beforeTap.getRGB(x, y)
                    }
                }
                assertTrue(changed > 30, "${style.name} should visibly react")
                step(130)
                if (style == AppearanceStyle.STRAWBERRIES) {
                    val resurfaced = screenshot()
                    assertEquals(
                        beforeTap.getRGB(mascot.x.toInt(), mascot.y.toInt()),
                        resurfaced.getRGB(mascot.x.toInt(), mascot.y.toInt()),
                        "The berry should return to its fully settled position",
                    )
                }

                pointer(PointerEventType.Press, Offset(width / 2f, 45f))
                step(3)
                pointer(PointerEventType.Move, Offset(width / 2f, height - 110f))
                step(6)
                pointer(PointerEventType.Release, Offset(width / 2f, height - 110f))
                step(110)
                assertNoPageAtLowerEdges(screenshot("collapsed"), background.value.toArgb(), label = style.name)
            }
        }
    }

    @Test
    fun lyricsFrameContinuesTheSceneAtBothSidesAndUnderTheCard() {
        val lines = listOf(
            LyricsLine(0L, "The room is quiet now"),
            LyricsLine(500L, "And the evening settles in"),
            LyricsLine(1000L, "We follow the music"),
        )
        val output = File("build/reports/animated-player").apply { mkdirs() }
        val sampleWidth = 390
        val themes = listOf(
            AppearanceStyle.TORMENTIL to Theme.DARK,
            AppearanceStyle.BLUE_POND to Theme.DARK,
            AppearanceStyle.STRAWBERRIES to Theme.LIGHT,
        )
        for ((style, theme) in themes) {
            val motion = IllustratedPlayerMotion(assertNotNull(style.illustratedPlayerArt()),
                progress = { 1f }, expanded = { true })
            var background = 0
            ImageComposeScene(sampleWidth, 418) {
                SpotubeTheme(UserSettings(theme = theme, appearanceStyle = style)) {
                    val canvas = AppTheme.colors.background
                    background = canvas.toArgb()
                    Column(Modifier.fillMaxSize().background(canvas)) {
                        IllustratedPlayerScene(motion, 160.dp) {
                            LyricsPreviewCard(
                                lines = lines,
                                currentIndex = 1,
                                onExpand = {},
                                illustratedStyle = style,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .padding(top = 8.dp, bottom = 20.dp),
                            )
                        }
                    }
                }
            }.use { scene ->
                repeat(36) { scene.render(it * 16_000_000L).close() }
                val png = scene.render(600_000_000L).use { image ->
                    assertNotNull(image.encodeToData()).use { it.bytes }
                }
                output.resolve("${style.name.lowercase()}-lyrics.png").writeBytes(png)
                val image = ImageIO.read(ByteArrayInputStream(png))
                val seam = motion.sceneBounds.bottom.toInt()
                for (x in listOf(4, 10, sampleWidth - 11, sampleWidth - 5)) {
                    val before = image.getRGB(x, seam - 1)
                    val after = image.getRGB(x, seam)
                    val delta = listOf(0, 8, 16).maxOf { shift ->
                        abs(((before shr shift) and 255) - ((after shr shift) and 255))
                    }
                    assertTrue(delta <= 16, "${style.name} scene changes abruptly at the lyrics seam")
                    assertTrue(image.getRGB(x, seam + 96) != background, "Art should appear at the card's sides")
                }
                assertTrue(image.getRGB(sampleWidth / 2, seam + 215) != background,
                    "${style.name} art should continue below the lyrics card")
            }
        }
    }

    private fun assertNoPageAtLowerEdges(image: BufferedImage, pageColor: Int, label: String) {
        for (y in image.height - 105 until image.height) {
            for (x in listOf(0, 1, image.width - 2, image.width - 1)) {
                assertTrue(image.getRGB(x, y) != pageColor, "${label} exposed the page at (${x}, ${y})")
            }
        }
    }
}
