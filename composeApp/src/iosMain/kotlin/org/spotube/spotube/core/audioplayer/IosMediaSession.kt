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

@file:OptIn(ExperimentalForeignApi::class)

package org.spotube.spotube.core.audioplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.NSData
import platform.Foundation.create
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.dataTaskWithURL
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.UIKit.UIImage
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

internal class IosMediaSession : PlayerMediaSession {
    private val audioSession = AVAudioSession.sharedInstance()
    private val nowPlayingInfoCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val remoteCommandCenter = MPRemoteCommandCenter.sharedCommandCenter()

    private var configured = false
    private val updateGate = NowPlayingUpdateGate()
    private var artworkUrl: String? = null
    private var artworkTask: NSURLSessionDataTask? = null
    private var artwork: MPMediaItemArtwork? = null
    private val artworkCache = linkedMapOf<String, MPMediaItemArtwork>()
    private val notificationCenter = NSNotificationCenter.defaultCenter()
    private val notificationTokens = mutableListOf<Any>()
    private val remoteCommandTokens = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var onPlay: (() -> Unit)? = null
    private var onPause: (() -> Unit)? = null
    private var onSeek: ((Double) -> Unit)? = null
    private var onNext: (() -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null

    override fun configure(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onSeek: (Double) -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit
    ) {
        this.onPlay = onPlay
        this.onPause = onPause
        this.onSeek = onSeek
        this.onNext = onNext
        this.onPrevious = onPrevious

        if (configured) return
        configured = true

        audioSession.setCategory(
            category = AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeDefault,
            options = 0u,
            error = null)
        registerAudioSessionNotifications()
        registerRemoteCommands()
    }

    override fun activate() {
        audioSession.setActive(true, error = null)
    }

    override fun deactivate() {
        audioSession.setActive(false, error = null)
    }

    override fun update(
        item: MediaItem?,
        positionSeconds: Double,
        durationSeconds: Double,
        isPlaying: Boolean
    ) {
        // Player events also arrive from Kotlin worker coroutines. Serialize native state on main.
        dispatch_async(dispatch_get_main_queue()) {
            updateOnMain(item, positionSeconds, durationSeconds, isPlaying)
        }
    }

    private fun updateOnMain(
        item: MediaItem?, positionSeconds: Double, durationSeconds: Double, isPlaying: Boolean
    ) {
        if (item == null) {
            updateGate.reset()
            resetArtwork()
            nowPlayingInfoCenter.nowPlayingInfo = null
            return
        }

        if (!updateGate.shouldPublish(item, positionSeconds, durationSeconds, isPlaying)) return
        updateArtwork(item)
        val info = buildMap<Any?, Any?> {
            artwork?.let { put(MPMediaItemPropertyArtwork, it) }
            put(MPMediaItemPropertyTitle, item.title)
            put(MPMediaItemPropertyArtist, item.artist)
            item.album.takeIf { it.isNotBlank() }?.let {
                put(MPMediaItemPropertyAlbumTitle, it)
            }
            if (durationSeconds.isFinite() && durationSeconds > 0) {
                put(MPMediaItemPropertyPlaybackDuration, durationSeconds)
            }
            if (positionSeconds.isFinite() && positionSeconds >= 0) {
                put(MPNowPlayingInfoPropertyElapsedPlaybackTime, positionSeconds)
            }
            put(MPNowPlayingInfoPropertyPlaybackRate, if (isPlaying) 1.0 else 0.0)
        }
        nowPlayingInfoCenter.nowPlayingInfo = info
    }

    override fun clear() {
        dispatch_async(dispatch_get_main_queue()) { clearOnMain() }
    }

    private fun clearOnMain() {
        updateGate.reset()
        resetArtwork()
        artworkCache.clear()
        nowPlayingInfoCenter.nowPlayingInfo = null
        notificationTokens.forEach { notificationCenter.removeObserver(it) }
        notificationTokens.clear()
        remoteCommandTokens.forEach { (command, token) -> command.removeTarget(token) }
        remoteCommandTokens.clear()
        configured = false
        onPlay = null
        onPause = null
        onSeek = null
        onNext = null
        onPrevious = null
    }

    private fun resetArtwork() {
        artworkTask?.cancel()
        artworkTask = null
        artworkUrl = null
        artwork = null
    }

    private fun updateArtwork(item: MediaItem) {
        val embedded = item.coverBytes?.takeIf { it.isNotEmpty() && it.size <= 8_000_000 }
        val url = if (embedded != null) "embedded:${item.url}" else item.coverURL
        // Never fetch or decode the cover on each playback-position event.
        if (artworkUrl == url) return
        resetArtwork()
        artworkUrl = url
        artwork = artworkCache[url]
        if (artwork != null || url.isBlank()) return
        if (embedded != null) {
            val image = embedded.usePinned {
                UIImage(data = NSData.create(bytes = it.addressOf(0), length = embedded.size.toULong()))
            } ?: return
            artwork = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
            artworkCache[url] = artwork!!
            if (artworkCache.size > 4) artworkCache.remove(artworkCache.keys.first())
            return
        }
        val nativeUrl = NSURL.URLWithString(url) ?: return
        artworkTask = NSURLSession.sharedSession.dataTaskWithURL(nativeUrl) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode ?: 200L
            if (error != null || data == null || data.length == 0UL ||
                data.length > 8_000_000UL || status !in 200L..299L) return@dataTaskWithURL
            val image = UIImage(data = data) ?: return@dataTaskWithURL
            dispatch_async(dispatch_get_main_queue()) {
                // A late response must never replace the cover of a newer track.
                if (artworkUrl != url) return@dispatch_async
                val loaded = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
                artwork = loaded
                artworkTask = null
                artworkCache[url] = loaded
                if (artworkCache.size > 4) artworkCache.remove(artworkCache.keys.first())
                val info = nowPlayingInfoCenter.nowPlayingInfo?.toMutableMap() ?: return@dispatch_async
                info[MPMediaItemPropertyArtwork] = loaded
                nowPlayingInfoCenter.nowPlayingInfo = info
            }
        }.also { it.resume() }
    }

    private fun registerAudioSessionNotifications() {
        notificationCenter.addObserverForName(
            name = platform.AVFAudio.AVAudioSessionInterruptionNotification,
            `object` = audioSession,
            queue = NSOperationQueue.mainQueue()) { notification ->
            val type = notification?.userInfo?.get(
                platform.AVFAudio.AVAudioSessionInterruptionTypeKey) as? NSNumber
            if (type?.unsignedLongValue == platform.AVFAudio.AVAudioSessionInterruptionTypeBegan) {
                onPause?.invoke()
            } else if (type?.unsignedLongValue == platform.AVFAudio.AVAudioSessionInterruptionTypeEnded) {
                activate()
                val options = notification?.userInfo?.get(
                    platform.AVFAudio.AVAudioSessionInterruptionOptionKey) as? NSNumber
                val shouldResume = options?.unsignedLongValue
                    ?.and(platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume)
                    ?.let { it != 0UL } == true
                if (shouldResume) onPlay?.invoke()
            }
        }?.let { notificationTokens += it }
    }

    private fun registerRemoteCommands() {
        fun register(command: MPRemoteCommand, handler: (platform.MediaPlayer.MPRemoteCommandEvent?) -> platform.MediaPlayer.MPRemoteCommandHandlerStatus) {
            command.addTargetWithHandler(handler)?.let {
                remoteCommandTokens += command to it
            }
        }

        register(remoteCommandCenter.playCommand) {
            onPlay?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(remoteCommandCenter.pauseCommand) {
            onPause?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(remoteCommandCenter.changePlaybackPositionCommand) { event ->
            val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (position != null && position.isFinite() && position >= 0) {
                onSeek?.invoke(position)
                MPRemoteCommandHandlerStatusSuccess
            } else {
                MPRemoteCommandHandlerStatusCommandFailed
            }
        }
        register(remoteCommandCenter.nextTrackCommand) {
            onNext?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }
        register(remoteCommandCenter.previousTrackCommand) {
            onPrevious?.invoke()
            MPRemoteCommandHandlerStatusSuccess
        }
    }
}
