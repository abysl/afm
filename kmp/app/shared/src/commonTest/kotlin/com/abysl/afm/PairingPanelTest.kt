package com.abysl.afm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingPanelTest {
    @Test
    fun validatesSquareQrMatrix() {
        assertTrue(isValidQrMatrix(2, byteArrayOf(0, 1, 1, 0)))
        assertFalse(isValidQrMatrix(2, byteArrayOf(0, 1, 1)))
        assertFalse(isValidQrMatrix(0, byteArrayOf()))
    }

    @Test
    fun readsNativeModuleOrdering() {
        val modules = byteArrayOf(0, 1, 1, 0)

        assertFalse(qrMatrixModule(2, modules, 0, 0))
        assertTrue(qrMatrixModule(2, modules, 0, 1))
        assertTrue(qrMatrixModule(2, modules, 1, 0))
        assertFalse(qrMatrixModule(2, modules, 1, 1))
    }

    @Test
    fun rejectsInvalidMatrixCoordinates() {
        val modules = byteArrayOf(0, 1, 1, 0)

        assertFailsWith<IllegalArgumentException> { qrMatrixModule(2, modules, 2, 0) }
        assertFailsWith<IllegalArgumentException> { qrMatrixModule(2, modules, 0, -1) }
        assertEquals(false, qrMatrixModule(2, modules, 1, 1))
    }
}
