package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadDirectoryTest {
    @Test fun keepsWritableSelection() {
        val attempts = mutableListOf<String>()
        assertEquals("selected", prepareDownloadDirectory("selected", "app", true) { attempts += it })
        assertEquals(listOf("selected"), attempts)
    }

    @Test fun deniedExternalIosFolderFallsBackToAppDocuments() {
        val attempts = mutableListOf<String>()
        val folder = prepareDownloadDirectory("provider", "app", true) {
            attempts += it
            if (it == "provider") error("Operation not permitted")
        }
        assertEquals("app", folder)
        assertEquals(listOf("provider", "app"), attempts)
    }

    @Test fun otherPlatformsDoNotChangeUserDestinationOnFailure() {
        assertFailsWith<IllegalStateException> {
            prepareDownloadDirectory("selected", "app", false) { error("denied") }
        }
    }

    @Test fun reportsFailureWhenAppFolderIsAlsoUnavailable() {
        val attempts = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            prepareDownloadDirectory("provider", "app", true) { attempts += it; error("denied") }
        }
        assertEquals(listOf("provider", "app"), attempts)
    }

    @Test fun doesNotRetryTheSameFolder() {
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            prepareDownloadDirectory("app", "app", true) { attempts++; error("denied") }
        }
        assertEquals(1, attempts)
    }

    @Test fun cancellationNeverChangesDestination() {
        val attempts = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            prepareDownloadDirectory("selected", "app", true) {
                attempts += it
                throw CancellationException()
            }
        }
        assertEquals(listOf("selected"), attempts)
    }
}
