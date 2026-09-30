package com.abysl.afm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object CatalogWire {
    const val protocol = "afm/catalog/1"
    const val limit = 262_144
    const val MAX_OPS_PER_BATCH = 64
    const val MAX_OPS_PER_RESPONSE = 64
    private const val batchLimit = 190_000
    private val tag = "AFM-CATALOG-SYNC-3".encodeToByteArray()

    data class Message(
        val vector: Map<CatalogClock, Long>,
        val ops: List<CatalogOp>,
        val more: Boolean = false,
        val digests: Map<CatalogClock, String> = emptyMap(),
        val malformed: Int = 0,
        val cursor: Int = 0,
    )

    fun digest(op: CatalogOp): String = MessageDigest.getInstance("SHA-256").digest(CatalogCodec.record(op))
        .joinToString("") { "%02x".format(it) }

    fun digests(ops: List<CatalogOp>): Map<CatalogClock, String> = ops.groupBy { CatalogClock(it.author, it.generation) }
        .mapValues { (_, values) ->
            val hash = MessageDigest.getInstance("SHA-256")
            values.sortedWith(compareBy<CatalogOp> { it.seq }.thenComparator(::compareRecords)).forEach { op ->
                val record = CatalogCodec.record(op)
                hash.update(byteArrayOf((record.size ushr 24).toByte(), (record.size ushr 16).toByte(),
                    (record.size ushr 8).toByte(), record.size.toByte()))
                hash.update(record)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }

    fun encode(message: Message, reply: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(tag)
            writer.writeByte(if (reply) 2 else 1)
            writer.writeBoolean(message.more)
            require(message.cursor in 0..(2 * CatalogCodec.MAX_CATALOG_CLOCKS * CatalogCodec.MAX_SEQ_PER_CLOCK).toInt())
            writer.writeInt(message.cursor)
            require(message.vector.size <= CatalogCodec.MAX_CATALOG_CLOCKS)
            writer.writeShort(message.vector.size)
            message.vector.entries.sortedWith(compareBy({ it.key.author }, { it.key.generation })).forEach { (clock, seq) ->
                require(clock.author.matches(CatalogCodec.authorPattern) && clock.generation in 0..0xffff_ffffL && seq in 0..CatalogCodec.MAX_SEQ_PER_CLOCK)
                writer.text(clock.author)
                writer.writeLong(clock.generation)
                writer.writeLong(seq)
                writer.write((message.digests[clock] ?: "0".repeat(64)).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            require(message.ops.size <= if (reply) MAX_OPS_PER_RESPONSE else MAX_OPS_PER_BATCH)
            writer.writeShort(message.ops.size)
            message.ops.forEach { op ->
                val bytes = CatalogCodec.record(op)
                writer.writeInt(bytes.size)
                writer.write(bytes)
            }
        }
        return output.toByteArray().also { require(it.size <= limit) }
    }

    fun decode(bytes: ByteArray, reply: Boolean): Message {
        require(bytes.size <= limit)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(ByteArray(tag.size).also(input::readFully).contentEquals(tag))
        require(input.readUnsignedByte() == if (reply) 2 else 1)
        val more = input.readBoolean()
        val cursor = input.readInt().also { require(it in 0..(2 * CatalogCodec.MAX_CATALOG_CLOCKS * CatalogCodec.MAX_SEQ_PER_CLOCK).toInt()) }
        val vector = linkedMapOf<CatalogClock, Long>()
        val digests = linkedMapOf<CatalogClock, String>()
        repeat(input.readUnsignedShort().also { require(it <= CatalogCodec.MAX_CATALOG_CLOCKS) }) {
            val clock = CatalogClock(input.text(), input.readLong())
            val seq = input.readLong()
            require(clock.author.matches(CatalogCodec.authorPattern) && clock.generation in 0..0xffff_ffffL && seq in 0..CatalogCodec.MAX_SEQ_PER_CLOCK)
            require(vector.putIfAbsent(clock, seq) == null)
            digests[clock] = ByteArray(32).also(input::readFully).joinToString("") { "%02x".format(it) }
        }
        val ops = ArrayList<CatalogOp>()
        var malformed = 0
        repeat(input.readUnsignedShort().also { require(it <= if (reply) MAX_OPS_PER_RESPONSE else MAX_OPS_PER_BATCH) }) {
            val size = input.readInt()
            require(size in 1..limit && size <= input.available())
            val record = ByteArray(size).also(input::readFully)
            val op = runCatching { CatalogCodec.parse(record) }.getOrNull()
            if (op == null) malformed++ else ops.add(op)
        }
        require(input.available() == 0)
        return Message(vector, ops, more, digests, malformed, cursor)
    }

    fun missing(
        ops: List<CatalogOp>, vector: Map<CatalogClock, Long>, budget: Int = batchLimit,
        digests: Map<CatalogClock, String> = emptyMap(), maxOps: Int = MAX_OPS_PER_BATCH, skip: Int = 0,
    ): Pair<List<CatalogOp>, Boolean> {
        require(skip >= 0)
        val localDigests = digests(ops)
        val absent = ops.filter { op ->
            val clock = CatalogClock(op.author, op.generation)
            op.seq > (vector[clock] ?: 0L) || ((vector[clock] ?: 0L) > 0 && digests[clock] != localDigests[clock])
        }.sortedWith(compareBy<CatalogOp>({ it.author }, { it.generation }, { it.seq }).thenComparator(::compareRecords))
        val batch = ArrayList<CatalogOp>()
        var remaining = budget
        for (op in absent.drop(skip)) {
            val size = CatalogCodec.record(op).size + 4
            require(size <= budget)
            if (size > remaining || batch.size == maxOps) break
            batch.add(op)
            remaining -= size
        }
        return batch to (skip + batch.size < absent.size)
    }

    private fun compareRecords(a: CatalogOp, b: CatalogOp): Int {
        val left = CatalogCodec.record(a)
        val right = CatalogCodec.record(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val diff = (left[i].toInt() and 255) - (right[i].toInt() and 255)
            if (diff != 0) return diff
        }
        return left.size.compareTo(right.size)
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size in 1..255)
        writeByte(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readUnsignedByte()
        require(size in 1..255)
        return ByteArray(size).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }
}
