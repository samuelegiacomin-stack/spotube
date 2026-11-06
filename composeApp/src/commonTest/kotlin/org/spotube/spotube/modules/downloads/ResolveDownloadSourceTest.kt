/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ResolveDownloadSourceTest {
    @Test fun retriesMissingSourceOnceBeforeStartingATransfer() = runTest {
        var requests = 0
        assertEquals("audio", resolveDownloadSource { if (++requests == 1) null else "audio" })
        assertEquals(2, requests)
    }
    @Test fun permanentMissingSourceIsBoundedAndCancellationDoesNotRetry() = runTest {
        var requests = 0
        assertNull(resolveDownloadSource<String> { requests++; null })
        assertEquals(2, requests)
        requests = 0
        assertFailsWith<CancellationException> {
            resolveDownloadSource<String> { requests++; throw CancellationException("cancelled") }
        }
        assertEquals(1, requests)
    }
    @Test fun timedOutLookupCanRecoverAndSuccessfulLookupDoesNotRetry() = runTest {
        var requests = 0
        assertEquals("audio", resolveDownloadSource { if (++requests == 1) { delay(30_000); null } else "audio" })
        assertEquals(2, requests)
        requests = 0
        assertEquals("audio", resolveDownloadSource { requests++; "audio" })
        assertEquals(1, requests)
    }
}
