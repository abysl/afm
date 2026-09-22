package com.abysl.afm

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

suspend fun assertNativeStoreRoundTrip(directory: String) {
    val bytes = ByteArray(256) { it.toByte() }
    val emptyBytes = byteArrayOf()
    val (hash, emptyHash) = assertNotNull(openBlobStore(directory)).use { store ->
        assertFalse(store.has("0".repeat(64)))
        val hash = store.put(bytes)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(hash, store.put(bytes))
        assertTrue(store.has(hash))
        assertContentEquals(bytes, store.get(hash))
        val emptyHash = store.put(emptyBytes)
        assertEquals(emptyHash, store.put(emptyBytes))
        assertTrue(store.has(emptyHash))
        assertContentEquals(emptyBytes, store.get(emptyHash))
        hash to emptyHash
    }
    assertNotNull(openBlobStore(directory)).use { reopened ->
        assertTrue(reopened.has(hash))
        assertContentEquals(bytes, reopened.get(hash))
        assertTrue(reopened.has(emptyHash))
        assertContentEquals(emptyBytes, reopened.get(emptyHash))
    }
}
