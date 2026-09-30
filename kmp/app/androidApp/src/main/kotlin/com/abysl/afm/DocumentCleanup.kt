package com.abysl.afm

internal fun cleanupPartialDocument(delete: () -> Boolean, truncate: () -> Boolean): DocumentCleanup {
    if (runCatching(delete).getOrDefault(false)) return DocumentCleanup.Deleted
    return if (runCatching(truncate).getOrDefault(false)) DocumentCleanup.Truncated else DocumentCleanup.Failed
}

internal enum class SaveCleanupDecision { KeepWritten, KeepExisting, RemovePartial }

internal fun saveCleanupDecision(written: Boolean, opened: Boolean, originalSize: Long?, justCreated: Boolean = false): SaveCleanupDecision = when {
    written -> SaveCleanupDecision.KeepWritten
    !opened && (originalSize?.let { it != 0L } ?: !justCreated) -> SaveCleanupDecision.KeepExisting
    else -> SaveCleanupDecision.RemovePartial
}
