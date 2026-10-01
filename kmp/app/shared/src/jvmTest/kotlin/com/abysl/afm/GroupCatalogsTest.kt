package com.abysl.afm

import blue.rae.spirit.sdk.FakeMeshFiles
import blue.rae.spirit.sdk.ImportedBlob
import blue.rae.spirit.sdk.LeftMesh
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshNode
import blue.rae.spirit.sdk.MeshNodeException
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus
import blue.rae.spirit.sdk.NodePeer
import blue.rae.spirit.sdk.NodePong
import blue.rae.spirit.sdk.NodeStatus
import blue.rae.spirit.sdk.PairingInvitation
import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroupCatalogsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val mesh = "mesh1_${"A".repeat(43)}"
    private val other = "mesh1_${"B".repeat(42)}A"
    private val hash = "f".repeat(64)

    @Test
    fun boundsGapsAndInvalidBatchContinuation(): Unit = runBlocking {
        val root = Files.createTempDirectory("wire-").toFile()
        try {
            val files = FakeMeshFiles(a).also { it.admit(mesh, a, 0) }
            val store = CatalogStore(root, mesh)
            val first = signed(files, mesh, a, 0, 1)
            val second = signed(files, mesh, a, 0, 2)
            val huge = CatalogOp(mesh, a, 0, 1_000_000_000_000, first.body, first.signature)
            assertTrue(store.acceptBatch(listOf(second, huge, first, second), files).rejected.isNotEmpty())
            assertEquals(2L, store.vector()[CatalogClock(a, 0)])
            repeat(78) { store.own(a, 0, CatalogBody.Add(EntryId.new(), "name", hash, 1), files) }
            val (batch, more) = CatalogWire.missing(store.snapshot(), emptyMap())
            assertTrue(more)
            assertEquals(64, batch.size)
            assertEquals(64, CatalogWire.decode(CatalogWire.encode(CatalogWire.Message(store.vector(), batch), true), true).ops.size)
            assertTrue(runCatching { CatalogWire.encode(CatalogWire.Message(emptyMap(), List(65) { first }), false) }.isFailure)
            assertEquals(80, store.entries.value.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun liveEntryCapDoesNotStallContiguousClock(): Unit = runBlocking {
        val root = Files.createTempDirectory("cap-").toFile()
        try {
            val files = FakeMeshFiles(a)
            val store = CatalogStore(root, mesh)
            for (digit in 0..8) {
                val author = digit.toString().repeat(64)
                files.admit(mesh, author, 0)
                val signer = FakeMeshFiles(author)
                val count = if (digit == 8) 1 else 1024
                for (batch in (1..count).chunked(64)) {
                    val signed = batch.map { seq -> signed(signer, mesh, author, 0, seq.toLong()) }
                    assertEquals(signed.size, store.acceptBatch(signed, files).accepted)
                }
            }
            assertEquals(CatalogCodec.MAX_LIVE_ENTRIES, store.entries.value.size)
            assertTrue(store.limitedEntries)
            assertEquals(1L, store.vector()[CatalogClock("8".repeat(64), 0)])
        } finally { root.deleteRecursively() }
    }

    @Test
    fun sharesOnlyLocallyObtainedHashesForThatGroupAndSurvivesReopen(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val owner = CatalogNode(a, network).also { network[a] = it }
        val visitor = CatalogNode(b, network).also { network[b] = it }
        listOf(owner, visitor).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0); node.fake.admit(other, it, 0) } }
        val rootA = Files.createTempDirectory("cat-a-").toFile()
        val rootB = Files.createTempDirectory("cat-b-").toFile()
        val sessionA = MeshSession({ owner })
        val sessionB = MeshSession({ visitor })
        val catalogsA = GroupCatalogs(rootA, sessionA)
        val catalogsB = GroupCatalogs(rootB, sessionB)
        val jobs = listOf(launch { catalogsA.run() }, launch { catalogsB.run() }, launch { sessionA.run() }, launch { sessionB.run() })
        try {
            waitFor { !sessionA.state.value.loading && !sessionB.state.value.loading }
            waitFor { runCatching { visitor.fake.appRequest(mesh, a, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
            val bEntry = assertIs<AddFileResult.Added>(catalogsB.addFile(other, "only-b", ImportedBlob(hash, 1)))
            assertEquals(setOf(hash), visitor.fake.sharesFor(other))
            assertTrue(visitor.fake.sharesFor(mesh).isEmpty())
            val flow = catalogsB.entries(mesh)
            assertIs<AddFileResult.Added>(catalogsA.addFile(mesh, "probe", ImportedBlob(hash, 1)))
            catalogsB.exchange(mesh, a)
            assertEquals(1, flow.value.size)
            assertTrue(visitor.fake.sharesFor(mesh).isEmpty())
            catalogsB.markObtained(mesh, hash)
            assertEquals(setOf(hash), visitor.fake.sharesFor(mesh))
            val reSession = MeshSession({ visitor })
            val reCatalog = GroupCatalogs(rootB, reSession)
            val reJobs = listOf(launch { reCatalog.run() }, launch { reSession.run() })
            try {
                waitFor { !reSession.state.value.loading && visitor.fake.sharesFor(mesh) == setOf(hash) }
                assertEquals(flow.value, reCatalog.entries(mesh).value)
                assertFalse(reCatalog.errors.value[mesh]?.kind == CatalogErrorKind.Share)
                assertEquals(b, bEntry.entryId.author)
            } finally { reJobs.forEach { it.cancelAndJoin() } }
        } finally { jobs.forEach { it.cancelAndJoin() }; rootA.deleteRecursively(); rootB.deleteRecursively() }
    }

    @Test
    fun equivocationConvergesAndGappedPeerTerminates(): Unit = runBlocking {
        for (loserInitiates in listOf(false, true)) {
            val network = mutableMapOf<String, CatalogNode>()
            val left = CatalogNode(a, network).also { network[a] = it }
            val right = CatalogNode(b, network).also { network[b] = it }
            listOf(left, right).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
            val firstRoot = Files.createTempDirectory("left-").toFile()
            val secondRoot = Files.createTempDirectory("right-").toFile()
            val one = signed(left.fake, mesh, a, 0, 1, "first")
            val unsignedTwo = one.copy(body = (one.body as CatalogBody.Add).copy(name = "second"), signature = "")
            val two = unsignedTwo.copy(signature = left.fake.signApp(CatalogCodec.domain, CatalogCodec.signed(unsignedTwo)))
            CatalogStore(firstRoot.resolve("groups/$mesh"), mesh).accept(one, left.fake)
            CatalogStore(secondRoot.resolve("groups/$mesh"), mesh).accept(two, right.fake)
            val firstSession = MeshSession({ left })
            val secondSession = MeshSession({ right })
            val first = GroupCatalogs(firstRoot, firstSession)
            val second = GroupCatalogs(secondRoot, secondSession)
            val jobs = listOf(launch { first.run() }, launch { second.run() }, launch { firstSession.run() }, launch { secondSession.run() })
            try {
                waitFor { !firstSession.state.value.loading && !secondSession.state.value.loading }
                waitFor { runCatching { left.fake.appRequest(mesh, b, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
                if (loserInitiates) { second.exchange(mesh, a); first.exchange(mesh, b) }
                else { first.exchange(mesh, b); second.exchange(mesh, a) }
                assertEquals(first.entries(mesh).value, second.entries(mesh).value)
                assertTrue(first.errors.value[mesh]?.reason?.contains("equivocation") == true)
                assertTrue(second.errors.value[mesh]?.reason?.contains("equivocation") == true)
                val gap = signed(left.fake, mesh, a, 0, 3)
                val batch = CatalogWire.encode(CatalogWire.Message(emptyMap(), listOf(gap)), false)
                right.fake.appRequest(mesh, a, CatalogWire.protocol, batch)
                first.exchange(mesh, b)
                assertEquals(1, second.entries(mesh).value.size)
            } finally { jobs.forEach { it.cancelAndJoin() }; firstRoot.deleteRecursively(); secondRoot.deleteRecursively() }
        }
    }

    @Test
    fun oneWayPullPagesMultipleClocksWithoutSkippingOrRejectingGaps(): Unit = runBlocking {
        for (secondClockSize in listOf(100, 200)) {
            val network = mutableMapOf<String, CatalogNode>()
            val x = CatalogNode(a, network).also { network[a] = it }
            val y = CatalogNode(b, network).also { network[b] = it }
            listOf(x, y).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
            val rootX = Files.createTempDirectory("pull-x-").toFile()
            val rootY = Files.createTempDirectory("pull-y-").toFile()
            val ops = (1..100).map { signed(x.fake, mesh, a, 0, it.toLong()) } +
                (1..secondClockSize).map { signed(y.fake, mesh, b, 0, it.toLong()) }
            CatalogStore(rootY.resolve("groups/$mesh"), mesh).acceptBatch(ops, y.fake)
            val sessionX = MeshSession({ x })
            val sessionY = MeshSession({ y })
            val puller = GroupCatalogs(rootX, sessionX)
            val peer = GroupCatalogs(rootY, sessionY)
            val jobs = listOf(launch { sessionX.run() }, launch { sessionY.run() }, launch { peer.run() })
            try {
                waitFor { !sessionX.state.value.loading && !sessionY.state.value.loading }
                waitFor { runCatching { y.fake.appRequest(mesh, a, CatalogWire.protocol,
                    CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
                puller.exchange(mesh, b)
                assertEquals(100 + secondClockSize, puller.entries(mesh).value.size)
                assertEquals(mapOf(CatalogClock(a, 0) to 100L, CatalogClock(b, 0) to secondClockSize.toLong()),
                    CatalogStore(rootX.resolve("groups/$mesh"), mesh).vector())
                assertTrue(puller.errors.value[mesh] == null, "One-way pull reported ${puller.errors.value[mesh]}")
            } finally { jobs.forEach { it.cancelAndJoin() }; rootX.deleteRecursively(); rootY.deleteRecursively() }
        }
    }

    @Test
    fun belowHeadEquivocationConvergesAndReachesLaterPeer(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val x = CatalogNode(a, network).also { network[a] = it }
        val y = CatalogNode(b, network).also { network[b] = it }
        listOf(x, y).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
        val rootX = Files.createTempDirectory("conflict-x-").toFile()
        val rootY = Files.createTempDirectory("conflict-y-").toFile()
        val winner = signed(x.fake, mesh, a, 0, 1, "aaaa")
        val unsignedLoser = winner.copy(body = (winner.body as CatalogBody.Add).copy(name = "zzzz"), signature = "")
        val loser = unsignedLoser.copy(signature = x.fake.signApp(CatalogCodec.domain, CatalogCodec.signed(unsignedLoser)))
        val second = signed(x.fake, mesh, a, 0, 2, "common")
        CatalogStore(rootX.resolve("groups/$mesh"), mesh).acceptBatch(listOf(winner, second), x.fake)
        CatalogStore(rootY.resolve("groups/$mesh"), mesh).acceptBatch(listOf(loser, second), y.fake)
        val sessionX = MeshSession({ x })
        val sessionY = MeshSession({ y })
        val catalogsX = GroupCatalogs(rootX, sessionX)
        val catalogsY = GroupCatalogs(rootY, sessionY)
        val jobs = listOf(launch { catalogsX.run() }, launch { catalogsY.run() }, launch { sessionX.run() }, launch { sessionY.run() })
        try {
            waitFor { !sessionX.state.value.loading && !sessionY.state.value.loading }
            waitFor { runCatching { x.fake.appRequest(mesh, b, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
            repeat(3) { catalogsX.exchange(mesh, b); catalogsY.exchange(mesh, a) }
            assertEquals(catalogsX.entries(mesh).value, catalogsY.entries(mesh).value)
            assertEquals(setOf("aaaa", "common"), catalogsX.entries(mesh).value.map { it.name }.toSet())
            assertTrue(catalogsX.errors.value[mesh]?.reason?.contains("equivocation") == true)
            assertTrue(catalogsY.errors.value[mesh]?.reason?.contains("equivocation") == true)
            val z = CatalogNode(c, network).also { network[c] = it }
            listOf(x, y, z).forEach { it.fake.admit(mesh, c, 0); it.fake.admit(mesh, a, 0) }
            val rootZ = Files.createTempDirectory("conflict-z-").toFile()
            val sessionZ = MeshSession({ z })
            val catalogsZ = GroupCatalogs(rootZ, sessionZ)
            val extra = listOf(launch { catalogsZ.run() }, launch { sessionZ.run() })
            try {
                waitFor { !sessionZ.state.value.loading }
                catalogsZ.exchange(mesh, b)
                assertEquals(catalogsX.entries(mesh).value, catalogsZ.entries(mesh).value)
                assertTrue(catalogsZ.errors.value[mesh]?.reason?.contains("equivocation") == true)
            } finally { extra.forEach { it.cancelAndJoin() }; rootZ.deleteRecursively() }
        } finally { jobs.forEach { it.cancelAndJoin() }; rootX.deleteRecursively(); rootY.deleteRecursively() }
    }

    @Test
    fun differingWholeClockPagesAndClockCountIsBounded(): Unit = runBlocking {
        val signer = FakeMeshFiles(a)
        val ops = (1..140).map { signed(signer, mesh, a, 0, it.toLong()) }
        val vector = mapOf(CatalogClock(a, 0) to 140L)
        val wrongDigest = mapOf(CatalogClock(a, 0) to "0".repeat(64))
        assertEquals(64, CatalogWire.missing(ops, vector, digests = wrongDigest).first.size)
        assertEquals(64, CatalogWire.missing(ops, vector, digests = wrongDigest, skip = 64).first.size)
        assertEquals(12, CatalogWire.missing(ops, vector, digests = wrongDigest, skip = 128).first.size)
        assertTrue(CatalogWire.missing(ops, vector, digests = wrongDigest, skip = 140).first.isEmpty())
        val root = Files.createTempDirectory("clock-cap-").toFile()
        try {
            val files = FakeMeshFiles(a)
            val store = CatalogStore(root, mesh)
            val distinct = (0..256).map { index ->
                val author = index.toString(16).padStart(64, '0')
                files.admit(mesh, author, 0)
                signed(FakeMeshFiles(author), mesh, author, 0, 1)
            }
            val result = store.acceptBatch(distinct, files)
            assertEquals(256, result.accepted)
            assertTrue(result.rejected.single().contains("clock limit"))
            assertEquals(256, store.vector().size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun aConflictPastTheFirstPageConverges(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val x = CatalogNode(a, network).also { network[a] = it }
        val y = CatalogNode(b, network).also { network[b] = it }
        listOf(x, y).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
        val rootX = Files.createTempDirectory("deep-x-").toFile()
        val rootY = Files.createTempDirectory("deep-y-").toFile()
        val history = (1..140).map { signed(x.fake, mesh, a, 0, it.toLong(), "common") }
        val unsignedLoser = history[99].copy(body = (history[99].body as CatalogBody.Add).copy(name = "zzzzzz"), signature = "")
        val loser = unsignedLoser.copy(signature = x.fake.signApp(CatalogCodec.domain, CatalogCodec.signed(unsignedLoser)))
        CatalogStore(rootX.resolve("groups/$mesh"), mesh).acceptBatch(history, x.fake)
        CatalogStore(rootY.resolve("groups/$mesh"), mesh).acceptBatch(history.mapIndexed { index, op -> if (index == 99) loser else op }, y.fake)
        val sessionX = MeshSession({ x })
        val sessionY = MeshSession({ y })
        val first = GroupCatalogs(rootX, sessionX)
        val second = GroupCatalogs(rootY, sessionY)
        val jobs = listOf(launch { first.run() }, launch { second.run() }, launch { sessionX.run() }, launch { sessionY.run() })
        try {
            waitFor { !sessionX.state.value.loading && !sessionY.state.value.loading }
            waitFor { runCatching { x.fake.appRequest(mesh, b, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
            repeat(3) { first.exchange(mesh, b); second.exchange(mesh, a) }
            assertEquals(first.entries(mesh).value, second.entries(mesh).value)
            assertEquals(140, first.entries(mesh).value.size)
            assertTrue(first.errors.value[mesh]?.reason?.contains("equivocation") == true)
            assertTrue(second.errors.value[mesh]?.reason?.contains("equivocation") == true)
        } finally { jobs.forEach { it.cancelAndJoin() }; rootX.deleteRecursively(); rootY.deleteRecursively() }
    }

    @Test
    fun flowSurvivesLeaveRejoinAndErrorsStayUntilCleared(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val node = CatalogNode(a, network).also { network[a] = it }
        node.fake.admit(mesh, a, 0)
        node.fake.admit(mesh, a, 1)
        val root = Files.createTempDirectory("leave-").toFile()
        val session = MeshSession({ node })
        val catalogs = GroupCatalogs(root, session)
        val flow = catalogs.entries(mesh)
        val jobs = listOf(launch { catalogs.run() }, launch { session.run() })
        try {
            waitFor { !session.state.value.loading }
            assertIs<AddFileResult.Added>(catalogs.addFile(mesh, "file", ImportedBlob(hash, 1)))
            assertEquals(1, flow.value.size)
            assertIs<AddFileResult.Rejected>(catalogs.addFile(mesh, "../x", ImportedBlob(hash, 1)))
            session.leaveGroup(mesh)
            waitFor { !root.resolve("groups/$mesh").exists() }
            assertTrue(flow.value.isEmpty())
            node.joined = true
            node.generation = 1
            assertEquals(mesh, session.createGroup("Rejoined"))
            assertIs<AddFileResult.Added>(catalogs.addFile(mesh, "new generation", ImportedBlob(hash, 1)))
            assertEquals(1L, CatalogStore(root.resolve("groups/$mesh"), mesh).vector()[CatalogClock(a, 1)])
            assertSame(flow, catalogs.entries(mesh))
            assertEquals(1, flow.value.size)
            catalogs.clearError(mesh)
            assertTrue(catalogs.errors.value.isEmpty())
        } finally { jobs.forEach { it.cancelAndJoin() }; root.deleteRecursively() }
    }

    @Test
    fun pushAndPresenceTriggersSyncWhenPeerAppears(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val sender = CatalogNode(a, network).also { network[a] = it }
        val receiver = CatalogNode(b, network).also { network[b] = it }
        receiver.online = false
        listOf(sender, receiver).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
        val firstRoot = Files.createTempDirectory("push-a-").toFile()
        val secondRoot = Files.createTempDirectory("push-b-").toFile()
        val firstSession = MeshSession({ sender })
        val secondSession = MeshSession({ receiver })
        val first = GroupCatalogs(firstRoot, firstSession)
        val second = GroupCatalogs(secondRoot, secondSession)
        val jobs = listOf(launch { first.run() }, launch { second.run() }, launch { firstSession.run() }, launch { secondSession.run() })
        try {
            waitFor { !firstSession.state.value.loading && !secondSession.state.value.loading }
            assertIs<AddFileResult.Added>(first.addFile(mesh, "offline", ImportedBlob(hash, 1)))
            assertTrue(second.entries(mesh).value.isEmpty())
            receiver.online = true
            try { waitFor { firstSession.state.value.groups.single { it.id == mesh }.members.any { it.id == b && it.online } } }
            catch (failure: AssertionError) { throw AssertionError("Peer did not come online: ${firstSession.state.value.groups}", failure) }
            try { waitFor { second.entries(mesh).value.size == 1 } }
            catch (failure: AssertionError) { throw AssertionError("Presence did not exchange: ${first.errors.value} / ${second.errors.value}; requests=${sender.requests}", failure) }
            val pushes = sender.requests
            assertIs<AddFileResult.Added>(first.addFile(mesh, "push", ImportedBlob(hash, 1)))
            waitFor { second.entries(mesh).value.size == 2 }
            assertTrue(sender.requests > pushes)
        } finally { jobs.forEach { it.cancelAndJoin() }; firstRoot.deleteRecursively(); secondRoot.deleteRecursively() }
    }

    @Test
    fun previouslyAdmittedDepartedAuthorCanStillBeRelayed(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val old = CatalogNode(a, network).also { network[a] = it }
        val member = CatalogNode(b, network).also { network[b] = it }
        member.departed = a
        member.fake.admit(mesh, b, 0)
        member.fake.admit(mesh, a, 0)
        val root = Files.createTempDirectory("departed-").toFile()
        val session = MeshSession({ member })
        val catalogs = GroupCatalogs(root, session)
        val jobs = listOf(launch { catalogs.run() }, launch { session.run() })
        try {
            waitFor { !session.state.value.loading }
            waitFor { runCatching { member.fake.appRequest(mesh, a, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), emptyList()), false)) }.isSuccess }
            assertTrue(session.state.value.groups.single { it.id == mesh }.members.none { it.id == a })
            val signed = signed(old.fake, mesh, a, 0, 1, "old file")
            old.appRequest(mesh, b, CatalogWire.protocol, CatalogWire.encode(CatalogWire.Message(emptyMap(), listOf(signed)), false))
            assertEquals(a, catalogs.entries(mesh).value.single().author)
        } finally { jobs.forEach { it.cancelAndJoin() }; root.deleteRecursively() }
    }

    @Test
    fun sixtySecondAntiEntropyTriggersWithoutChanges(): Unit = runBlocking {
        val network = mutableMapOf<String, CatalogNode>()
        val sender = CatalogNode(a, network).also { network[a] = it }
        val receiver = CatalogNode(b, network).also { network[b] = it }
        listOf(sender, receiver).forEach { node -> listOf(a, b).forEach { node.fake.admit(mesh, it, 0) } }
        val firstRoot = Files.createTempDirectory("timer-a-").toFile()
        val secondRoot = Files.createTempDirectory("timer-b-").toFile()
        val firstSession = MeshSession({ sender })
        val secondSession = MeshSession({ receiver })
        val first = GroupCatalogs(firstRoot, firstSession)
        val second = GroupCatalogs(secondRoot, secondSession)
        val jobs = listOf(launch { first.run() }, launch { second.run() }, launch { firstSession.run() }, launch { secondSession.run() })
        try {
            waitFor { !firstSession.state.value.loading && !secondSession.state.value.loading }
            waitFor { sender.requests > 0 && receiver.requests > 0 }
            val baseline = sender.requests + receiver.requests
            delay(61_000)
            waitFor { sender.requests + receiver.requests > baseline }
        } finally { jobs.forEach { it.cancelAndJoin() }; firstRoot.deleteRecursively(); secondRoot.deleteRecursively() }
    }

    private suspend fun signed(files: FakeMeshFiles, meshId: String, author: String, generation: Long, seq: Long, name: String = "name"): CatalogOp {
        val op = CatalogOp(meshId, author, generation, seq, CatalogBody.Add(EntryId.new(), name, hash, 1), "")
        return op.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(op)))
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        repeat(150) { if (condition()) return; delay(20) }
        assertTrue(condition())
    }

    private inner class CatalogNode(val id: String, private val network: Map<String, CatalogNode>, val fake: FakeMeshFiles = FakeMeshFiles(id)) : MeshNode, MeshFiles by fake {
        var joined = true
        var generation = 0L
        var online = true
        var departed: String? = null
        var requests = 0
        override suspend fun status() = NodeStatus(id, id,
            if (joined) listOf(MeshStatus(mesh, "Group", listOf(a, b, c).filter { it in network && it != departed }.map { MeshMember(it, it, if (it == id) generation else 0) }), MeshStatus(other, "Other", listOf(a, b).map { MeshMember(it, it, 0) })) else emptyList(),
            network.keys.filter { it != id }.map { NodePeer(it, it, network.getValue(it).online, if (network.getValue(it).online) 0 else null, null) }, true)
        override suspend fun appRequest(meshId: String, peer: String, protocol: String, bytes: ByteArray): ByteArray {
            requests++
            return network[peer]?.fake?.appRequest(meshId, id, protocol, bytes) ?: throw MeshNodeException(MeshFailure.Unavailable)
        }
        override suspend fun createMesh(name: String) = mesh
        override suspend fun pair() = PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
        override suspend fun add(meshId: String, ticket: String) = b
        override suspend fun ping(device: String) = NodePong(device, 1)
        override suspend fun leaveMesh(meshId: String): LeftMesh { joined = false; return LeftMesh(meshId, "Group", 1, 0) }
        override suspend fun shutdown() = Unit
    }
}
