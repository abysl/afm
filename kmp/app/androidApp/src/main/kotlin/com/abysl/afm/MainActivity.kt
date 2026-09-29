package com.abysl.afm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[AfmViewModel::class.java]
        setContent {
            val mesh by model.mesh.state.collectAsState()
            val pendingScan by model.scanner.pendingStep.collectAsState()
            val actions by model.actions.state.collectAsState()
            val requestScan = rememberPairingScannerLauncher(model.scanner)
            App(
                store = model.store,
                mesh = mesh,
                onCreateGroup = model::createGroup,
                onRefreshTicket = model::refreshTicket,
                onClearMessages = model::clearMessages,
                actions = actions,
                onAddDevice = model::addDevice,
                onLeaveGroup = model::leaveGroup,
                groupBackHandler = { onBack -> BackHandler(onBack = onBack) },
                scanDeviceButton = { id ->
                    AndroidPairDeviceButton(
                        enabled = !mesh.loading && !mesh.busy && mesh.nodeId.isNotEmpty() && !actions.adding && !actions.leaving && pendingScan == null,
                        onClick = { requestScan(id) },
                    )
                },
            )
        }
    }
}
