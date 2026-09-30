package com.abysl.afm

import android.app.Application
import android.os.Build
import android.net.Uri
import blue.rae.spirit.sdk.exportToStream
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import blue.rae.spirit.sdk.importStream
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import blue.rae.spirit.sdk.AndroidNodeContext
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.SpiritNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    fun selectSave(meshId: String, entryId: String): String? {
        val entry = catalogs.entries(meshId).value.firstOrNull { it.id.rowKey() == entryId } ?: return null
        picker.selectSave(meshId, entryId)
        return entry.name
    }

    fun selectedSave(uri: Uri?) {
        val selection = picker.consumeSave(uri)
        if (selection == null) {
            if (uri != null) viewModelScope.launch {
                val size = saveDestinationSize(uri)
                val outcome = cleanupSaveDestination(uri, saveCleanupDecision(false, false, size))
                mesh.reportError(when (outcome) {
                    DocumentCleanup.Deleted -> "Save request expired. Choose the file again."
                    DocumentCleanup.Untouched -> "Save request expired. Your existing file was not changed."
                    DocumentCleanup.Truncated -> "Save request expired. An empty document may remain; delete it manually."
                    DocumentCleanup.Failed -> "Save request expired. The incomplete document may remain; delete it manually."
                })
            }
            return
        }
        val (group, entryId, destination) = selection
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            var opened = false
            var written = false
            var originalSize: Long? = null
            try {
                originalSize = saveDestinationSize(destination)
                files.export(group, entryId) { backend, entry ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        backend.exportToStream(entry.hash) {
                            val stream = resolver.openOutputStream(destination, "wt") ?: error("Cannot open destination")
                            opened = true
                            stream
                        }
                        written = true
                    }
                }
            } finally {
                val decision = saveCleanupDecision(written, opened, originalSize)
                if (decision != SaveCleanupDecision.KeepWritten) {
                    val outcome = cleanupSaveDestination(destination, decision)
                    files.reportCleanup(group, outcome)
                }
            }
        }
    }

    private suspend fun saveDestinationSize(uri: Uri): Long? = withContext(NonCancellable + Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()
    }

    private suspend fun cleanupSaveDestination(uri: Uri, decision: SaveCleanupDecision): DocumentCleanup {
        if (decision == SaveCleanupDecision.KeepExisting) return DocumentCleanup.Untouched
        val resolver = getApplication<Application>().contentResolver
        return withContext(NonCancellable + Dispatchers.IO) {
            cleanupPartialDocument(
                { DocumentsContract.deleteDocument(resolver, uri) },
                { resolver.openOutputStream(uri, "wt")?.use { true } ?: false },
            )
        }
    }

    fun refreshTicket() {
        viewModelScope.launch { mesh.refreshTicket() }
    }
}
