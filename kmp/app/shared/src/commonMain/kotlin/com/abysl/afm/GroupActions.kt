package com.abysl.afm

import blue.rae.spirit.sdk.AddDeviceResult
import blue.rae.spirit.sdk.LeftMesh
import blue.rae.spirit.sdk.MeshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class GroupActionState(
    val adding: Boolean = false,
    val lastTicket: String? = null,
    val addedTicket: String? = null,
    val leaving: Boolean = false,
    val departure: LeftMesh? = null,
)

class GroupActions(private val mesh: MeshSession, private val ownerScope: CoroutineScope) {
    private val mutableState = MutableStateFlow(GroupActionState())
    val state: StateFlow<GroupActionState> = mutableState.asStateFlow()

    fun addDevice(groupId: String, ticket: String) {
        when {
            mesh.state.value.nodeId.isEmpty() -> mesh.reportError("Device is not ready. Reopen AFM and try again.")
            mutableState.value.adding || mutableState.value.leaving || mesh.state.value.busy -> mesh.reportError("Another action is in progress. Try again when it finishes.")
            else -> {
                mutableState.value = mutableState.value.copy(adding = true, lastTicket = ticket.trim(), addedTicket = null)
                ownerScope.launch {
                    try {
                        if (mesh.addDevice(groupId, ticket) is AddDeviceResult.Added) {
                            mutableState.value = mutableState.value.copy(addedTicket = ticket.trim())
                        }
                    } finally {
                        mutableState.value = mutableState.value.copy(adding = false)
                    }
                }
            }
        }
    }

    fun leaveGroup(groupId: String) {
        when {
            mesh.state.value.nodeId.isEmpty() -> mesh.reportError("Device is not ready. Reopen AFM and try again.")
            mutableState.value.leaving || mutableState.value.adding || mesh.state.value.busy -> mesh.reportError("Another action is in progress. Try again when it finishes.")
            else -> {
                mutableState.value = mutableState.value.copy(leaving = true)
                ownerScope.launch {
                    try {
                        val left = mesh.leaveGroup(groupId)
                        if (left != null) mutableState.value = mutableState.value.copy(departure = left)
                    } finally {
                        mutableState.value = mutableState.value.copy(leaving = false)
                    }
                }
            }
        }
    }

    fun clearMessages() {
        mutableState.value = mutableState.value.copy(departure = null, lastTicket = null)
        mesh.clearMessages()
    }

    fun addScannedTicket(groupId: String, ticket: String) {
        ownerScope.launch {
            mesh.state.first { !it.loading && !it.busy }
            addDevice(groupId, ticket)
        }
    }
}
