package com.abysl.afm

import blue.rae.spirit.sdk.ImportedBlob
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshNodeException
import blue.rae.spirit.sdk.MeshSession
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun CatalogEntryId.rowKey(): String = "$author:$generation:${entry.hex}"

private fun fileNameMessage(problem: FileNameProblem): String = when (problem) {
    FileNameProblem.Empty -> "Choose a file with a name."
    FileNameProblem.TooLong -> "File name is too long. Rename it to 255 UTF-8 bytes or less and retry."
    FileNameProblem.InvalidCharacter -> "File name contains a slash or unsafe character. Rename it and retry."
    FileNameProblem.InvalidUnicode -> "File name contains invalid Unicode. Rename it and retry."
}

private fun catalogMessage(error: CatalogError?): String? = when (error?.kind) {
    CatalogErrorKind.Storage, CatalogErrorKind.Write -> "Could not save group files. Check storage space and retry."
    CatalogErrorKind.InvalidOperation -> "A group file change was rejected. Reconnect to a member to resync."
    CatalogErrorKind.Sync -> "Group files may be out of date. Reconnect to a member to resync, then retry."
    CatalogErrorKind.Share -> "This device may not be sharing a group file. Reopen AFM and retry."
    null -> null
}

class FilePresentationException(val userMessage: String) : Exception()

enum class DocumentCleanup { Deleted, Truncated, Failed, Untouched }

sealed interface ExportResult {
    data object Saved : ExportResult
    data class Failed(val message: String) : ExportResult
}

class GroupFilesOwner(
    private val catalogs: GroupCatalogs,
    private val mesh: MeshSession,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<Map<String, FilesState>>(emptyMap())
    val state: StateFlow<Map<String, FilesState>> = mutableState.asStateFlow()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val transfers = mutableMapOf<Pair<String, String>, Job>()
    private val corruptProviders = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    init {
        scope.launch {
            mesh.state.map { it.groups.map { group -> group.id to group.members.map { member -> member.id to member.name } } }
                .distinctUntilChanged().collectLatest { groups ->
                    val ids = groups.map { it.first }.toSet()
                    mutableState.update { current -> ids.associateWith { current[it] ?: FilesState() } }
                    synchronized(transfers) { transfers.filterKeys { it.first !in ids }.values.toList() }.forEach { it.cancel() }
                    synchronized(corruptProviders) { corruptProviders.keys.removeAll { it.first !in ids } }
                    for (id in ids) launch {
                        catalogs.entries(id).collect { entries ->
                            val live = entries.map { it.id.rowKey() }.toSet()
                            val removed = synchronized(transfers) {
                                transfers.filterKeys { it.first == id && it.second !in live }.values.toList()
                            }
                            removed.forEach { it.cancel() }
                            update(id) { it.copy(transfers = it.transfers.filterKeys { key -> key in live }) }
                            refresh(id)
                        }
                    }
                }
        }
        scope.launch { mesh.files.collect { files -> if (files != null) mutableState.value.keys.forEach { refresh(it) } } }
        scope.launch {
            combine(mesh.state, catalogs.errors) { state, errors -> state.groups.map { it.id } to errors }
                .collect { (ids, errors) ->
                    for (id in ids) update(id) { it.copy(catalogProblem = catalogMessage(errors[id] ?: errors["*"])) }
                }
        }
    }

    suspend fun refresh(meshId: String) {
        refreshLocks.computeIfAbsent(meshId) { Mutex() }.withLock {
            val files = mesh.files.value
            while (true) {
                val group = mesh.state.value.groups.firstOrNull { it.id == meshId } ?: break
                val snapshot = catalogs.entries(meshId).value
                val entries = snapshot.map { entry ->
                    val member = group.members.firstOrNull { it.id == entry.author }
                    val author = when {
                        member == null -> entry.author.take(12)
                        group.members.count { it.name == member.name } > 1 -> "${member.name} (${member.id.take(12)})"
                        else -> member.name
                    }
                    val local = try { files?.hasBlob(entry.hash) == true }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                    FileRow(entry.id.rowKey(), entry.name, entry.size, author, local)
                }
                if (snapshot == catalogs.entries(meshId).value) {
                    update(meshId) { it.copy(entries = entries) }
                    break
                }
            }
        }
    }

    fun addFile(meshId: String, name: String, import: suspend (MeshFiles) -> ImportedBlob) {
        catalogs.validateFileName(name)?.let { problem ->
            update(meshId) { it.copy(message = fileNameMessage(problem)) }
            return
        }
        if (!beginAction(meshId, importing = true)) {
            if (state.value[meshId]?.busy == true) {
                update(meshId) { it.copy(message = "Another file action is in progress. Wait for it to finish, then try again.") }
            }
            return
        }
        scope.launch {
            try {
                val files = checkNotNull(mesh.files.value) { "Node is closed" }
                when (val result = catalogs.addFile(meshId, name, import(files))) {
                    is AddFileResult.Added -> update(meshId) { it.copy(message = "File added to group.") }
                    is AddFileResult.Rejected -> update(meshId) { it.copy(message = fileNameMessage(result.problem)) }
                    is AddFileResult.Failed -> update(meshId) { it.copy(message = "Could not add file. Check group storage and retry.") }
                }
                refresh(meshId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MeshNodeException) {
                update(meshId) { it.copy(message = "Import failed: ${fileFailureMessage(failure.failure)}") }
            } catch (failure: Exception) {
                update(meshId) { it.copy(message = if (mesh.files.value == null)
                    "AFM's node stopped. Restart AFM." else "Could not import file. Check the selected file and available storage, then retry.") }
            } finally {
                update(meshId) { it.copy(busy = false, importing = false) }
            }
        }
    }

    fun remove(meshId: String, entryId: String) {
        val target = catalogs.entries(meshId).value.firstOrNull { it.id.rowKey() == entryId }?.id ?: return
        if (!beginAction(meshId, importing = false)) return
        scope.launch {
            try {
                when (catalogs.remove(meshId, target)) {
                    RemoveResult.Removed -> update(meshId) { it.copy(message = "Entry removed. Downloaded copies remain on members' devices.") }
                    is RemoveResult.Failed -> update(meshId) { it.copy(message = "Could not remove this entry. Check the group and try again.") }
                }
                refresh(meshId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update(meshId) { it.copy(message = "Could not remove this entry. Check the group and try again.") }
            } finally {
                update(meshId) { it.copy(busy = false) }
            }
        }
    }

    fun clearMessage(meshId: String) {
        catalogs.clearError(meshId)
        catalogs.clearError("*")
        update(meshId) { it.copy(message = null, exportMessage = null) }
    }

    fun download(meshId: String, entryId: String) {
        val key = meshId to entryId
        val previous = synchronized(transfers) { transfers[key] }
        if (previous?.isActive == true) {
            val terminal = state.value[meshId]?.transfers?.get(entryId)
            if (terminal == FileTransfer.Cancelled || terminal == FileTransfer.SourceUnavailable || terminal is FileTransfer.Failed) {
                scope.launch { previous.join(); download(meshId, entryId) }
            }
            return
        }
        val entry = catalogs.entries(meshId).value.firstOrNull { it.id.rowKey() == entryId } ?: return
        updateTransfer(meshId, entryId, FileTransfer.Queued)
        val job = scope.launch {
            try {
                val files = mesh.files.value ?: throw MeshNodeException(MeshFailure.NodeClosed)
                val group = mesh.state.value.groups.firstOrNull { it.id == meshId }
                    ?: throw MeshNodeException(MeshFailure.NotMember)
                val online = group.members.filter { it.online && it.id != mesh.state.value.nodeId }
                val preferred = online.filter { it.id == entry.author } + online.filter { it.id != entry.author }
                val excluded = synchronized(corruptProviders) { corruptProviders[key]?.toSet() ?: emptySet() }
                val clean = preferred.filter { it.id !in excluded }
                val providers = clean.ifEmpty { preferred }
                if (providers.isEmpty()) {
                    updateTransfer(meshId, entryId, FileTransfer.SourceUnavailable)
                    return@launch
                }
                val corruptThisAttempt = mutableSetOf<String>()
                for ((index, provider) in providers.withIndex()) {
                    updateTransfer(meshId, entryId, FileTransfer.Queued)
                    try {
                        files.fetch(meshId, provider.id, entry.hash, entry.size,
                            onQueued = { updateTransfer(meshId, entryId, FileTransfer.Queued) },
                            onProgress = { received, total -> updateTransfer(meshId, entryId,
                                if (received >= total) FileTransfer.Verifying else FileTransfer.Transferring(received, total)) },
                        )
                        withContext(NonCancellable) {
                            if (catalogs.entries(meshId).value.any { it.hash == entry.hash }) {
                                catalogs.markObtained(meshId, entry.hash)
                                if (catalogs.entries(meshId).value.none { it.hash == entry.hash }) {
                                    val error = catalogs.errors.value[meshId]
                                    if (error?.kind == CatalogErrorKind.Share && error.reason == "Hash is not live in group") catalogs.clearError(meshId)
                                }
                            }
                            refresh(meshId)
                            if (catalogs.entries(meshId).value.any { it.id == entry.id }) {
                                updateTransfer(meshId, entryId, FileTransfer.Completed)
                            }
                        }
                        return@launch
                    } catch (failure: MeshNodeException) {
                        if (failure.failure == MeshFailure.Corrupt) {
                            corruptThisAttempt += provider.id
                            synchronized(corruptProviders) { corruptProviders.getOrPut(key) { mutableSetOf() }.add(provider.id) }
                        }
                        if (failure.failure in setOf(MeshFailure.Unavailable, MeshFailure.Timeout, MeshFailure.Interrupted, MeshFailure.Corrupt) && index < providers.lastIndex) continue
                        updateTransfer(meshId, entryId, when (failure.failure) {
                            MeshFailure.Unavailable -> FileTransfer.SourceUnavailable
                            MeshFailure.Cancelled -> FileTransfer.Cancelled
                            else -> FileTransfer.Failed(failure.failure, corruptThisAttempt.size == providers.size)
                        })
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                updateTransfer(meshId, entryId, FileTransfer.Cancelled)
                throw cancelled
            } catch (failure: MeshNodeException) {
                updateTransfer(meshId, entryId, FileTransfer.Failed(failure.failure))
            } catch (failure: Exception) {
                updateTransfer(meshId, entryId, FileTransfer.Failed(MeshFailure.Io))
            }
        }
        synchronized(transfers) { transfers[key] = job }
        job.invokeOnCompletion { synchronized(transfers) { if (transfers[key] == job) transfers.remove(key) } }
    }

    fun cancel(meshId: String, entryId: String) {
        synchronized(transfers) { transfers[meshId to entryId] }?.cancel()
    }

    suspend fun export(meshId: String, entryId: String, action: String = "Save",
        successMessage: String = "Saved to your chosen location.", destination: suspend (MeshFiles, CatalogEntry) -> Unit): ExportResult {
        fun failed(message: String): ExportResult.Failed {
            val result = ExportResult.Failed(message)
            update(meshId) { it.copy(exportMessage = "$action failed: $message") }
            return result
        }
        val entry = catalogs.entries(meshId).value.firstOrNull { it.id.rowKey() == entryId }
            ?: return failed("This group entry is no longer available. Choose it again.")
        val files = mesh.files.value ?: return failed("AFM's node stopped. Restart AFM.")
        return try {
            if (!files.hasBlob(entry.hash)) return failed("This copy is damaged or missing. Download it again.")
            destination(files, entry)
            update(meshId) { it.copy(exportMessage = successMessage) }
            ExportResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FilePresentationException) {
            failed(failure.userMessage)
        } catch (failure: MeshNodeException) {
            failed(if (failure.failure == MeshFailure.Missing || failure.failure == MeshFailure.Corrupt)
                "This copy is damaged or missing. Download it again." else fileFailureMessage(failure.failure))
        } catch (failure: Exception) {
            failed(fileFailureMessage(MeshFailure.Destination))
        }
    }

    fun reportCleanup(meshId: String, outcome: DocumentCleanup) {
        val note = when (outcome) {
            DocumentCleanup.Deleted -> return
            DocumentCleanup.Truncated -> "The provider kept an empty document; delete it if unwanted."
            DocumentCleanup.Failed -> "The incomplete document may remain; delete it manually."
            DocumentCleanup.Untouched -> "Your existing file was not changed."
        }
        update(meshId) { it.copy(exportMessage = listOfNotNull(it.exportMessage, note).joinToString(" ")) }
    }

    private fun updateTransfer(meshId: String, entryId: String, transfer: FileTransfer) {
        if (mesh.state.value.groups.none { it.id == meshId } ||
            catalogs.entries(meshId).value.none { it.id.rowKey() == entryId }) return
        update(meshId) { it.copy(transfers = it.transfers + (entryId to transfer)) }
    }

    private fun beginAction(meshId: String, importing: Boolean): Boolean {
        while (true) {
            val current = mutableState.value
            val previous = current[meshId] ?: return false
            if (previous.busy) return false
            if (mutableState.compareAndSet(current, current + (meshId to
                    previous.copy(busy = true, importing = importing, message = null)))) return true
        }
    }

    private fun update(meshId: String, change: (FilesState) -> FilesState) {
        mutableState.update { current ->
            val previous = current[meshId] ?: return@update current
            current + (meshId to change(previous))
        }
    }
}
