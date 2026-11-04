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

package org.spotube.spotube.core.paths

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSApplicationSupportDirectory
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@OptIn(ExperimentalForeignApi::class)
actual class Paths {
    private val documentsPath: String by lazy {
        requireNotNull(NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory, inDomain = NSUserDomainMask,
            appropriateForURL = null, create = true, error = null)?.path)
    }

    private val privateDataPath: String by lazy {
        val root = requireNotNull(NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory, inDomain = NSUserDomainMask,
            appropriateForURL = null, create = true, error = null)?.path)
        val target = "$root/Spotube".toPath()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(target)
        // Upgrade before any DataStore/plugin is opened. Move only Spotube's internal data,
        // leaving downloaded music and other user documents in place.
        fs.list(documentsPath.toPath()).filter {
            it.name == "plugins" ||
                (it.name.startsWith("spotube") && it.name.endsWith(".preferences_pb"))
        }.forEach { source ->
            val destination = target / source.name
            check(!fs.exists(destination)) { "Conflicting iOS data migration: ${source.name}" }
            fs.atomicMove(source, destination)
        }
        target.toString()
    }

    /** Called before Koin opens databases; Files gets both folders on the first launch. */
    fun prepareUserFolders() {
        getApplicationDataDirPath()
        getUserDownloadsDirPath()
        getMusicCacheDirPath()
        ensureFolder("$documentsPath/Spotube/Music")
    }

    actual fun getApplicationCacheDirPath(): String {
        // Return ios cache dir
        val cacheDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null)

        return requireNotNull(cacheDirectory?.path)
    }

    actual fun getApplicationDataDirPath(): String {
        return privateDataPath
    }

    actual fun getUserDownloadsDirPath(): String {
        return ensureFolder("$documentsPath/Spotube/Downloads")
    }

    actual fun getMusicCacheDirPath(): String {
        // The user requested an accessible audio cache in Files. General transient caches
        // remain in Library/Caches; only the managed music cache is user-visible.
        return ensureFolder("$documentsPath/Spotube/Cache")
    }

    private fun ensureFolder(path: String): String {
        FileSystem.SYSTEM.createDirectories(path.toPath())
        return path
    }
}
