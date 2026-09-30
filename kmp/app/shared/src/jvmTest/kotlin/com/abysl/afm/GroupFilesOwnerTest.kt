package com.abysl.afm

import blue.rae.spirit.sdk.FakeMeshFiles
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshNode
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus
import blue.rae.spirit.sdk.NodeStatus
import blue.rae.spirit.sdk.NodePong
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.LeftMesh
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
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

class GroupFilesOwnerTest {
    private val group = "d".repeat(64)
    private val self = "a".repeat(64)
    private val phone = "b".repeat(64)

    @Test
    fun importsListsDuplicatesAndRemovesOnlyConfirmedEntry() = runBlocking {
        val root = Files.createTempDirectory("afm-files-test").toFile()
        val backend = FakeMeshFiles(self)
        backend.admit(group, self, 0)
        val holdAvailability = AtomicBoolean(false)
        val checking = CompletableDeferred<Unit>()
        val resumeAvailability = CompletableDeferred<Unit>()
        val node = object : MeshNode, MeshFiles by backend {
            override suspend fun hasBlob(hash: String): Boolean {
                if (holdAvailability.get()) { checking.complete(Unit); resumeAvailability.await() }
                return backend.hasBlob(hash)
            }
            override suspend fun status() = NodeStatus(self, "Laptop", listOf(MeshStatus(group, "Family", listOf(
                MeshMember(self, "Laptop", 0), MeshMember(phone, "Phone", 0),
            ))), emptyList(), true)
            override suspend fun createMesh(name: String) = group
            override suspend fun pair() = PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
            override suspend fun add(meshId: String, ticket: String) = phone
            override suspend fun ping(device: String) = NodePong("Phone", 1)
            override suspend fun leaveMesh(meshId: String) = LeftMesh(meshId, "Family", 1, 0)
            override suspend fun shutdown() = Unit
        }
        val session = MeshSession({ node })
        val catalogs = GroupCatalogs(root, session)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val files = GroupFilesOwner(catalogs, session, scope)
        val run = launch { session.run() }
        try {
            waitFor("session") { session.files.value != null && session.state.value.groups.isNotEmpty() }
            var imports = 0
            assertTrue(catalogs.addFile(group, "bad/name", blue.rae.spirit.sdk.ImportedBlob("0".repeat(64), 1)) is AddFileResult.Rejected)
            files.addFile(group, "bad/name") { imports++; error("Should not import") }
            assertEquals(0, imports)
            assertTrue(files.state.value.getValue(group).message!!.contains("Rename"))
            files.addFile(group, "same.txt") { it.importFile(root.resolve("first").apply { writeText("one") }.path) }
            waitFor("first add") { files.state.value[group]?.entries?.size == 1 && files.state.value[group]?.busy == false }
            assertTrue(files.state.value.getValue(group).entries.single().local)
            val recreated = GroupFilesOwner(catalogs, session, scope)
            waitFor("owner recreated") { recreated.state.value[group]?.entries?.singleOrNull()?.local == true }
            holdAvailability.set(true)
            val staleRefresh = scope.launch { files.refresh(group) }
            checking.await()
            val remote = "0".repeat(64)
            catalogs.addFile(group, "same.txt", blue.rae.spirit.sdk.ImportedBlob(remote, 15))
            holdAvailability.set(false)
            resumeAvailability.complete(Unit)
            staleRefresh.join()
            waitFor("second add") { files.state.value.getValue(group).entries.size == 2 }
            assertEquals(listOf(true, false), files.state.value.getValue(group).entries.map { it.local })
            assertEquals(listOf("Laptop", "Laptop"), files.state.value.getValue(group).entries.map { it.author })
            files.remove(group, files.state.value.getValue(group).entries.last().id)
            waitFor("remove") { files.state.value.getValue(group).entries.size == 1 && files.state.value.getValue(group).busy == false }
            assertEquals("same.txt", files.state.value.getValue(group).entries.single().name)
            files.addFile(group, "bad.txt") { error("Cannot read") }
            waitFor("import failure") { files.state.value.getValue(group).message?.startsWith("Could not import") == true && files.state.value.getValue(group).busy == false }
            assertEquals(1, files.state.value.getValue(group).entries.size)
            val gate = CompletableDeferred<Unit>()
            files.addFile(group, "slow.txt") { backend -> gate.await(); backend.importFile(root.resolve("first").path) }
            waitFor("import busy") { files.state.value.getValue(group).busy }
            files.addFile(group, "ignored.txt") { imports++; error("Should not import") }
            assertEquals(0, imports)
            assertTrue(files.state.value.getValue(group).message!!.startsWith("Another file action"))
            gate.complete(Unit)
            waitFor("slow import") { files.state.value.getValue(group).entries.size == 2 && !files.state.value.getValue(group).busy }
            catalogs.markObtained(group, "f".repeat(64))
            waitFor("share warning") { files.state.value.getValue(group).catalogProblem?.contains("sharing") == true }
            files.clearMessage(group)
            waitFor("dismiss warning") { files.state.value.getValue(group).catalogProblem == null }
            val absentGroup = "e".repeat(64)
            files.addFile(absentGroup, "orphan.txt") { it.importFile(root.resolve("first").path) }
            waitFor("add failed") { files.state.value[absentGroup]?.message?.startsWith("Could not add") == true }
            run.cancelAndJoin()
            assertTrue(catalogs.remove(group, catalogs.entries(group).value.first().id) is RemoveResult.Failed)
        } finally { run.cancelAndJoin(); scope.cancel(); root.deleteRecursively() }
    }

    @Test
    fun duplicateNamesUseAuthorIdsWhenNicknamesCollide() = runBlocking {
        val root = Files.createTempDirectory("afm-authors-").toFile()
        val source = root.resolve("source").apply { writeText("remote bytes") }
        val remote = FakeMeshFiles(phone).apply { admit(group, phone, 0) }
        val blob = remote.importFile(source.path)
        CatalogStore(root.resolve("groups/$group"), group).own(phone, 0,
            CatalogBody.Add(EntryId.new(), "same.txt", blob.hash, blob.size), remote)
        val local = FakeMeshFiles(self).apply { admit(group, self, 0); admit(group, phone, 0) }
        val node = object : MeshNode, MeshFiles by local {
            override suspend fun status() = NodeStatus(self, "Laptop", listOf(MeshStatus(group, "Family", listOf(
                MeshMember(self, "Laptop", 0), MeshMember(phone, "Laptop", 0),
            ))), emptyList(), true)
            override suspend fun createMesh(name: String) = group
            override suspend fun pair() = PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
            override suspend fun add(meshId: String, ticket: String) = phone
            override suspend fun ping(device: String) = NodePong("Laptop", 1)
            override suspend fun leaveMesh(meshId: String) = LeftMesh(meshId, "Family", 1, 0)
            override suspend fun shutdown() = Unit
        }
        val session = MeshSession({ node })
        val catalogs = GroupCatalogs(root, session)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = GroupFilesOwner(catalogs, session, ownerScope)
        val run = launch { session.run() }
        try {
            waitFor("identity") { session.files.value != null && session.state.value.groups.isNotEmpty() }
            owner.addFile(group, "same.txt") { it.importFile(root.resolve("local").apply { writeText("local bytes") }.path) }
            waitFor("both authors") { owner.state.value[group]?.entries?.size == 2 }
            val entries = owner.state.value.getValue(group).entries
            assertEquals(setOf("Laptop (${self.take(12)})", "Laptop (${phone.take(12)})"), entries.map { it.author }.toSet())
            assertEquals(setOf(true, false), entries.map { it.local }.toSet())
            assertEquals(2, entries.map { it.id }.toSet().size)
        } finally { run.cancelAndJoin(); ownerScope.cancel(); root.deleteRecursively() }
    }

    private suspend fun waitFor(step: String, condition: () -> Boolean) {
        repeat(100) { if (condition()) return else delay(20) }
        assertTrue(condition(), step)
    }
}
