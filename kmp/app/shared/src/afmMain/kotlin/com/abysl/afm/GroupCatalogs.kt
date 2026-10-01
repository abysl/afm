package com.abysl.afm

import blue.rae.spirit.sdk.AppCallInfo
import blue.rae.spirit.sdk.ImportedBlob
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshSession
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface AddFileResult {
    data class Added(val entryId: CatalogEntryId) : AddFileResult
    data class Rejected(val problem: FileNameProblem) : AddFileResult
    data class Failed(val reason: String) : AddFileResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data class Failed(val reason: String) : RemoveResult
}

enum class CatalogErrorKind { Storage, InvalidOperation, Sync, Share, Write }
data class CatalogError(val kind: CatalogErrorKind, val reason: String)

class GroupCatalogs(private val root: File, private val session: MeshSession) {
    private val stores = ConcurrentHashMap<String, CatalogStore>()
    private val views = ConcurrentHashMap<String, MutableStateFlow<List<CatalogEntry>>>()
    private val stableViews = ConcurrentHashMap<String, StateFlow<List<CatalogEntry>>>()
    private val obtained = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutation = Mutex()
    private val exchanges = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private val pendingPushes = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val shareRetries = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(4)
    private val mutableErrors = MutableStateFlow<Map<String, CatalogError>>(emptyMap())
    val errors: StateFlow<Map<String, CatalogError>> = mutableErrors.asStateFlow()
    private var activeScope: CoroutineScope? = null

    fun entries(meshId: String): StateFlow<List<CatalogEntry>> = stableViews.computeIfAbsent(meshId) {
        views.computeIfAbsent(it) { MutableStateFlow(emptyList()) }.asStateFlow()
    }
    fun validateFileName(name: String): FileNameProblem? = com.abysl.afm.validateFileName(name)
    fun clearError(meshId: String) { mutableErrors.update { it - meshId } }

    suspend fun addFile(meshId: String, name: String, imported: ImportedBlob): AddFileResult {
        validateFileName(name)?.let { return AddFileResult.Rejected(it) }
        val id = EntryId.new()
        var entryId: CatalogEntryId? = null
        try {
            val files = requireNotNull(session.files.value) { "Node is closed" }
            mutation.withLock {
                val state = session.state.value
                val member = requireNotNull(state.groups.firstOrNull { it.id == meshId }?.members?.firstOrNull { it.id == state.nodeId }) { "Group is not joined" }
                entryId = CatalogEntryId(state.nodeId, member.generation, id)
                val store = store(meshId)
                withContext(Dispatchers.IO) { store.own(state.nodeId, member.generation, CatalogBody.Add(id, name, imported.hash, imported.size), files) }
                publish(meshId, store)
                rememberObtained(meshId, imported.hash)
            }
            success(meshId, CatalogErrorKind.Write)
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
            stores[meshId]?.let { publish(meshId, it) }
            if (entryId == null || entries(meshId).value.none { it.id == entryId }) {
                problem(meshId, CatalogErrorKind.Write, failure)
                return AddFileResult.Failed(failure.message ?: "Could not add file")
            }
            problem(meshId, CatalogErrorKind.Write, failure)
        }
        val added = AddFileResult.Added(requireNotNull(entryId))
        afterCommit(meshId)
        return added
    }

    suspend fun remove(meshId: String, entryId: CatalogEntryId): RemoveResult {
        var committed = false
        var attempted = false
        try {
            val files = requireNotNull(session.files.value) { "Node is closed" }
            mutation.withLock {
                val state = session.state.value
                val member = requireNotNull(state.groups.firstOrNull { it.id == meshId }?.members?.firstOrNull { it.id == state.nodeId }) { "Group is not joined" }
                val store = store(meshId)
                attempted = true
                withContext(Dispatchers.IO) { store.own(state.nodeId, member.generation, CatalogBody.Remove(entryId), files) }
                committed = true
                publish(meshId, store)
                pruneObtained(meshId)
            }
            success(meshId, CatalogErrorKind.Write)
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
            stores[meshId]?.let { publish(meshId, it) }
            if (!committed && (!attempted || entries(meshId).value.any { it.id == entryId })) {
                problem(meshId, CatalogErrorKind.Write, failure)
                return RemoveResult.Failed(failure.message ?: "Could not remove file")
            }
            problem(meshId, CatalogErrorKind.Write, failure)
        }
        afterCommit(meshId)
        return RemoveResult.Removed
    }

    suspend fun markObtained(meshId: String, hash: String) {
        try {
            mutation.withLock {
                require(hash.matches(Regex("[0-9a-f]{64}")))
                require(session.state.value.groups.any { it.id == meshId }) { "Group is not joined" }
                require(entries(meshId).value.any { it.hash == hash }) { "Hash is not live in group" }
                rememberObtained(meshId, hash)
            }
            success(meshId, CatalogErrorKind.Share)
            session.files.value?.let { reconcile(meshId, it) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
            problem(meshId, CatalogErrorKind.Share, failure)
        }
    }

    suspend fun run(): Unit = coroutineScope {
        activeScope = this
        launch {
            session.files.collectLatest { files ->
                if (files == null) return@collectLatest
                try {
                    files.registerAppHandler(CatalogWire.protocol) { call, bytes -> handle(files, call, bytes) }
                    if (!session.state.value.loading) for (group in session.state.value.groups) reconcile(group.id, files)
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                    problem("*", CatalogErrorKind.Sync, failure)
                } finally { withContext(NonCancellable) { runCatching { files.unregisterAppHandler(CatalogWire.protocol) } } }
            }
        }
        launch {
            var previousOnline = emptySet<Pair<String, String>>()
            var previousGroups = emptySet<String>()
            session.state.collect { state ->
                if (state.loading || state.nodeId.isEmpty() || session.files.value == null) return@collect
                val active = state.groups.map { it.id }.toSet()
                try {
                    mutation.withLock {
                        withContext(Dispatchers.IO) {
                            File(root, "groups").listFiles()?.filter { it.isDirectory && it.name.matches(CatalogCodec.meshPattern) && it.name !in active }?.forEach { directory ->
                                check(directory.deleteRecursively()) { "Could not delete departed catalog" }
                                stores.remove(directory.name)
                                obtained.remove(directory.name)
                                views[directory.name]?.value = emptyList()
                            }
                        }
                        for (id in active - previousGroups) session.files.value?.let { reconcile(id, it) }
                    }
                    val online = state.groups.flatMap { group -> group.members.filter { it.online && it.id != state.nodeId }.map { group.id to it.id } }.toSet()
                    (online - previousOnline).forEach { (mesh, peer) -> queuePush(mesh, peer) }
                    previousOnline = online
                    previousGroups = active
                } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                    problem("*", CatalogErrorKind.Storage, failure)
                }
            }
        }
        launch {
            val indices = mutableMapOf<String, Int>()
            while (true) {
                delay(60_000)
                val state = session.state.value
                for (group in state.groups) {
                    val peers = group.members.filter { it.online && it.id != state.nodeId }
                    if (peers.isNotEmpty()) {
                        val index = indices.getOrDefault(group.id, 0)
                        indices[group.id] = index + 1
                        queuePush(group.id, peers[index % peers.size].id)
                    }
                }
            }
        }
        try { awaitCancellation() } finally { activeScope = null }
    }

    internal suspend fun exchange(meshId: String, peer: String) = withContext(Dispatchers.Default) {
        val lock = exchanges.computeIfAbsent(meshId to peer) { Mutex() }
        try {
            withTimeout(15_000) {
                lock.withLock {
                    slots.withPermit {
                        val files = session.files.value ?: return@withPermit
                        val catalog = store(meshId)
                        var remote = emptyMap<CatalogClock, Long>()
                        var remoteDigests = emptyMap<CatalogClock, String>()
                        var responseCursor = 0
                        var pageVector = emptyMap<CatalogClock, Long>()
                        var pageDigests = emptyMap<CatalogClock, String>()
                        var uploadCursor = 0
                        var first = true
                        var stalled = false
                        while (true) {
                            val before = catalog.vector()
                            val snapshot = catalog.snapshot()
                            val localDigests = CatalogWire.digests(snapshot)
                            if (responseCursor == 0) {
                                pageVector = before
                                pageDigests = localDigests
                            }
                            val initial = first
                            val (outgoing, outgoingMore) = if (first) emptyList<CatalogOp>() to false else
                                CatalogWire.missing(snapshot, remote, digests = remoteDigests, skip = uploadCursor)
                            first = false
                            val request = CatalogWire.Message(pageVector, outgoing, digests = pageDigests, cursor = responseCursor)
                            val reply = CatalogWire.decode(files.appRequest(meshId, peer, CatalogWire.protocol,
                                CatalogWire.encode(request, reply = false)), reply = true)
                            val priorRemote = remote
                            val priorDigests = remoteDigests
                            remote = reply.vector
                            remoteDigests = reply.digests
                            if (reply.malformed > 0) problem(meshId, CatalogErrorKind.InvalidOperation, "Malformed catalog operation")
                            val accepted = accept(meshId, reply.ops, files)
                            val after = catalog.vector()
                            val afterDigests = CatalogWire.digests(catalog.snapshot())
                            val localChanged = before != after || localDigests != afterDigests
                            val remoteChanged = priorRemote != remote || priorDigests != remoteDigests
                            uploadCursor = if (localChanged || remoteChanged || !outgoingMore) 0 else uploadCursor + outgoing.size
                            responseCursor = if (remoteChanged || !reply.more) 0 else responseCursor + reply.ops.size
                            val pending = after != remote || afterDigests != remoteDigests || reply.more || outgoingMore
                            if (!pending) break
                            if (accepted == 0 && !localChanged && !remoteChanged && !initial &&
                                responseCursor == 0 && uploadCursor == 0) {
                                problem(meshId, CatalogErrorKind.Sync, "Catalog peer made no progress")
                                stalled = true
                                break
                            }
                        }
                        if (!stalled) success(meshId, CatalogErrorKind.Sync)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) problem(meshId, CatalogErrorKind.Sync, "Catalog exchange deadline") else throw cancelled
        } catch (failure: Exception) { problem(meshId, CatalogErrorKind.Sync, failure) }
    }

    private fun handle(files: MeshFiles, call: AppCallInfo, bytes: ByteArray): ByteArray = try {
        runBlocking(Dispatchers.IO) {
            withTimeout(call.remainingMs.coerceAtLeast(1)) {
                require(!call.isCancelled && call.remainingMs > 0)
                val request = CatalogWire.decode(bytes, reply = false)
                require(session.state.value.groups.any { it.id == call.meshId })
                if (request.malformed > 0) problem(call.meshId, CatalogErrorKind.InvalidOperation, "Malformed catalog operation")
                accept(call.meshId, request.ops, files)
                val catalog = store(call.meshId)
                val snapshot = catalog.snapshot()
                val (missing, more) = CatalogWire.missing(snapshot, request.vector, digests = request.digests, maxOps = CatalogWire.MAX_OPS_PER_RESPONSE, skip = request.cursor)
                CatalogWire.encode(CatalogWire.Message(catalog.vector(), missing, more, CatalogWire.digests(snapshot)), reply = true)
            }
        }
    } catch (failure: Exception) {
        problem(call.meshId, CatalogErrorKind.Sync, failure)
        throw failure
    }

    private suspend fun accept(meshId: String, ops: List<CatalogOp>, files: MeshFiles): Int = mutation.withLock {
        require(session.state.value.groups.any { it.id == meshId }) { "Group is not joined" }
        val catalog = store(meshId)
        val result = withContext(Dispatchers.IO) { catalog.acceptBatch(ops, files) }
        if (result.rejected.isNotEmpty()) problem(meshId, CatalogErrorKind.InvalidOperation, result.rejected.first())
        if (result.accepted > 0) {
            publish(meshId, catalog)
            pruneObtained(meshId)
            reconcile(meshId, files)
        }
        result.accepted
    }

    private suspend fun afterCommit(meshId: String) {
        session.files.value?.let { runCatching { reconcile(meshId, it) }.onFailure { failure -> problem(meshId, CatalogErrorKind.Share, failure) } }
        val state = session.state.value
        state.groups.firstOrNull { it.id == meshId }?.members?.filter { it.online && it.id != state.nodeId }?.forEach { queuePush(meshId, it.id) }
    }

    private suspend fun reconcile(meshId: String, files: MeshFiles): Boolean {
        try {
            val store = store(meshId)
            val hashes = withContext(Dispatchers.IO) { obtainedSet(meshId).intersect(store.entries.value.map { it.hash }.toSet()).sorted() }
            files.setShares(meshId, hashes)
            success(meshId, CatalogErrorKind.Share)
            return true
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
            problem(meshId, CatalogErrorKind.Share, failure)
            if (shareRetries.add(meshId)) activeScope?.launch {
                var pause = 1000L
                try {
                    while (session.files.value === files && session.state.value.groups.any { it.id == meshId }) {
                        delay(pause)
                        pause = (pause * 2).coerceAtMost(60_000)
                        if (reconcile(meshId, files)) break
                    }
                } finally { shareRetries.remove(meshId) }
            }
            return false
        }
    }

    private fun queuePush(meshId: String, peer: String) {
        val pair = meshId to peer
        if (!pendingPushes.add(pair)) return
        val scope = activeScope ?: run { pendingPushes.remove(pair); return }
        scope.launch { try { exchange(meshId, peer) } finally { pendingPushes.remove(pair) } }
    }

    private suspend fun store(meshId: String): CatalogStore = withContext(Dispatchers.IO) {
        stores.computeIfAbsent(meshId) {
            CatalogStore(File(File(root, "groups"), it), it).also { store ->
                if (store.storageError != null) problem(meshId, CatalogErrorKind.Storage, store.storageError!!)
                views.computeIfAbsent(it) { MutableStateFlow(emptyList()) }.value = store.entries.value
            }
        }
    }

    private fun publish(meshId: String, store: CatalogStore) {
        views.computeIfAbsent(meshId) { MutableStateFlow(emptyList()) }.value = store.entries.value
        if (store.limitedEntries) problem(meshId, CatalogErrorKind.InvalidOperation, "Catalog live-entry limit")
        if (store.storageError != null) problem(meshId, CatalogErrorKind.Storage, store.storageError!!)
    }

    private suspend fun obtainedSet(meshId: String): MutableSet<String> = withContext(Dispatchers.IO) {
        obtained.computeIfAbsent(meshId) {
            File(File(File(root, "groups"), meshId), "obtained").takeIf(File::exists)?.readLines()
                ?.filter { hash -> hash.matches(Regex("[0-9a-f]{64}")) }?.toMutableSet() ?: mutableSetOf()
        }
    }

    private suspend fun rememberObtained(meshId: String, hash: String) = withContext(Dispatchers.IO) {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val hashes = obtainedSet(meshId)
        if (hashes.add(hash)) persistObtained(meshId, hashes)
    }

    private suspend fun pruneObtained(meshId: String) = withContext(Dispatchers.IO) {
        val hashes = obtainedSet(meshId)
        if (hashes.retainAll(entries(meshId).value.map { it.hash }.toSet())) persistObtained(meshId, hashes)
    }

    private fun persistObtained(meshId: String, hashes: Set<String>) {
        val dir = File(File(root, "groups"), meshId)
        dir.mkdirs()
        val temp = File.createTempFile("obtained-", ".tmp", dir)
        try {
            RandomAccessFile(temp, "rw").use { file -> file.write(hashes.sorted().joinToString("\n", postfix = "\n").encodeToByteArray()); file.fd.sync() }
            atomicReplaceCatalogFile(temp, File(dir, "obtained"))
            syncCatalogDirectory(dir)
        } finally { temp.delete() }
    }

    private fun success(meshId: String, kind: CatalogErrorKind) { mutableErrors.update { existing -> if (existing[meshId]?.kind == kind) existing - meshId else existing } }
    private fun problem(meshId: String, kind: CatalogErrorKind, failure: Throwable) = problem(meshId, kind, failure.message ?: "Catalog operation failed")
    private fun problem(meshId: String, kind: CatalogErrorKind, reason: String) { mutableErrors.update { it + (meshId to CatalogError(kind, reason)) } }
}
