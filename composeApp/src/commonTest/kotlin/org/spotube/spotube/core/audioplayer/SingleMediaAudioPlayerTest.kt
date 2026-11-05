/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class SingleMediaAudioPlayerTest {
    private class Engine : NativePlayerEngine {
        var callback: NativePlayerListener? = null
        val loads = mutableListOf<String>()
        var lastSeekMs = -1L
        override fun setListener(listener: NativePlayerListener?) { callback = listener }
        override fun load(url: String, autoPlay: Boolean, startPositionMs: Long) {
            loads += url
            if (autoPlay) callback?.onState(NativePlayerState.PLAYING)
        }
        override fun play() { callback?.onState(NativePlayerState.PLAYING) }
        override fun pause() { callback?.onState(NativePlayerState.PAUSED) }
        override fun stop() { callback?.onState(NativePlayerState.IDLE) }
        override fun seekTo(positionMs: Long) { lastSeekMs = positionMs }
        override fun setVolume(volume: Float) {}
        override fun setRate(rate: Float) {}
        override fun destroy() {}
    }
    private object Session : PlayerMediaSession {
        override fun configure(onPlay: () -> Unit, onPause: () -> Unit, onSeek: (Double) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {}
        override fun activate() {}
        override fun deactivate() {}
        override fun clear() {}
        override fun update(item: MediaItem?, positionSeconds: Double, durationSeconds: Double, isPlaying: Boolean) {}
    }
    private fun item(url: String) = MediaItem(url, "Artist", "Album", 10.seconds, "", url)
    private fun scenario(test: suspend (SingleMediaAudioPlayer, Engine) -> Unit) = runBlocking {
        val engine = Engine()
        val player = SingleMediaAudioPlayer(Unit, engine, Session)
        try { test(player, engine) } finally { player.dispose() }
    }
    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(3_000) {
        while (!predicate()) delay(2)
    }
    @Test fun automaticallyPlaysEveryLocalAndStreamingItemInOrder() = scenario { player, engine ->
        val urls = (0..19).map { if (it % 2 == 0) "/Music/My song $it.m4a" else "http://127.0.0.1/stream/$it" }
        player.load(urls.map(::item), true, 0)
        for (index in 1..urls.lastIndex) {
            engine.callback!!.onState(NativePlayerState.ENDED)
            awaitCondition { player.currentMediaItemFlow.value?.url == urls[index] && player.playerStateFlow.value == PlayerState.PLAYING }
        }
        assertEquals(urls, engine.loads)
        engine.callback!!.onState(NativePlayerState.ENDED)
        awaitCondition { player.playerStateFlow.value == PlayerState.COMPLETED }
    }
    @Test fun duplicateAndStaleCallbacksCannotSkipOrStopSuccessor() = scenario { player, engine ->
        player.load(listOf(item("a"), item("b"), item("c")), true, 0)
        val retired = engine.callback!!
        retired.onState(NativePlayerState.ENDED)
        retired.onState(NativePlayerState.ENDED)
        awaitCondition { player.currentMediaItemFlow.value?.url == "b" }
        retired.onState(NativePlayerState.IDLE)
        retired.onTime(999999)
        retired.onError("Late error")
        delay(20)
        assertEquals("b", player.currentMediaItemFlow.value?.url)
        assertEquals(PlayerState.PLAYING, player.playerStateFlow.value)
        assertEquals(0L, player.positionFlow.value.inWholeMilliseconds)
    }
    @Test fun oldCompletionDoesNotOverrideManualSelection() = scenario { player, engine ->
        player.load(listOf(item("a"), item("b"), item("c")), true, 0)
        val retired = engine.callback!!
        player.jumpTo(2)
        retired.onState(NativePlayerState.ENDED)
        delay(20)
        assertEquals("c", player.currentMediaItemFlow.value?.url)
        assertEquals(listOf("a", "c"), engine.loads)
    }
    @Test fun removingCurrentItemInvalidatesItsQueuedEndEvent() = scenario { player, engine ->
        val a = item("a")
        player.load(listOf(a, item("b"), item("c")), true, 0)
        val retired = engine.callback!!
        player.removeMediaItem(a)
        retired.onState(NativePlayerState.ENDED)
        delay(20)
        assertEquals("b", player.currentMediaItemFlow.value?.url)
        assertEquals(PlayerState.IDLE, player.playerStateFlow.value)
        assertEquals(listOf("a"), engine.loads)
    }
    @Test fun loopOneReloadsOncePerEndAndLoopAllWraps() = scenario { player, engine ->
        player.load(listOf(item("a"), item("b")), true, 0)
        player.loop(LoopState.ONE)
        val first = engine.callback!!
        first.onState(NativePlayerState.ENDED); first.onState(NativePlayerState.ENDED)
        awaitCondition { engine.loads.size == 2 }
        assertEquals(listOf("a", "a"), engine.loads)
        player.loop(LoopState.ALL)
        player.jumpTo(1)
        engine.callback!!.onState(NativePlayerState.ENDED)
        awaitCondition { player.currentMediaItemFlow.value?.url == "a" }
    }
    @Test fun recommendationsArrivingAfterEndResumeTheSameQueue() = scenario { player, engine ->
        player.load(listOf(item("a")), true, 0)
        engine.callback!!.onState(NativePlayerState.ENDED)
        awaitCondition { player.playerStateFlow.value == PlayerState.COMPLETED }
        engine.callback!!.onState(NativePlayerState.IDLE)
        assertEquals(PlayerState.COMPLETED, player.playerStateFlow.value)
        player.addMediaItems(listOf(item("b"), item("c")))
        player.continueAfterQueueEnd("a")
        assertEquals("b", player.currentMediaItemFlow.value?.url)
        assertEquals(PlayerState.PLAYING, player.playerStateFlow.value)
    }
    @Test fun explicitPauseAfterEndBlocksLateRecommendationResume() = scenario { player, engine ->
        player.load(listOf(item("a")), true, 0)
        engine.callback!!.onState(NativePlayerState.ENDED)
        awaitCondition { player.playerStateFlow.value == PlayerState.COMPLETED }
        player.pause()
        player.addMediaItems(listOf(item("b")))
        player.continueAfterQueueEnd("a")
        assertEquals("a", player.currentMediaItemFlow.value?.url)
        assertEquals(PlayerState.PAUSED, player.playerStateFlow.value)
    }
    @Test fun lateRecommendationsCannotRestartStoppedOrDifferentTrack() = scenario { player, engine ->
        player.load(listOf(item("a")), true, 0)
        engine.callback!!.onState(NativePlayerState.ENDED)
        awaitCondition { player.playerStateFlow.value == PlayerState.COMPLETED }
        player.stop()
        player.addMediaItems(listOf(item("b")))
        player.continueAfterQueueEnd("a")
        assertEquals(PlayerState.IDLE, player.playerStateFlow.value)
        player.load(listOf(item("x"), item("y")), false, 0)
        player.continueAfterQueueEnd("a")
        assertEquals("x", player.currentMediaItemFlow.value?.url)
        assertEquals(PlayerState.PAUSED, player.playerStateFlow.value)
    }
    @Test fun bulkAppendPreservesOrderAndDoesNotStartPausedPlayback() = scenario { player, engine ->
        player.load(listOf(item("a")), false, 0)
        player.addMediaItems((1..5000).map { item("$it") })
        assertEquals(5001, player.playlistFlow.value.size)
        assertEquals("5000", player.playlistFlow.value.last().url)
        assertTrue(engine.loads.isEmpty())
        assertEquals(PlayerState.PAUSED, player.playerStateFlow.value)
    }
    @Test fun disposedPlayerIgnoresOldCallbacks() = scenario { player, engine ->
        player.load(listOf(item("a"), item("b")), true, 0)
        val retired = engine.callback!!
        player.dispose()
        retired.onState(NativePlayerState.ENDED)
        delay(20)
        assertEquals(listOf("a"), engine.loads)
        assertTrue(player.playlistFlow.value.isEmpty())
    }

    private suspend fun delayedRecommendationScenario(
        player: SingleMediaAudioPlayer, engine: Engine, replaceQueue: Boolean,
    ) {
        org.koin.core.context.startKoin {
            modules(org.koin.dsl.module { factory { (tag: String?) -> co.touchlab.kermit.Logger.withTag(tag ?: "test") } })
        }
        try {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            fun track(id: String) = org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack(
                id, id, 10000, null, null, emptyList(), null, null, null, null, null, null)
            var requests = 0
            val api = object : org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrackAPI by FakeMetadataTrackAPI() {
                override suspend fun recommendationsBasedOnTracks(seedTrackIds: List<String>, limit: Int): List<org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack> {
                    if (++requests > 1) return emptyList()
                    started.complete(Unit); response.await()
                    return listOf(track("b"))
                }
            }
            val queue = DeviceAudioPlayerQueue(player,
                FakeSettingsProvider(org.spotube.spotube.modules.settings.UserSettings(enableEndlessPlayback = true)),
                FakeAudioPlayerQueueRepository(), FakePluginProvider(FakePluginService(api)), FakeBlacklistRepository(),
                org.spotube.spotube.core.server.PlaybackProxyEndpoint().apply { publish(14769) })
            queue.load(listOf(QueueEntry.StreamingTrack(track("a"), "")), true, 0, null)
            withTimeout(3000) { started.await() }
            if (replaceQueue) {
                queue.load(listOf(QueueEntry.LocalTrack("Own", emptyList(), 1000, null, url = "/Music/Own.m4a")), false, 0, null)
            } else {
                engine.callback!!.onState(NativePlayerState.ENDED)
                awaitCondition { player.playerStateFlow.value == PlayerState.COMPLETED }
            }
            response.complete(Unit)
            if (replaceQueue) {
                delay(100)
                assertEquals(listOf("/Music/Own.m4a"), player.playlistFlow.value.map { it.url })
                assertEquals(PlayerState.PAUSED, player.playerStateFlow.value)
            } else {
                awaitCondition { player.currentMediaItemFlow.value?.url?.endsWith("/stream/b") == true && player.playerStateFlow.value == PlayerState.PLAYING }
                queue.setEndlessPlaybackSuppressed(true)
            }
        } finally { org.koin.core.context.stopKoin() }
    }
    @Test fun realQueueResumesWhenRecommendationsFinishAfterTheSong() = scenario { player, engine ->
        delayedRecommendationScenario(player, engine, replaceQueue = false)
    }
    @Test fun realQueueDiscardsRecommendationsAfterUserReplacesIt() = scenario { player, engine ->
        delayedRecommendationScenario(player, engine, replaceQueue = true)
    }
    @Test fun systemPlayerSeekUsesSecondsInsteadOfMilliseconds() = runBlocking {
        val engine = Engine()
        var seek: ((Double) -> Unit)? = null
        val session = object : PlayerMediaSession by Session {
            override fun configure(onPlay: () -> Unit, onPause: () -> Unit, onSeek: (Double) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
                seek = onSeek
            }
        }
        val player = SingleMediaAudioPlayer(Unit, engine, session)
        try {
            player.load(listOf(item("a")), true, 0)
            seek!!(5.0)
            awaitCondition { engine.lastSeekMs == 5000L }
            assertEquals(5000L, player.positionFlow.value.inWholeMilliseconds)
        } finally { player.dispose() }
    }
}
