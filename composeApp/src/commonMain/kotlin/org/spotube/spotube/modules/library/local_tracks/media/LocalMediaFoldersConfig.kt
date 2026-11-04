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

package org.spotube.spotube.modules.library.local_tracks.media

import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.modules.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.spotube.spotube.modules.settings.UserSettings

class LocalMediaFoldersConfig(
    private val settingsRepository: SettingsRepository,
    private val paths: Paths) {
    val configuredRootsFlow: Flow<List<String>> = settingsRepository.userSettings.map { settings ->
        configuredLocalMediaRoots(settings, paths.getUserDownloadsDirPath(),
            if (settings.enableMusicCaching) paths.getMusicCacheDirPath() else null)
    }.distinctUntilChanged()
}

internal fun configuredLocalMediaRoots(settings: UserSettings, defaultDownloads: String, defaultCache: String?): List<String> =
    (listOf(defaultDownloads) + listOfNotNull(settings.overloadedDownloadFolder) +
        settings.localMediaFolders +
        if (settings.enableMusicCaching) listOfNotNull(settings.cacheFolder ?: defaultCache) else emptyList())
        .map { it.trim().trimEnd('/').ifBlank { if (it.trim() == "/") "/" else "" } }
        .filter { it.isNotBlank() }
        .distinct()
