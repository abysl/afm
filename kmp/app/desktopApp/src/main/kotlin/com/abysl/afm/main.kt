package com.abysl.afm

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.SpiritNode
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val home = System.getProperty("user.home")
    val store = remember { openBlobStore("$home/.spirit2/afm") }
    val mesh = remember {
        MeshSession(
            nodeFactory = { SpiritNode.open(afmNodeDirectory(File(home, ".spirit2")).path, "AFM desktop", storeDir = afmStoreDirectory(File(home, ".spirit2")).path) },
        )
    }
    val scope = rememberCoroutineScope()
    val catalogs = remember(mesh) { GroupCatalogs(File(home, ".spirit2/afm-groups"), mesh) }
    val actions = remember(mesh) { GroupActions(mesh, scope) }
    val files = remember(mesh) { GroupFilesOwner(catalogs, mesh, scope) }
    val openCopies = remember { DesktopOpenCopies() }
    val canOpenFiles = remember { desktopOpenAvailable(System.getProperty("os.name")) }
    val nodeJob = remember {
        scope.launch {
            val catalogJob = launch { catalogs.run() }
            try {
                runMeshUntilOwnerCancellation(
                    mesh::run,
                    onOwnerTeardown = {
                        withContext(Dispatchers.IO) { store?.close() }
                    },
                )
            } finally {
                catalogJob.cancelAndJoin()
            }
        }
    }
    var closing by remember { mutableStateOf(false) }
    val state by mesh.state.collectAsState()
    val actionState by actions.state.collectAsState()
    val fileState by files.state.collectAsState()
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch {
                    nodeJob.cancelAndJoin()
                    openCopies.clear()
                    exitApplication()
                }
            }
        },
        title = "AFM",
    ) {
        App(
            store = store,
            files = fileState,
            onPickFile = { group ->
                val frame = Frame()
                val dialog = FileDialog(frame, "Add file", FileDialog.LOAD)
                try {
                    dialog.isVisible = true
                    dialog.file?.let { name ->
                        val path = File(dialog.directory, name).absolutePath
                        files.addFile(group, name) { backend -> backend.importFile(path) }
                    }
                } finally { dialog.dispose(); frame.dispose() }
            },
            onRemoveFile = files::remove,
            onClearFileMessage = files::clearMessage,
            onDownload = files::download,
            onCancelDownload = files::cancel,
            canOpenFiles = canOpenFiles,
            onOpenFile = { group, entryId ->
                if (canOpenFiles) scope.launch {
                    files.export(group, entryId, action = "Open", successMessage = "Opened in another app.") { backend, entry ->
                        openCopies.open(backend, entry)
                    }
                }
            },
            onSaveFile = { group, entryId ->
                val entry = catalogs.entries(group).value.firstOrNull { it.id.rowKey() == entryId }
                if (entry != null) {
                    val frame = Frame()
                    val dialog = FileDialog(frame, "Save file", FileDialog.SAVE).apply { file = entry.name }
                    try {
                        dialog.isVisible = true
                        dialog.file?.let { name ->
                            val path = File(dialog.directory, name).absolutePath
                            scope.launch { files.export(group, entryId) { backend, item -> backend.exportFile(item.hash, path) } }
                        }
                    } finally { dialog.dispose(); frame.dispose() }
                }
            },
            mesh = state.copy(busy = state.busy || closing),
            onCreateGroup = { name -> scope.launch { mesh.createGroup(name) } },
            onClearMessages = actions::clearMessages,
            actions = actionState,
            onAddDevice = actions::addDevice,
            onLeaveGroup = actions::leaveGroup,
            onRefreshTicket = { scope.launch { mesh.refreshTicket() } },
        )
    }
}