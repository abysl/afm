package com.abysl.afm

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedLogicWebTest {
    @Test
    fun browserReportsNativeStorageAsUnavailable() {
        assertNull(openBlobStore("browser-smoke"))
        assertTrue(getPlatform().name.isNotBlank())
    }
}
