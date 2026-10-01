package com.abysl.afm

import blue.rae.spirit.sdk.MeshFiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

@JvmInline
value class EntryId(val hex: String) {
    init { require(hex.matches(Regex("[0-9a-f]{32}"))) }

    companion object {
        private val random = SecureRandom()
        fun new(): EntryId = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }.let(::EntryId)
    }
}

sealed interface CatalogBody {
    val entry: EntryId
    data class Add(override val entry: EntryId, val name: String, val hash: String, val size: Long) : CatalogBody
    data class Remove(val target: CatalogEntryId) : CatalogBody { override val entry: EntryId get() = target.entry }
}

data class CatalogOp(
    val mesh: String,
    val author: String,
    val generation: Long,
    val seq: Long,
    val body: CatalogBody,
    val signature: String,
)

data class CatalogEntryId(val author: String, val generation: Long, val entry: EntryId)

data class CatalogEntry(val id: CatalogEntryId, val name: String, val hash: String, val size: Long, val author: String)

enum class FileNameProblem { Empty, TooLong, InvalidCharacter, InvalidUnicode }

fun validateFileName(name: String): FileNameProblem? {
    if (name.isEmpty()) return FileNameProblem.Empty
    if (name == "." || name == "..") return FileNameProblem.InvalidCharacter
    var index = 0
    while (index < name.length) {
        val char = name[index]
        if (Character.isHighSurrogate(char)) {
            if (index + 1 >= name.length || !Character.isLowSurrogate(name[index + 1])) return FileNameProblem.InvalidUnicode
        } else if (Character.isLowSurrogate(char)) return FileNameProblem.InvalidUnicode
        val point = Character.codePointAt(name, index)
        val category = Character.getType(point)
        if (point == '/'.code || point == '\\'.code || category == Character.CONTROL.toInt() ||
            category == Character.FORMAT.toInt() || category == Character.LINE_SEPARATOR.toInt() ||
            category == Character.PARAGRAPH_SEPARATOR.toInt()) return FileNameProblem.InvalidCharacter
        index += Character.charCount(point)
    }
    if (name.encodeToByteArray().size > 255) return FileNameProblem.TooLong
    return null
}

data class CatalogClock(val author: String, val generation: Long)

object CatalogCodec {
    const val domain = "afm/catalog/op/1"
    private val tag = "AFM-CATALOG-OP-1".encodeToByteArray()
    private val hashPattern = Regex("[0-9a-f]{64}")
    val authorPattern = Regex("[0-9a-f]{64}")
    val meshPattern = Regex("(?:mesh1_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]|[0-9a-f]{64})")
    const val MAX_SEQ_PER_CLOCK = 1024L
    const val MAX_LIVE_ENTRIES = 8192
    const val MAX_CATALOG_CLOCKS = 256
    const val MAX_LOSER_PROOFS_PER_KEY = 1

    fun signed(op: CatalogOp): ByteArray {
        require(op.mesh.matches(meshPattern) && op.author.matches(authorPattern))
        require(op.generation in 0..0xffff_ffffL && op.seq in 1..MAX_SEQ_PER_CLOCK)
        val body = op.body
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { writer ->
            writer.write(tag)
            writer.text(op.mesh)
            writer.text(op.author)
            writer.writeLong(op.generation)
            writer.writeLong(op.seq)
            writer.write(if (body is CatalogBody.Add) 1 else 3)
            writer.write(body.entry.hex.hexBytes())
            if (body is CatalogBody.Add) {
                require(validateFileName(body.name) == null)
                require(body.hash.matches(hashPattern) && body.size >= 0)
                writer.text(body.name)
                writer.write(body.hash.hexBytes())
                writer.writeLong(body.size)
            } else if (body is CatalogBody.Remove) {
                require(body.target.author.matches(authorPattern) && body.target.generation in 0..0xffff_ffffL)
                writer.text(body.target.author)
                writer.writeLong(body.target.generation)
            }
        }
        return out.toByteArray()
    }

    fun record(op: CatalogOp): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            val signed = signed(op)
            output.writeInt(signed.size)
            output.write(signed)
            output.text(op.signature)
        }
    }.toByteArray()

    fun parse(bytes: ByteArray): CatalogOp {
        require(bytes.size <= 262_144)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val signedSize = input.readInt()
        require(signedSize in tag.size..bytes.size - 8)
        val signed = ByteArray(signedSize).also(input::readFully)
        val signature = input.text()
        require(signature.length <= 256)
        require(signature.isNotEmpty() && input.available() == 0)
        val fields = DataInputStream(ByteArrayInputStream(signed))
        require(ByteArray(tag.size).also(fields::readFully).contentEquals(tag))
        val mesh = fields.text()
        val author = fields.text()
        val generation = fields.readLong()
        val seq = fields.readLong()
        val kind = fields.readUnsignedByte()
        val entry = EntryId(ByteArray(16).also(fields::readFully).toHex())
        val body = when (kind) {
            1 -> CatalogBody.Add(entry, fields.text(), ByteArray(32).also(fields::readFully).toHex(), fields.readLong())
            3 -> CatalogBody.Remove(CatalogEntryId(fields.text(), fields.readLong(), entry))
            else -> error("Unknown catalog operation")
        }
        require(fields.available() == 0)
        return CatalogOp(mesh, author, generation, seq, body, signature).also {
            require(signed(it).contentEquals(signed))
        }
    }

    suspend fun valid(op: CatalogOp, mesh: String, files: MeshFiles): Boolean {
        if (op.mesh != mesh) return false
        val bytes = runCatching { signed(op) }.getOrNull() ?: return false
        return files.verifyApp(op.author, domain, bytes, op.signature) &&
            files.admitted(mesh, op.author, op.generation)
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= 65535)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val length = readUnsignedShort()
        val bytes = ByteArray(length).also(::readFully)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (invalid: CharacterCodingException) { throw IllegalArgumentException("Invalid UTF-8", invalid) }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
