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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val home = System.getProperty("user.home")
    val store = remember { openBlobStore("$home/.spirit2/afm") }
    val mesh = remember {
        MeshSession(
            nodeFactory = { SpiritNode.open("$home/.spirit2/afm-node", "AFM desktop") },
        )
    }
    val scope = rememberCoroutineScope()
    val actions = remember(mesh) { GroupActions(mesh, scope) }
    val nodeJob = remember {
        scope.launch {
            runMeshUntilOwnerCancellation(
                mesh::run,
                onOwnerTeardown = {
                    withContext(Dispatchers.IO) { store?.close() }
                },
            )
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
            onClearMessages = mesh::clearMessages,
            actions = actionState,
            onAddDevice = actions::addDevice,
            onRefreshTicket = { scope.launch { mesh.refreshTicket() } },
        )
    }
}