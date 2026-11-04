package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.CancellationException

/** Old iOS folder selections stored a path without a persistent security-scoped bookmark. */
internal fun prepareDownloadDirectory(
    selected: String,
    fallback: String,
    allowFallback: Boolean,
    prepare: (String) -> Unit
): String {
    try {
        prepare(selected)
        return selected
    } catch (error: Exception) {
        if (error is CancellationException || !allowFallback || selected == fallback) throw error
        prepare(fallback)
        return fallback
    }
}
