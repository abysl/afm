package com.abysl.afm

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import blue.rae.spirit.sdk.AndroidNodeContext
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.SpiritNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AfmViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val store = openBlobStore(application.filesDir.resolve("afm").path)
    val mesh = MeshSession(
        nodeFactory = {
            withContext(Dispatchers.IO) { AndroidNodeContext.initialize(application) }
            SpiritNode.open(
                application.noBackupFilesDir.resolve("afm-node").path,
                "Android ${Build.MODEL.filter { it.isLetterOrDigit() || it == ' ' }.take(24)}",
            )
        },
    )
    val actions = GroupActions(mesh, viewModelScope)
    val scanner = PairingScannerModel(
        savedState = savedStateHandle,
        canStart = { mesh.state.value.let { !it.loading && !it.busy && it.nodeId.isNotEmpty() } && !actions.state.value.adding && !actions.state.value.leaving },
        onTicket = actions::addScannedTicket,
        onError = mesh::reportError,
    )

    init {
        viewModelScope.launch {
            runMeshUntilOwnerCancellation(
                mesh::run,
                onOwnerTeardown = {
                    withContext(Dispatchers.IO) { store?.close() }
                },
            )
        }
    }

    fun createGroup(name: String) {
        viewModelScope.launch { mesh.createGroup(name) }
    }

    fun clearMessages() = actions.clearMessages()

    fun addDevice(meshId: String, ticket: String) = actions.addDevice(meshId, ticket)

    fun leaveGroup(meshId: String) = actions.leaveGroup(meshId)

    fun refreshTicket() {
        viewModelScope.launch { mesh.refreshTicket() }
    }
}
