/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Retry only source lookup, before any transfer exists; cancellation always propagates. */
internal suspend fun <T> resolveDownloadSource(resolve: suspend () -> T?): T? {
    val first = withTimeoutOrNull(20_000) { resolve() }
    if (first != null) return first
    delay(1_000)
    return withTimeoutOrNull(20_000) { resolve() }
}
