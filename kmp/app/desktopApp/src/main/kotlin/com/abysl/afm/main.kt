package com.abysl.afm

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import blue.rae.spirit.sdk.PairingSession
import blue.rae.spirit.sdk.SpiritNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val home = System.getProperty("user.home")
    val store = remember { openBlobStore("$home/.spirit2/afm") }
    val pairing = remember {
        PairingSession(
            nodeFactory = { SpiritNode.open("$home/.spirit2/afm-node", "AFM desktop") },
            meshName = "AFM mesh",
        )
    }
    val scope = rememberCoroutineScope()
    val nodeJob = remember {
        scope.launch {
            runPairingUntilOwnerCancellation(
                pairing::run,
                onOwnerTeardown = {
                    withContext(Dispatchers.IO) { store?.close() }
                },
            )
        }
    }
    var closing by remember { mutableStateOf(false) }
    val state by pairing.state.collectAsState()
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
            pairing = state.copy(busy = state.busy || closing),
            onRefreshTicket = { scope.launch { pairing.refreshTicket() } },
            onLeaveMesh = { scope.launch { pairing.leaveMesh() } },
        )
    }
}