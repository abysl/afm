package com.abysl.afm

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class NativeStoreAndroidTest {
    @Test
    fun nativeStorePersistsBinaryBytes() {
        runBlocking {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val directory = File(cache, "afm-native-${UUID.randomUUID()}")
            try {
                assertNativeStoreRoundTrip(directory.path)
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
