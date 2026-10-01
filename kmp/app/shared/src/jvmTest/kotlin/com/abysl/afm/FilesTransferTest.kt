package com.abysl.afm

import blue.rae.spirit.sdk.FakeMeshFiles
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshNode
import blue.rae.spirit.sdk.MeshNodeException
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus
import blue.rae.spirit.sdk.NodePeer
import blue.rae.spirit.sdk.NodePong
import blue.rae.spirit.sdk.NodeStatus
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.LeftMesh
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesTransferTest {
    private val group = "d".repeat(64)
    private val self = "a".repeat(64)
    private val phone = "b".repeat(64)
    private val tablet = "c".repeat(64)
    private val outsider = "e".repeat(64)
    private val otherGroup = "f".repeat(64)

    @Test
    fun queuesReportsProgressFallsBackCompletesAndExports() = runBlocking {
        fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = MeshFailure.Unavailable
            test.node.gate = CompletableDeferred()
            test.node.progressGate = CompletableDeferred()
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Queued && test.node.attempts.contains(tablet) }
            assertEquals(listOf(phone, tablet), test.node.attempts)
            test.node.gate?.complete(Unit)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Transferring(test.entrySize / 2, test.entrySize) }
            test.node.progressGate?.complete(Unit)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertEquals(test.entrySize, test.node.expectedSizes.last())
            assertTrue(test.owner.state.value.getValue(group).entries.single().local)
            assertEquals(setOf(test.hash), test.node.backend.sharesFor(group))
            val destination = test.root.resolve("export.txt")
            test.owner.export(group, test.id) { backend, entry -> backend.exportFile(entry.hash, destination.path) }
            waitFor { destination.exists() }
            assertEquals("verified bytes", destination.readText())
            assertEquals("Saved to your chosen location.", test.owner.state.value.getValue(group).exportMessage)
            test.owner.export(group, test.id) { _, _ -> throw MeshNodeException(MeshFailure.Destination) }
            waitFor { test.owner.state.value.getValue(group).exportMessage?.startsWith("Save failed") == true }
            assertTrue(test.owner.state.value.getValue(group).exportMessage!!.contains("chosen location"))
            val corruptSave = test.owner.export(group, test.id) { _, _ -> throw MeshNodeException(MeshFailure.Corrupt) }
            assertTrue(corruptSave is ExportResult.Failed)
            assertTrue(test.owner.state.value.getValue(group).exportMessage!!.contains("damaged or missing"))
        }
    }

    @Test
    fun corruptAuthorFallsBackAndRetrySkipsIt() = runBlocking {
        fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = MeshFailure.Corrupt
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertEquals(listOf(phone, tablet), test.node.attempts)
        }
        fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = MeshFailure.Corrupt
            test.node.failures[tablet] = MeshFailure.Timeout
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Failed(MeshFailure.Timeout) }
            assertEquals(listOf(phone, tablet), test.node.attempts)
            test.node.failures.remove(tablet)
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertEquals(listOf(phone, tablet, tablet), test.node.attempts)
        }
    }

    @Test
    fun everyCorruptProviderReportsVerificationFailure() = runBlocking {
        fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = MeshFailure.Corrupt
            test.node.failures[tablet] = MeshFailure.Corrupt
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] ==
                FileTransfer.Failed(MeshFailure.Corrupt, allProvidersCorrupt = true) }
            assertEquals(listOf(phone, tablet), test.node.attempts)
        }
    }

    @Test
    fun timeoutAndInterruptedAlsoFallBack() = runBlocking {
        for (reason in listOf(MeshFailure.Timeout, MeshFailure.Interrupted)) fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = reason
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertEquals(listOf(phone, tablet), test.node.attempts)
        }
    }

    @Test
    fun recreatedOwnerDiscoversTheVerifiedLocalCopy() = runBlocking {
        fixture(setOf(phone)) { test ->
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            val recreated = GroupFilesOwner(test.catalogs, test.mesh, test.scope)
            waitFor { recreated.state.value[group]?.entries?.singleOrNull()?.local == true }
            assertEquals(setOf(test.hash), test.node.backend.sharesFor(group))
        }
    }

    @Test
    fun saveChecksEntryAndLocalCopyBeforeOpeningDestination() = runBlocking {
        fixture(emptySet()) { test ->
            var destinations = 0
            val absent = test.owner.export(group, "missing-entry") { _, _ -> destinations++ }
            assertTrue(absent is ExportResult.Failed)
            assertTrue(test.owner.state.value.getValue(group).exportMessage!!.contains("no longer available"))
            val missingCopy = test.owner.export(group, test.id) { _, _ -> destinations++ }
            assertTrue(missingCopy is ExportResult.Failed)
            assertTrue(test.owner.state.value.getValue(group).exportMessage!!.contains("damaged or missing"))
            assertEquals(0, destinations)
            test.sessionJob.cancelAndJoin()
            val closed = test.owner.export(group, test.id) { _, _ -> destinations++ }
            assertTrue(closed is ExportResult.Failed)
            assertTrue((closed as ExportResult.Failed).message.contains("node stopped"))
            waitFor { group !in test.owner.state.value }
            test.owner.reportCleanup(group, DocumentCleanup.Truncated)
            assertTrue(group !in test.owner.state.value)
            assertEquals(0, destinations)
        }
    }

    @Test
    fun concurrentAddsReserveBusyBeforeImporting() = runBlocking {
        fixture(emptySet()) { test ->
            val start = CompletableDeferred<Unit>()
            val imports = java.util.concurrent.atomic.AtomicInteger()
            val first = launch(Dispatchers.Default) {
                start.await()
                test.owner.addFile(group, "first.txt") { files ->
                    imports.incrementAndGet()
                    files.importFile(test.root.resolve("bytes").path)
                }
            }
            val second = launch(Dispatchers.Default) {
                start.await()
                test.owner.addFile(group, "second.txt") { files ->
                    imports.incrementAndGet()
                    files.importFile(test.root.resolve("bytes").path)
                }
            }
            start.complete(Unit)
            first.join()
            second.join()
            waitFor { test.owner.state.value[group]?.entries?.size == 2 && test.owner.state.value[group]?.busy == false }
            assertEquals(1, imports.get())
        }
    }

    @Test
    fun providersStayInsideTheSelectedGroup()
 = runBlocking {
        fixture(setOf(phone, outsider)) { test ->
            test.node.failures[phone] = MeshFailure.Unavailable
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.SourceUnavailable }
            assertEquals(listOf(phone), test.node.attempts)
            assertTrue(test.node.backend.sharesFor(otherGroup).isEmpty())
        }
    }

    @Test
    fun removingAnEntryCancelsItsTransferWithoutAFalseShareWarning() = runBlocking {
        fixture(setOf(phone)) { test ->
            test.node.gate = CompletableDeferred()
            test.owner.download(group, test.id)
            waitFor { test.node.attempts.isNotEmpty() }
            assertEquals(RemoveResult.Removed, test.catalogs.remove(group, test.catalogs.entries(group).value.single().id))
            waitFor { test.owner.state.value.getValue(group).entries.isEmpty() &&
                test.owner.state.value.getValue(group).transfers.isEmpty() }
            test.node.gate?.complete(Unit)
            assertEquals(null, test.catalogs.errors.value[group]?.takeIf { it.kind == CatalogErrorKind.Share })
            assertTrue(test.node.backend.sharesFor(group).isEmpty())
        }
    }

    @Test
    fun cancellingDuringFallbackLeavesNoSharesOrCompletedBlob() = runBlocking {
        fixture(setOf(phone, tablet)) { test ->
            test.node.failures[phone] = MeshFailure.Unavailable
            test.node.gate = CompletableDeferred()
            test.owner.download(group, test.id)
            waitFor { test.node.attempts.size == 2 }
            test.owner.cancel(group, test.id)
            test.node.gate?.complete(Unit)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Cancelled }
            assertEquals(listOf(phone, tablet), test.node.attempts)
            assertTrue(test.node.backend.sharesFor(group).isEmpty())
            assertEquals(false, test.node.backend.hasBlob(test.hash))
        }
    }

    @Test
    fun cancellationAfterVerifiedFetchStillMarksOnlyThisGroupObtained() = runBlocking {
        fixture(setOf(phone)) { test ->
            test.node.onFetched = { test.owner.cancel(group, test.id) }
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertEquals(setOf(test.hash), test.node.backend.sharesFor(group))
            assertTrue(test.node.backend.sharesFor(otherGroup).isEmpty())
        }
    }

    @Test
    fun offlineAndCorruptAndCancelRetryKeepStateInOwner() = runBlocking {
        fixture(emptySet()) { test ->
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.SourceUnavailable }
            assertTrue(test.node.attempts.isEmpty())
        }
        fixture(setOf(phone)) { test ->
            test.node.failures[phone] = MeshFailure.Corrupt
            test.owner.download(group, test.id)
            waitFor { test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Failed(MeshFailure.Corrupt, allProvidersCorrupt = true) }
            assertTrue(test.owner.state.value.getValue(group).entries.single().local.not())
            test.owner.download(group, test.id)
            waitFor { test.node.attempts.size == 2 && test.owner.state.value.getValue(group).transfers[test.id] == FileTransfer.Failed(MeshFailure.Corrupt, allProvidersCorrupt = true) }
            test.node.failures.clear()
            test.node.gate = CompletableDeferred()
            test.owner.download(group, test.id)
            waitFor { test.node.attempts.size == 3 }
            val retainedState = test.owner.state
            test.owner.cancel(group, test.id)
            waitFor { retainedState.value.getValue(group).transfers[test.id] == FileTransfer.Cancelled }
            test.node.gate?.complete(Unit)
            test.owner.download(group, test.id)
            waitFor { retainedState.value.getValue(group).transfers[test.id] == FileTransfer.Completed }
            assertTrue(test.owner.state.value.getValue(group).entries.single().local)
        }
    }

    private suspend fun fixture(online: Set<String>, test: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("afm-transfer-").toFile()
        val content = root.resolve("bytes").apply { writeText("verified bytes") }
        val remote = FakeMeshFiles(phone).apply { admit(group, phone, 0) }
        val imported = remote.importFile(content.path)
        val id = EntryId.new()
        CatalogStore(root.resolve("groups/$group"), group).own(phone, 0,
            CatalogBody.Add(id, "same.txt", imported.hash, imported.size), remote)
        val backend = FakeMeshFiles(self).apply { admit(group, self, 0); admit(group, phone, 0); admit(otherGroup, self, 0); admit(otherGroup, outsider, 0) }
        val node = TransferNode(backend, content.path, online)
        val mesh = MeshSession({ node })
        val catalogs = GroupCatalogs(root, mesh)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = GroupFilesOwner(catalogs, mesh, scope)
        val sessionJob = scope.launch { mesh.run() }
        val catalogJob = scope.launch { catalogs.run() }
        try {
            waitFor { mesh.files.value != null && owner.state.value[group]?.entries?.size == 1 }
            test(Fixture(root, CatalogEntryId(phone, 0, id).rowKey(), imported.hash, imported.size, node, owner, catalogs, sessionJob, mesh, scope))
        } finally {
            sessionJob.cancelAndJoin()
            catalogJob.cancelAndJoin()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private data class Fixture(val root: java.io.File, val id: String, val hash: String, val entrySize: Long,
        val node: TransferNode, val owner: GroupFilesOwner, val catalogs: GroupCatalogs,
        val sessionJob: kotlinx.coroutines.Job, val mesh: MeshSession, val scope: CoroutineScope)

    private inner class TransferNode(
        val backend: FakeMeshFiles,
        private val content: String,
        private val online: Set<String>,
    ) : MeshNode, MeshFiles by backend {
        val failures = mutableMapOf<String, MeshFailure>()
        val attempts = mutableListOf<String>()
        val expectedSizes = mutableListOf<Long?>()
        var gate: CompletableDeferred<Unit>? = null
        var progressGate: CompletableDeferred<Unit>? = null
        var onFetched: (() -> Unit)? = null

        override suspend fun status() = NodeStatus(self, "Desktop", listOf(
            MeshStatus(group, "Family", listOf(MeshMember(self, "Desktop", 0), MeshMember(phone, "Phone", 0), MeshMember(tablet, "Tablet", 0))),
            MeshStatus(otherGroup, "Work", listOf(MeshMember(self, "Desktop", 0), MeshMember(outsider, "Other", 0))),
        ), online.map { NodePeer(it, it, true, 0, null) }, true)
        override suspend fun fetch(meshId: String, provider: String, hash: String, expectedSize: Long?,
            onQueued: () -> Unit, onProgress: (Long, Long) -> Unit): Long {
            attempts += provider
            expectedSizes += expectedSize
            failures[provider]?.let { throw MeshNodeException(it) }
            onQueued()
            gate?.await()
            onProgress(expectedSize!! / 2, expectedSize)
            progressGate?.await()
            val imported = backend.importFile(content)
            assertEquals(hash, imported.hash)
            onProgress(expectedSize, expectedSize)
            onFetched?.invoke()
            return expectedSize
        }
        override suspend fun createMesh(name: String) = group
        override suspend fun pair() = PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
        override suspend fun add(meshId: String, ticket: String) = "Phone"
        override suspend fun ping(device: String) = NodePong(device, 1)
        override suspend fun leaveMesh(meshId: String) = LeftMesh(group, "Family", 2, 0)
        override suspend fun shutdown() = Unit
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(150) { if (condition()) return else delay(20) }
        assertTrue(condition())
    }
}
