package com.abysl.afm

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

class BlobStoreDesktopTest {
    @Test
    fun nativeStorePersistsBinaryBytes() {
        runBlocking {
            val directory = Files.createTempDirectory("afm-native-").toFile()
            try {
                assertNativeStoreRoundTrip(directory.path)
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
