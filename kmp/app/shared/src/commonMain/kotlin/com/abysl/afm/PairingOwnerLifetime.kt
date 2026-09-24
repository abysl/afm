package com.abysl.afm

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

suspend fun runPairingUntilOwnerCancellation(
    runPairing: suspend () -> Unit,
    onOwnerTeardown: suspend () -> Unit,
) {
    try {
        runPairing()
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { onOwnerTeardown() }
    }
}
