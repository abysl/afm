package com.abysl.afm

import android.app.Application
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import blue.rae.spirit.sdk.importStream
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
                afmNodeDirectory(application.noBackupFilesDir).path,
                "Android ${Build.MODEL.filter { it.isLetterOrDigit() || it == ' ' }.take(24)}",
                storeDir = afmStoreDirectory(application.noBackupFilesDir).path,
            )
        },
    )
    val catalogs = GroupCatalogs(application.noBackupFilesDir, mesh)
    val files = GroupFilesOwner(catalogs, mesh, viewModelScope)
    private val picker = GroupFilePicker(savedStateHandle)
    val actions = GroupActions(mesh, viewModelScope)
    val scanner = PairingScannerModel(
        savedState = savedStateHandle,
        canStart = { mesh.state.value.let { !it.loading && !it.busy && it.nodeId.isNotEmpty() } && !actions.state.value.adding && !actions.state.value.leaving },
        onTicket = actions::addScannedTicket,
        onError = mesh::reportError,
    )

    init {
        viewModelScope.launch { catalogs.run() }
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

    fun pickFile(meshId: String) { picker.select(meshId) }

    fun selectedFile(uri: Uri?) {
        val (group, selected) = picker.consume(uri) ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching {
                resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Selected file"
            files.addFile(group, name) { backend ->
                withContext(Dispatchers.IO) {
                    backend.importStream { resolver.openInputStream(selected) ?: error("Cannot open selected file") }
                }
            }
        }
    }

    fun refreshTicket() {
        viewModelScope.launch { mesh.refreshTicket() }
    }
}
