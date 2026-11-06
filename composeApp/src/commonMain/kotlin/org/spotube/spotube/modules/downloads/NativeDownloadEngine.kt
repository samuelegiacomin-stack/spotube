/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

/** Swift registers one process-wide URLSession owner before Koin starts. Calls may be off-main. */
interface NativeDownloadEngine {
    /** Reattaches by durable id; a completed result is replayed until acknowledge() is called. */
    fun start(id: String, url: String, destination: String, listener: NativeDownloadListener)
    fun cancel(id: String)
    fun setSuspended(id: String, suspended: Boolean)
    fun acknowledge(id: String)
    /** Cancel native records removed from the durable common queue before a process crash. */
    fun reconcile(knownIds: List<String>)
    fun setWakeListener(listener: NativeDownloadWakeListener)
    /** Kotlin has persisted its state and handed the next eligible tasks to URLSession. */
    fun queueReady()
}

interface NativeDownloadListener {
    fun onAttached()
    fun onProgress(downloaded: Long, total: Long)
    fun onComplete()
    fun onFailure(message: String)
}

interface NativeDownloadWakeListener {
    fun onWake()
}

object NativeDownloadRegistry {
    var engine: NativeDownloadEngine? = null
}
