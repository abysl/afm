package com.abysl.afm

import blue.rae.spirit.sdk.MeshFiles
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogStore(
    private val directory: File,
    val mesh: String,
    private val writeCounter: ((File, Long) -> Unit)? = null,
    private val writeFrame: (RandomAccessFile, ByteArray) -> Unit = { file, bytes ->
        file.writeInt(bytes.size)
        file.write(bytes)
        file.writeInt(CRC32().apply { update(bytes) }.value.toInt())
    },
) {
    data class BatchResult(val accepted: Int, val rejected: List<String>)
    private val mutex = Mutex()
    private val operations = linkedMapOf<Triple<String, Long, Long>, CatalogOp>()
    private val loserProofs = linkedMapOf<Triple<String, Long, Long>, CatalogOp>()
    private val mutableEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val entries: StateFlow<List<CatalogEntry>> = mutableEntries.asStateFlow()
    private val log = File(directory, "ops.log")
    var storageError: String? = null
        private set
    var limitedEntries: Boolean = false
        private set

    init {
        require(mesh.matches(CatalogCodec.meshPattern))
        directory.listFiles()?.filter { it.name.endsWith(".tmp") &&
            listOf("seq-", "ops-", "obtained-").any(it.name::startsWith) }?.forEach { it.delete() }
        if (log.exists()) RandomAccessFile(log, "rw").use { file ->
            var end = 0L
            while (end < file.length()) {
                val length = if (end + 8 <= file.length()) {
                    file.seek(end)
                    file.readInt()
                } else -1
                val next = end + length + 8
                val valid = if (length in 1..262_144 && next <= file.length()) runCatching {
                    val bytes = ByteArray(length).also(file::readFully)
                    val crc = file.readInt()
                    require(CRC32().apply { update(bytes) }.value.toInt() == crc)
                    CatalogCodec.parse(bytes).also { require(it.mesh == mesh) }
                }.getOrNull() else null
                if (valid == null) {
                    if (hasFrameAfter(file, end + 1)) {
                        val aside = File(directory, "ops.log.corrupt-${System.currentTimeMillis()}")
                        check(log.renameTo(aside)) { "Could not isolate damaged catalog" }
                        operations.clear()
                        loserProofs.clear()
                        storageError = "Catalog log corruption; resync required"
                    } else {
                        file.setLength(end)
                        file.fd.sync()
                        storageError = "Discarded damaged catalog tail"
                    }
                    break
                }
                val key = Triple(valid.author, valid.generation, valid.seq)
                val prior = operations[key]
                if (prior == null) operations[key] = valid
                else if (prior != valid) {
                    if (compare(valid, prior) < 0) {
                        val priorLoser = loserProofs[key]
                        if (priorLoser == null || compare(prior, priorLoser) < 0) loserProofs[key] = prior
                        operations[key] = valid
                    } else {
                        val priorLoser = loserProofs[key]
                        if (priorLoser == null || compare(valid, priorLoser) < 0) loserProofs[key] = valid
                    }
                }
                end = next
            }
        }
        rebuild()
    }

    suspend fun accept(op: CatalogOp, files: MeshFiles): Boolean {
        val result = acceptBatch(listOf(op), files)
        require(result.rejected.isEmpty()) { result.rejected.first() }
        return result.accepted > 0
    }

    suspend fun acceptBatch(batch: List<CatalogOp>, files: MeshFiles): BatchResult {
        val checked = batch.map { op -> op to runCatching { CatalogCodec.valid(op, mesh, files) }.getOrDefault(false) }
        return mutex.withLock {
            val rejected = mutableListOf<String>()
            val pending = mutableListOf<CatalogOp>()
            val seen = operations.toMutableMap()
            val proofs = loserProofs.toMutableMap()
            var replacingWinner = false
            for ((op, valid) in checked) {
                if (!valid) { rejected += "Untrusted catalog operation"; continue }
                val key = Triple(op.author, op.generation, op.seq)
                val clock = CatalogClock(op.author, op.generation)
                val prior = seen[key]
                if (prior == op || proofs[key] == op) continue
                if (prior != null) {
                    rejected += "Catalog equivocation: ${op.author}/${op.generation}/${op.seq}"
                    if (compare(op, prior) < 0) {
                        val priorLoser = proofs[key]
                        if (priorLoser == null || compare(prior, priorLoser) < 0) proofs[key] = prior
                        seen[key] = op
                        replacingWinner = true
                        pending += op
                    } else {
                        val priorLoser = proofs[key]
                        if (priorLoser == null || compare(op, priorLoser) < 0) {
                            proofs[key] = op
                            replacingWinner = true
                            pending += op
                        }
                    }
                    continue
                }
                if (seen.keys.map { CatalogClock(it.first, it.second) }.distinct().size >= CatalogCodec.MAX_CATALOG_CLOCKS &&
                    seen.keys.none { it.first == op.author && it.second == op.generation }) {
                    rejected += "Catalog clock limit"
                    continue
                }
                val contiguous = seen.keys.count { it.first == clock.author && it.second == clock.generation }
                if (op.seq != contiguous + 1L || op.seq > CatalogCodec.MAX_SEQ_PER_CLOCK) {
                    rejected += "Catalog sequence outside contiguous bound"
                    continue
                }
                seen[key] = op
                pending += op
            }
            if (pending.isNotEmpty()) {
                if (replacingWinner) rewriteLog(seen.values + proofs.values) else appendFrames(pending)
                operations.clear()
                operations.putAll(seen)
                loserProofs.clear()
                loserProofs.putAll(proofs)
                rebuild()
            }
            BatchResult(pending.size, rejected)
        }
    }

    suspend fun own(author: String, generation: Long, body: CatalogBody, files: MeshFiles): CatalogOp = mutex.withLock {
        val counter = File(directory, "seq-$author-$generation")
        val previous = if (counter.exists()) counter.readText().toLong() else 0L
        val seq = maxOf(previous, operations.keys.filter { it.first == author && it.second == generation }.maxOfOrNull { it.third } ?: 0L) + 1
        require(seq <= CatalogCodec.MAX_SEQ_PER_CLOCK) { "Catalog sequence limit" }
        require(seq == (operations.keys.count { it.first == author && it.second == generation } + 1).toLong()) { "Resync damaged catalog before writing" }
        require(operations.keys.any { it.first == author && it.second == generation } ||
            operations.keys.map { CatalogClock(it.first, it.second) }.distinct().size < CatalogCodec.MAX_CATALOG_CLOCKS) { "Catalog clock limit" }
        val unsigned = CatalogOp(mesh, author, generation, seq, body, "")
        val op = unsigned.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(unsigned)))
        require(CatalogCodec.valid(op, mesh, files))
        appendFrames(listOf(op))
        operations[Triple(author, generation, seq)] = op
        rebuild()
        try {
            (writeCounter ?: ::atomicCounter)(counter, seq)
            if (storageError?.startsWith("Catalog counter update failed:") == true) storageError = null
        } catch (failure: Exception) { storageError = "Catalog counter update failed: ${failure.message}" }
        op
    }

    suspend fun snapshot(): List<CatalogOp> = mutex.withLock { operations.values.toList() + loserProofs.values.toList() }
    suspend fun vector(): Map<CatalogClock, Long> = mutex.withLock {
        operations.keys.groupBy { CatalogClock(it.first, it.second) }.mapValues { (_, keys) -> keys.maxOf { it.third } }
    }
    suspend fun contains(op: CatalogOp): Boolean = mutex.withLock { operations[Triple(op.author, op.generation, op.seq)] == op }

    private fun appendFrames(pending: List<CatalogOp>) {
        directory.mkdirs()
        val created = !log.exists()
        RandomAccessFile(log, "rw").use { file ->
            val end = file.length()
            try {
                file.seek(end)
                pending.forEach { writeFrame(file, CatalogCodec.record(it)) }
                file.fd.sync()
                if (created) syncDirectory()
                if (storageError != null && !storageError!!.startsWith("Catalog counter update failed:")) storageError = null
            } catch (failure: Exception) {
                file.setLength(end)
                file.fd.sync()
                throw failure
            }
        }
    }

    private fun rewriteLog(winners: Collection<CatalogOp>) {
        directory.mkdirs()
        val temp = File.createTempFile("ops-", ".tmp", directory)
        try {
            RandomAccessFile(temp, "rw").use { file ->
                winners.forEach { writeFrame(file, CatalogCodec.record(it)) }
                file.fd.sync()
            }
            atomicReplaceCatalogFile(temp, log)
            syncDirectory()
            if (storageError != null && !storageError!!.startsWith("Catalog counter update failed:")) storageError = null
        } finally { temp.delete() }
    }

    private fun rebuild() {
        val added = linkedMapOf<CatalogEntryId, CatalogOp>()
        val removed = mutableSetOf<CatalogEntryId>()
        operations.values.sortedWith(compareBy({ it.author }, { it.generation }, { it.seq })).forEach { op ->
            when (val body = op.body) {
                is CatalogBody.Add -> added[CatalogEntryId(op.author, op.generation, body.entry)] = op
                is CatalogBody.Remove -> removed += body.target
            }
        }
        val live = added.filterKeys { it !in removed }
        limitedEntries = live.size > CatalogCodec.MAX_LIVE_ENTRIES
        mutableEntries.value = live.entries.take(CatalogCodec.MAX_LIVE_ENTRIES).map { (id, op) ->
            val body = op.body as CatalogBody.Add
            CatalogEntry(id, body.name, body.hash, body.size, op.author)
        }
    }

    private fun compare(a: CatalogOp, b: CatalogOp): Int {
        val left = CatalogCodec.record(a)
        val right = CatalogCodec.record(b)
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 255) - (right[index].toInt() and 255)
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }

    private fun hasFrameAfter(file: RandomAccessFile, from: Long): Boolean {
        for (offset in from..(file.length() - 8).coerceAtLeast(from - 1)) {
            file.seek(offset)
            val size = file.readInt()
            if (size !in 1..262_144 || offset + size + 8 > file.length()) continue
            if (runCatching {
                val bytes = ByteArray(size).also(file::readFully)
                val checksum = file.readInt()
                CRC32().apply { update(bytes) }.value.toInt() == checksum && CatalogCodec.parse(bytes).mesh == mesh
            }.getOrDefault(false)) return true
        }
        return false
    }

    private fun atomicCounter(file: File, seq: Long) {
        directory.mkdirs()
        val temp = File.createTempFile("seq-", ".tmp", directory)
        try {
            RandomAccessFile(temp, "rw").use { output ->
                output.write(seq.toString().encodeToByteArray())
                output.fd.sync()
            }
            atomicReplaceCatalogFile(temp, file)
            syncDirectory()
        } finally { temp.delete() }
    }

    private fun syncDirectory() {
        syncCatalogDirectory(directory)
    }
}

internal expect fun syncCatalogDirectory(directory: File)
internal expect fun atomicReplaceCatalogFile(source: File, destination: File)
