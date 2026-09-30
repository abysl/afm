package com.abysl.afm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.LeftMesh
import blue.rae.spirit.sdk.MeshState

internal fun failureMessage(failure: MeshFailure?, fallback: String?, ownTicket: Boolean = false): String? = when (failure) {
    MeshFailure.TicketRejected -> if (ownTicket) "This is this device's own code. Scan or paste the code shown on the device you want to add." else "Ticket rejected. Ask for a fresh QR code."
    MeshFailure.Unavailable -> "Device unreachable. Check its connection and try again."
    MeshFailure.MeshLimit -> "This device has reached the 64-group limit. Leave a group before creating or joining another group."
    MeshFailure.NodeBusy -> "AFM's device data is in use by another AFM window or process. Close it and restart AFM."
    MeshFailure.Invalid -> "Invalid input. Use a valid ticket or a group name of 1–128 UTF-8 bytes with no control characters."
    MeshFailure.NotMember -> "This device is no longer in that group."
    MeshFailure.NodeClosed -> "AFM's node stopped. Restart AFM."
    MeshFailure.Node -> fallback ?: "Could not contact the device. Try again."
    null -> fallback
    else -> fallback ?: "The group action failed. Try again."
}

@Composable
fun GroupsPanel(
    state: MeshState,
    onCreateGroup: (String) -> Unit,
    onRefreshTicket: () -> Unit,
    onClearMessages: () -> Unit,
    onOpenGroup: (String) -> Unit,
    lastTicket: String? = null,
    departure: LeftMesh? = null,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var joining by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val ready = !state.loading && !state.busy && state.nodeId.isNotEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Groups", style = MaterialTheme.typography.headlineMedium)
        if (state.loading) Text("Opening this device…")
        departure?.let { Text(departureMessage(it)) }
        val withoutDeparture = when {
            departure == null -> state.notice
            state.notice?.startsWith("Left ${departure.meshName}") == true -> null
            else -> state.notice?.substringBefore("; Left ${departure.meshName}")
        }
        val notice = withoutDeparture?.split("; ")?.filterNot { state.joinedGroupIds.isNotEmpty() && it.startsWith("Joined ") }?.joinToString("; ")?.ifEmpty { null }
        notice?.let { Text(it) }
        if (departure != null || state.notice != null) {
            TextButton(onClick = onClearMessages) { Text("Dismiss notice") }
        }
        state.joinedGroupIds.forEach { id ->
            state.groups.firstOrNull { it.id == id }?.let { Text("Joined ${it.name}") }
        }
        failureMessage(state.failure, state.error, lastTicket?.trim() == state.invitation?.ticket && lastTicket != null)?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onClearMessages) { Text("Dismiss error") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready, onClick = { creating = true }) { Text("New group") }
            OutlinedButton(enabled = ready, onClick = { joining = true }) { Text("Join a group") }
        }
        if (!state.loading && state.groups.isEmpty()) {
            Text("No groups yet. Create a new group, or show your QR to a member of a group you want to join.")
        }
        state.groups.forEach { group ->
            key(group.id) {
                val online = group.members.count { it.online }
                OutlinedButton(onClick = { onOpenGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${group.name} · ${group.members.size} ${if (group.members.size == 1) "member" else "members"} · $online online")
                }
            }
        }
        Text("Being in two groups reveals this device to members of both groups.", style = MaterialTheme.typography.bodySmall)
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New group") },
        text = {
            Column {
                OutlinedTextField(name, { entered ->
                    val filtered = entered.filterNot { it.code < 32 || it.code in 127..159 }
                    if (filtered.encodeToByteArray().size <= 128) name = filtered
                }, label = { Text("Group name") }, singleLine = true)
                Text("${name.encodeToByteArray().size}/128 UTF-8 bytes maximum")
                Text("Any member you add can see the group and add other devices.")
            }
        },
        confirmButton = {
            TextButton(enabled = ready && name.isNotBlank(), onClick = {
                onCreateGroup(name.trim())
                name = ""
                creating = false
            }) { Text("Create group") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
    )
    if (joining) AlertDialog(
        onDismissRequest = { joining = false },
        title = { Text("Join a group") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Show this QR to a member of the group. Whoever holds it can add this device to one group they choose. This ticket is single-use; show it only to trusted devices.")
                Text("Identity: ${state.name}")
                Text("Node ID: ${state.nodeId}")
                val invitation = state.invitation
                if (invitation == null) Text("No QR available. Refresh to request a new ticket.")
                else if (state.invitationSecondsRemaining <= 0) Text("Join QR expired. Refresh it before joining a group.")
                else if (!isValidQrMatrix(invitation.width, invitation.modules)) Text("Join QR data is unavailable. Refresh it to create a new code.")
                else {
                    ExactQrMatrix(invitation)
                    Text("Ticket expires in ${state.invitationSecondsRemaining} seconds.")
                    OutlinedTextField(invitation.ticket, {}, readOnly = true, label = { Text("Ticket text") }, modifier = Modifier.fillMaxWidth())
                }
                Button(enabled = ready, onClick = onRefreshTicket) { Text("Refresh QR") }
            }
        },
        confirmButton = { TextButton(onClick = { joining = false }) { Text("Close") } },
    )
}
