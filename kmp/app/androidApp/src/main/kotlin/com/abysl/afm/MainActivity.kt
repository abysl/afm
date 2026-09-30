package com.abysl.afm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
            val fileState by model.files.state.collectAsState()
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> model.selectedFile(uri) }
            val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> model.selectedSave(uri) }
            App(
                store = model.store,
                files = fileState,
                onPickFile = { group -> model.pickFile(group); filePicker.launch(arrayOf("*/*")) },
                onRemoveFile = model.files::remove,
                onClearFileMessage = model.files::clearMessage,
                onDownload = model.files::download,
                onCancelDownload = model.files::cancel,
                onSaveFile = { group, entry -> model.selectSave(group, entry)?.let(savePicker::launch) },
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
