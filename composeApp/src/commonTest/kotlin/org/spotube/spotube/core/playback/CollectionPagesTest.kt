/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import kotlin.test.*

class CollectionPagesTest {
    @Test fun parallelPagesRemainOrderedAndConcurrencyIsBounded() = runTest {
        var active = 0; var peak = 0
        val items = loadCollectionPages { strategy ->
            val page = strategy as PaginationStrategy.Offset
            active++; peak = maxOf(peak, active)
            delay(if (page.offset == 100) 90 else 30)
            active--
            val end = minOf(page.offset + page.limit, 750)
            PaginationResult((page.offset until end).toList(), 750,
                if (end < 750) PaginationStrategy.Offset(end, page.limit) else null)
        }
        assertEquals((0 until 750).toList(), items)
        assertEquals(4, peak)
    }

    @Test fun providerClampingPageSizeDoesNotSkipTracks() = runTest {
        val items = loadCollectionPages { strategy ->
            val p = strategy as PaginationStrategy.Offset
            val end = minOf(p.offset + minOf(50, p.limit), 230)
            PaginationResult((p.offset until end).toList(), 230,
                if (end < 230) PaginationStrategy.Offset(end, p.limit) else null)
        }
        assertEquals((0 until 230).toList(), items)
    }

    @Test fun failedPageCannotBeReportedAsCompletePlaylist() = runTest {
        assertFailsWith<IllegalStateException> {
            loadCollectionPages { strategy ->
                val p = strategy as PaginationStrategy.Offset
                if (p.offset == 100) null else PaginationResult(listOf(p.offset), 250,
                    PaginationStrategy.Offset(100, 100))
            }
        }
    }

    @Test fun cursorPluginsAndEmptyCollectionsKeepTheirContract() = runTest {
        val items = loadCollectionPages { strategy ->
            if (strategy is PaginationStrategy.Offset) PaginationResult(listOf(1), 0, PaginationStrategy.Cursor("next", 20))
            else PaginationResult(listOf(2), 0, null)
        }
        assertEquals(listOf(1, 2), items)
        assertTrue(loadCollectionPages<Int> { PaginationResult(emptyList(), 0, null) }.isEmpty())
    }
}
