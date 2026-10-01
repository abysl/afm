package com.abysl.afm

import blue.rae.spirit.sdk.MeshFailure

data class FileRow(val id: String, val name: String, val size: Long, val author: String, val local: Boolean)

sealed interface FileTransfer {
    data object Queued : FileTransfer
    data class Transferring(val received: Long, val total: Long) : FileTransfer
    data object Verifying : FileTransfer
    data object Completed : FileTransfer
    data class Failed(val reason: MeshFailure, val allProvidersCorrupt: Boolean = false) : FileTransfer
    data object Cancelled : FileTransfer
    data object SourceUnavailable : FileTransfer
}

data class FilesState(
    val entries: List<FileRow> = emptyList(),
    val busy: Boolean = false,
    val importing: Boolean = false,
    val catalogProblem: String? = null,
    val transfers: Map<String, FileTransfer> = emptyMap(),
    val exportMessage: String? = null,
    val message: String? = null,
)

internal fun fileFailureMessage(failure: MeshFailure): String = when (failure) {
    MeshFailure.Corrupt -> "The file failed verification. Try another member."
    MeshFailure.Destination -> "Could not write to the chosen location. Check available space and permissions, then try again."
    MeshFailure.StoreNotConfigured -> "AFM file storage is not set up. Restart AFM; if this continues, report the problem."
    MeshFailure.Unavailable, MeshFailure.Interrupted, MeshFailure.Timeout -> "Source unavailable or connection interrupted. Check group members' connections and retry."
    MeshFailure.Missing -> "That member no longer has the file. Try another member."
    MeshFailure.Io -> "File storage failed. Check available space and try again."
    MeshFailure.SourceRead -> "Could not read the selected file. Choose it again and retry."
    MeshFailure.NotMember -> "This device is no longer in this group."
    MeshFailure.Cancelled -> "Transfer cancelled. You can retry."
    MeshFailure.NodeClosed -> "AFM's node stopped. Restart AFM."
    MeshFailure.NodeBusy -> "AFM's node is in use. Close other AFM windows and retry."
    MeshFailure.Invalid -> "File request was invalid. Select the file again and retry."
    MeshFailure.MeshLimit -> "This device has reached its group limit. Leave another group before retrying."
    MeshFailure.TicketRejected -> "Device access was refused. Reconnect with the group and retry."
    MeshFailure.Node -> "AFM could not complete the file request. Restart AFM and retry."
}

internal fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
