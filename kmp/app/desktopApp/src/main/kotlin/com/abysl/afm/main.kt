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
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch {
                    nodeJob.cancelAndJoin()
                    exitApplication()
                }
            }
        },
        title = "AFM",
    ) {
        App(
            store = store,
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