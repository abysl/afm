package com.abysl.afm

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentCleanupTest {
    @Test
    fun completedWriteIsKeptEvenWhenCancelledAfterWriting() {
        assertEquals(SaveCleanupDecision.KeepWritten, saveCleanupDecision(true, true, 12L))
        assertEquals(SaveCleanupDecision.KeepWritten, saveCleanupDecision(true, true, null))
    }

    @Test
    fun unopenedExistingDestinationIsNeverDeleted() {
        assertEquals(SaveCleanupDecision.KeepExisting, saveCleanupDecision(false, false, 12L))
        assertEquals(SaveCleanupDecision.KeepExisting, saveCleanupDecision(false, false, 12L, justCreated = true))
        assertEquals(SaveCleanupDecision.KeepExisting, saveCleanupDecision(false, false, null))
    }

    @Test
    fun unopenedEmptyDestinationCanBeRemoved() {
        assertEquals(SaveCleanupDecision.RemovePartial, saveCleanupDecision(false, false, 0L))
        assertEquals(SaveCleanupDecision.RemovePartial, saveCleanupDecision(false, false, null, justCreated = true))
    }

    @Test
    fun openedButFailedDestinationIsCleanedUp() {
        assertEquals(SaveCleanupDecision.RemovePartial, saveCleanupDecision(false, true, 12L))
        assertEquals(SaveCleanupDecision.RemovePartial, saveCleanupDecision(false, true, null))
    }

    @Test
    fun deletesFirstAndFallsBackToTruncation() {
        var truncated = false
        assertEquals(DocumentCleanup.Deleted, cleanupPartialDocument({ true }, { truncated = true; true }))
        assertEquals(false, truncated)
        assertEquals(DocumentCleanup.Truncated, cleanupPartialDocument(
            { throw UnsupportedOperationException("provider does not support deletion") },
            { truncated = true; true },
        ))
        assertEquals(true, truncated)
    }

    @Test
    fun reportsWhenNeitherCleanupIsSupported() {
        assertEquals(DocumentCleanup.Failed, cleanupPartialDocument({ false }, { throw UnsupportedOperationException() }))
    }
}
