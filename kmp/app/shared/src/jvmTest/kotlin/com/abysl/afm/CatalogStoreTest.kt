package com.abysl.afm

import blue.rae.spirit.sdk.FakeMeshFiles
import java.nio.file.Files
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogStoreTest {
    private val mesh = "mesh1_${"A".repeat(43)}"
    private val hash = "a".repeat(64)
    private val id = EntryId("01".repeat(16))

    @Test
    fun signedBytesAreStableAndRoundTrip(): Unit = runBlocking {
        val files = FakeMeshFiles("a".repeat(64))
        files.admit(mesh, "a".repeat(64), 0)
        val op = unsigned(CatalogBody.Add(id, "é", hash, 12))
        val expected = "41464d2d434154414c4f472d4f502d310031" + mesh.encodeToByteArray().joinToString("") { "%02x".format(it) } + "0040" + "61".repeat(64) + "0000000000000000000000000000000101" + "01".repeat(16) + "0002c3a9" + "aa".repeat(32) + "000000000000000c"
        assertEquals(expected, CatalogCodec.signed(op).joinToString("") { "%02x".format(it) })
        val signed = op.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(op)))
        assertEquals(signed, CatalogCodec.parse(CatalogCodec.record(signed)))
        assertTrue(CatalogCodec.valid(signed, mesh, files))
        assertFailsWith<IllegalArgumentException> { CatalogCodec.signed(op.copy(body = CatalogBody.Add(id, "x/y", hash, 12))) }
        assertFailsWith<IllegalArgumentException> { CatalogCodec.signed(op.copy(body = CatalogBody.Add(id, "\u0000", hash, 12))) }
    }

    @Test
    fun checksSignatureMeshAdmissionHistoryAndEquivocation(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            val store = CatalogStore(directory, mesh)
            val add = unsigned(CatalogBody.Add(id, "file", hash, 4))
            val signed = add.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(add)))
            assertFalse(CatalogCodec.valid(signed, mesh, files))
            files.admit(mesh, "a".repeat(64), 0)
            assertFailsWith<IllegalArgumentException> { store.accept(signed.copy(signature = "wrong"), files) }
            assertFailsWith<IllegalArgumentException> { store.accept(signed.copy(mesh = "mesh1_${"B".repeat(42)}A"), files) }
            assertTrue(store.accept(signed, files))
            assertFalse(store.accept(signed, files))
            val conflicting = add.copy(body = CatalogBody.Add(id, "else", hash, 4))
            val outcome = store.acceptBatch(listOf(conflicting.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(conflicting)))), files)
            assertTrue(outcome.rejected.single().contains("equivocation"))
            assertEquals("else", store.entries.value.single().name)
            assertTrue(CatalogCodec.valid(signed, mesh, files))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun retainsEarlyRemoveAndDuplicateNamesAndRestartsSequence(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            files.admit(mesh, "a".repeat(64), 1)
            files.admit(mesh, "b".repeat(64), 0)
            val store = CatalogStore(directory, mesh)
            val removed = store.own("a".repeat(64), 0, CatalogBody.Remove(CatalogEntryId("a".repeat(64), 0, id)), files)
            assertEquals(1L, removed.seq)
            val duplicate = EntryId("02".repeat(16))
            val other = unsigned(CatalogBody.Add(duplicate, "same", hash, 6), "b".repeat(64))
            store.accept(other.copy(signature = FakeMeshFiles("b".repeat(64)).signApp(CatalogCodec.domain, CatalogCodec.signed(other))), files)
            store.own("a".repeat(64), 0, CatalogBody.Add(id, "same", hash, 5), files)
            assertEquals(listOf("b".repeat(64)), store.entries.value.map { it.author })
            val third = store.own("a".repeat(64), 0, CatalogBody.Add(EntryId("03".repeat(16)), "same", hash, 7), files)
            assertEquals(3L, third.seq)
            assertEquals(2, store.entries.value.size)
            val reopened = CatalogStore(directory, mesh)
            assertEquals(store.entries.value, reopened.entries.value)
            assertEquals(4L, reopened.own("a".repeat(64), 0, CatalogBody.Remove(CatalogEntryId("a".repeat(64), 0, third.body.entry)), files).seq)
            assertEquals(1L, reopened.own("a".repeat(64), 1, CatalogBody.Add(EntryId("04".repeat(16)), "new", hash, 8), files).seq)
            assertEquals(4L, reopened.vector()[CatalogClock("a".repeat(64), 0)])
            assertContentEquals(store.snapshot().map { it.seq }, reopened.snapshot().dropLast(2).map { it.seq })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun identicalRandomEntryIdsFromDifferentAuthorsRemainSeparate(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-collision-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            files.admit(mesh, "b".repeat(64), 0)
            val store = CatalogStore(directory, mesh)
            val a = unsigned(CatalogBody.Add(id, "a", hash, 1))
            val b = unsigned(CatalogBody.Add(id, "b", hash, 1), "b".repeat(64))
            store.accept(a.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(a))), files)
            store.accept(b.copy(signature = FakeMeshFiles("b".repeat(64)).signApp(CatalogCodec.domain, CatalogCodec.signed(b))), files)
            assertEquals(2, store.entries.value.size)
            store.own("a".repeat(64), 0, CatalogBody.Remove(CatalogEntryId("b".repeat(64), 0, id)), files)
            assertEquals("a", store.entries.value.single().name)
            assertEquals(store.entries.value, CatalogStore(directory, mesh).entries.value)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun rejectsGapsAndExcessiveSequence(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-gap-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            val store = CatalogStore(directory, mesh)
            val gap = unsigned(CatalogBody.Add(id, "file", hash, 4)).copy(seq = 2)
            val signed = gap.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(gap)))
            assertTrue(store.acceptBatch(listOf(signed), files).rejected.isNotEmpty())
            assertTrue(store.vector().isEmpty())
            assertFailsWith<IllegalArgumentException> { CatalogCodec.signed(gap.copy(seq = 1_000_000_000_000)) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun repairsTornTailsAndPreservesEarlierOperations(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-torn-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            val store = CatalogStore(directory, mesh)
            store.own("a".repeat(64), 0, CatalogBody.Add(id, "first", hash, 1), files)
            val log = directory.resolve("ops.log")
            val goodLength = log.length()
            RandomAccessFile(log, "rw").use { it.seek(goodLength); it.write(ByteArray(7)) }
            val zero = CatalogStore(directory, mesh)
            assertEquals(1, zero.entries.value.size)
            assertEquals(goodLength, log.length())
            val second = zero.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "second", hash, 1), files)
            RandomAccessFile(log, "rw").use { it.seek(log.length() - 1); it.writeByte(127) }
            val crc = CatalogStore(directory, mesh)
            assertEquals(1, crc.entries.value.size)
            assertEquals(goodLength, log.length())
            assertFailsWith<IllegalArgumentException> { crc.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "third", hash, 1), files) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun failedWriteRollsBackAndMidLogCorruptionIsIsolated(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-corrupt-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            val store = CatalogStore(directory, mesh)
            store.own("a".repeat(64), 0, CatalogBody.Add(id, "first", hash, 1), files)
            val log = directory.resolve("ops.log")
            val length = log.length()
            val failing = CatalogStore(directory, mesh) { file, bytes -> file.write(bytes); throw java.io.IOException("write interrupted") }
            assertFailsWith<java.io.IOException> { failing.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "second", hash, 1), files) }
            assertEquals(length, log.length())
            val good = CatalogStore(directory, mesh)
            good.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "second", hash, 1), files)
            RandomAccessFile(log, "rw").use { it.seek(4); it.writeByte(127) }
            val repaired = CatalogStore(directory, mesh)
            assertTrue(repaired.entries.value.isEmpty())
            assertTrue(directory.listFiles()!!.any { it.name.startsWith("ops.log.corrupt-") })
            assertFailsWith<IllegalArgumentException> { repaired.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "third", hash, 1), files) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun equivocationGrowthIsBoundedAndInterruptedRewriteKeepsOldLog(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-equivocation-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            val initial = unsigned(CatalogBody.Add(id, "z", hash, 1))
            val signed = initial.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(initial)))
            CatalogStore(directory, mesh).accept(signed, files)
            val log = directory.resolve("ops.log")
            val original = log.readBytes()
            val failing = CatalogStore(directory, mesh) { file, bytes ->
                file.write(bytes)
                throw java.io.IOException("Interrupted log rewrite")
            }
            val better = initial.copy(body = CatalogBody.Add(id, "y", hash, 1))
            val replacement = better.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(better)))
            assertFailsWith<java.io.IOException> { failing.acceptBatch(listOf(replacement), files) }
            assertContentEquals(original, log.readBytes())
            assertEquals("z", CatalogStore(directory, mesh).entries.value.single().name)
            val store = CatalogStore(directory, mesh)
            for (character in 'y' downTo 'a') {
                val candidate = initial.copy(body = CatalogBody.Add(id, character.toString(), hash, 1))
                store.acceptBatch(listOf(candidate.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(candidate)))), files)
                assertTrue(CatalogStore(directory, mesh).snapshot().size <= 2)
                assertTrue(log.length() < 1024)
            }
            assertEquals("a", store.entries.value.single().name)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun committedOperationSurvivesFailedCounterWrite(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-counter-").toFile()
        try {
            val files = FakeMeshFiles("a".repeat(64))
            files.admit(mesh, "a".repeat(64), 0)
            val broken = CatalogStore(directory, mesh, writeCounter = { _, _ -> throw java.io.IOException("counter failed") })
            assertEquals(1L, broken.own("a".repeat(64), 0, CatalogBody.Add(id, "retained", hash, 1), files).seq)
            assertTrue(broken.storageError!!.contains("counter failed"))
            val reopened = CatalogStore(directory, mesh)
            assertEquals("retained", reopened.entries.value.single().name)
            assertEquals(2L, reopened.own("a".repeat(64), 0, CatalogBody.Add(EntryId.new(), "next", hash, 1), files).seq)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun successfulStoreWritesClearOnlyTheirMatchingStorageError(): Unit = runBlocking {
        val directory = Files.createTempDirectory("catalog-recovered-").toFile()
        try {
            val author = "a".repeat(64)
            val files = FakeMeshFiles(author).also { it.admit(mesh, author, 0) }
            var failCounter = true
            val store = CatalogStore(directory, mesh, writeCounter = { file, seq ->
                if (failCounter) throw java.io.IOException("counter failed")
                file.writeText(seq.toString())
            })
            store.own(author, 0, CatalogBody.Add(id, "first", hash, 1), files)
            assertTrue(store.storageError!!.contains("counter failed"))
            val second = unsigned(CatalogBody.Add(EntryId.new(), "peer", hash, 1), "b".repeat(64))
            files.admit(mesh, second.author, 0)
            store.accept(second.copy(signature = FakeMeshFiles(second.author).signApp(CatalogCodec.domain, CatalogCodec.signed(second))), files)
            assertTrue(store.storageError!!.contains("counter failed"))
            failCounter = false
            store.own(author, 0, CatalogBody.Add(EntryId.new(), "third", hash, 1), files)
            assertEquals(null, store.storageError)
            val log = directory.resolve("ops.log")
            RandomAccessFile(log, "rw").use { it.seek(log.length()); it.write(ByteArray(7)) }
            val recovered = CatalogStore(directory, mesh)
            assertTrue(recovered.storageError!!.contains("damaged catalog tail"))
            recovered.own(author, 0, CatalogBody.Add(EntryId.new(), "fourth", hash, 1), files)
            assertEquals(null, recovered.storageError)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cleansInterruptedTemporaryFilesAtOpen() {
        val directory = Files.createTempDirectory("catalog-tmp-").toFile()
        try {
            listOf("seq-abandoned.tmp", "ops-abandoned.tmp", "obtained-abandoned.tmp").forEach { directory.resolve(it).writeText("partial") }
            CatalogStore(directory, mesh)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun rejectsUnsafeNamesAndAuthorAndMeshAliases() {
        assertEquals(FileNameProblem.InvalidUnicode, validateFileName("a\uD800"))
        listOf(".", "..", "x/y", "x\\y", "x\u202e", "x\u0000", "x\u2028", "x\u2029", "x\uDB40\uDC01", "x\uDB40\uDC20").forEach { assertEquals(FileNameProblem.InvalidCharacter, validateFileName(it)) }
        val op = unsigned(CatalogBody.Add(id, "ok", hash, 1))
        assertFailsWith<IllegalArgumentException> { CatalogCodec.signed(op.copy(author = "A".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { CatalogCodec.signed(op.copy(mesh = "mesh1_test")) }
    }

    private fun unsigned(body: CatalogBody, author: String = "a".repeat(64)) = CatalogOp(mesh, author, 0, 1, body, "")
}
