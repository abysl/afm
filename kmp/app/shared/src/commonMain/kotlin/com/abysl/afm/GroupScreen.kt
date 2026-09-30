package com.abysl.afm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import blue.rae.spirit.sdk.GroupState
import blue.rae.spirit.sdk.LeftMesh

internal fun departureMessage(left: LeftMesh): String = when {
    left.remainingMembers == 0 -> "Left ${left.meshName}. The last member left, so the group was deleted."
    left.notifiedMembers == 0 -> "Left ${left.meshName}. No other members were notified. They can learn of your departure when they next reach this device while it keeps the departed copy."
    else -> {
        val members = if (left.remainingMembers == 1) "member" else "members"
        val count = "Left ${left.meshName}. Notified ${left.notifiedMembers} of ${left.remainingMembers} $members."
        if (left.notifiedMembers >= left.remainingMembers) count else "$count Others learn of your departure from a notified member or when they next reach this device while it keeps the departed copy."
    }
}

@Composable
fun GroupScreen(
    group: GroupState,
    nodeId: String,
    ready: Boolean,
    adding: Boolean,
    leaving: Boolean,
    onBack: () -> Unit,
    onAddDevice: (String, String) -> Unit,
    onLeave: () -> Unit,
    addedTicket: String? = null,
    scanDeviceButton: (@Composable (String) -> Unit)? = null,
    files: FilesState = FilesState(),
    onPickFile: (String) -> Unit = {},
    onRemoveFile: (String, String) -> Unit = { _, _ -> },
    onClearFileMessage: (String) -> Unit = {},
    onDownload: (String, String) -> Unit = { _, _ -> },
    onCancelDownload: (String, String) -> Unit = { _, _ -> },
    onSaveFile: (String, String) -> Unit = { _, _ -> },
    onOpenFile: (String, String) -> Unit = { _, _ -> },
    canOpenFiles: Boolean = false,
) {
    var code by remember(group.id) { mutableStateOf("") }
    LaunchedEffect(group.id, addedTicket) {
        if (addedTicket != null && code.trim() == addedTicket) code = ""
    }
    var confirmLeave by rememberSaveable(group.id) { mutableStateOf(false) }
    var menuOpen by remember(group.id) { mutableStateOf(false) }
    var removeEntryId by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Groups") }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(enabled = ready && !adding && !leaving, onClick = { menuOpen = true }) { Text("Group menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Leave group") }, onClick = {
                        menuOpen = false
                        confirmLeave = true
                    })
                }
            }
        }
        Text(group.name, style = MaterialTheme.typography.headlineMedium)
        Text("Every member can add devices to this group. Adding a device lets it see this group and its members.")
        Text("Members", style = MaterialTheme.typography.titleLarge)
        group.members.forEach { member ->
            key(member.id) {
            val online = member.online
            val color = if (online) Color(0xFF2E7D32) else Color(0xFFB3261E)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(if (member.id == nodeId) "${member.name} (this device)" else member.name)
                    Text(member.id, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (online) "Online" else "Offline", color = color)
            }
        }
        }
        scanDeviceButton?.invoke(group.id)
        OutlinedTextField(code, { code = it }, label = { Text("Paste code") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Button(enabled = ready && !adding && !leaving && code.isNotBlank(), onClick = { onAddDevice(group.id, code) }) { Text("Add pasted code") }
        if (adding) Text("Adding device…")
        if (leaving) Text("Leaving…")
        HorizontalDivider()
        Text("Files", style = MaterialTheme.typography.titleLarge)
        Text("Files added to this group are visible to all current and future members. AFM does not scan your files or upload unrelated files.")
        Button(enabled = ready && !files.busy, onClick = { onPickFile(group.id) }) { Text("Add file") }
        if (files.busy) Text(if (files.importing) "Importing file…" else "Updating group files…")
        files.message?.let { Text(it) }
        files.catalogProblem?.let { Text(it) }
        if (files.message != null || files.catalogProblem != null || files.exportMessage != null) {
            TextButton(onClick = { onClearFileMessage(group.id) }) { Text("Dismiss file message") }
        }
        files.exportMessage?.let { Text(it) }
        if (files.entries.isEmpty()) Text("No files in this group yet.")
        files.entries.forEach { entry ->
            key(entry.id) {
                Column {
                    Text(entry.name)
                    Text("${fileSize(entry.size)} · Added by ${entry.author} · ${if (entry.local) "On this device" else "Not on this device"}")
                    if (entry.local) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canOpenFiles) TextButton(onClick = { onOpenFile(group.id, entry.id) }) { Text("Open ${entry.name}") }
                            TextButton(onClick = { onSaveFile(group.id, entry.id) }) { Text("Save ${entry.name}") }
                        }
                    } else {
                        when (val transfer = files.transfers[entry.id]) {
                            FileTransfer.Queued -> Text("Queued for download")
                            is FileTransfer.Transferring -> Text("Downloading: ${fileSize(transfer.received)} / ${fileSize(transfer.total)}")
                            FileTransfer.Verifying -> Text("Verifying file…")
                            FileTransfer.Completed -> Text("Downloaded into AFM. Save separately to your location.")
                            FileTransfer.SourceUnavailable -> Text("Source unavailable: no online group member could provide this file. Retry when a member reconnects.")
                            FileTransfer.Cancelled -> Text("Download cancelled")
                            is FileTransfer.Failed -> Text(if (transfer.allProvidersCorrupt)
                                "Download failed: The file failed verification on every available member. Ask a member to re-add it or retry later."
                                else "Download failed: ${fileFailureMessage(transfer.reason)}")
                            null -> Unit
                        }
                        if (files.transfers[entry.id] is FileTransfer.Queued || files.transfers[entry.id] is FileTransfer.Transferring || files.transfers[entry.id] is FileTransfer.Verifying) {
                            TextButton(onClick = { onCancelDownload(group.id, entry.id) }) { Text("Cancel download") }
                        } else {
                            TextButton(enabled = ready, onClick = { onDownload(group.id, entry.id) }) {
                                Text(if (files.transfers[entry.id] == null) "Download ${entry.name}" else "Retry download")
                            }
                        }
                    }
                    TextButton(enabled = ready && !files.busy, onClick = { removeEntryId = entry.id }) { Text("Remove ${entry.name}") }
                }
            }
        }
    }
    files.entries.firstOrNull { it.id == removeEntryId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { removeEntryId = null },
            title = { Text("Remove ${entry.name}?") },
            text = { Text("This removes the group entry, not the file itself. Members who downloaded it keep their copies.") },
            confirmButton = { TextButton(onClick = { onRemoveFile(group.id, entry.id); removeEntryId = null }) { Text("Remove file") } },
            dismissButton = { TextButton(onClick = { removeEntryId = null }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave ${group.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Files only this device holds become unavailable to the group. Changes not yet synced to another member are lost.")
                if (group.members.size <= 1) Text("This device is the last member. Leaving deletes the group.")
                Text("Files already downloaded by other members remain on their devices. Bytes already stored here stay on this device for now.")
            }
        },
        confirmButton = { TextButton(enabled = ready && !leaving, onClick = { confirmLeave = false; onLeave() }) { Text("Leave group") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
    )
}
