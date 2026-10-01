package com.abysl.afm

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

suspend fun runMeshUntilOwnerCancellation(
    runMesh: suspend () -> Unit,
    onOwnerTeardown: suspend () -> Unit,
) {
    try {
        runMesh()
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { onOwnerTeardown() }
    }
}
