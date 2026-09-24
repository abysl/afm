package com.abysl.afm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val model = ViewModelProvider(this)[AfmViewModel::class.java]
        setContent {
            val pairing by model.pairing.state.collectAsState()
            val pendingScan by model.scanner.pendingStep.collectAsState()
            val requestScan = rememberPairingScannerLauncher(model.scanner)
            App(
                store = model.store,
                pairing = pairing,
                onRefreshTicket = model::refreshTicket,
                pairDeviceButton = {
                    AndroidPairDeviceButton(
                        enabled = !pairing.loading && !pairing.busy && pendingScan == null,
                        onClick = requestScan,
                    )
                },
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}