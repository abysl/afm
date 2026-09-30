package com.abysl.afm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenCachePathsTest {
    @Test
    fun everyOpenUsesAnUnrelatedCacheDirectoryAndValidatedName() {
        val cache = File("cache")
        val first = openCacheFile(cache, "12345678-1234-1234-1234-123456789abc", "report.pdf")
        val second = openCacheFile(cache, "abcdefab-1234-1234-1234-123456789abc", "report.pdf")
        assertEquals(File(cache, "afm-open/12345678-1234-1234-1234-123456789abc/report.pdf"), first)
        assertEquals(openCacheDirectory(cache), first.parentFile?.parentFile)
        assertTrue(first.parentFile != second.parentFile)
        assertFailsWith<IllegalArgumentException> { openCacheFile(cache, "12345678-1234-1234-1234-123456789abc", "../secret") }
    }

    @Test
    fun providerIsRestrictedToOpenCacheWithTemporaryReadGrants() {
        val directory = File("src/main").takeIf { it.isDirectory } ?: File("app/androidApp/src/main")
        val paths = File(directory, "res/xml/shared_files.xml").readText()
        val manifest = File(directory, "AndroidManifest.xml").readText()
        assertTrue(paths.contains("<cache-path name=\"open\" path=\"afm-open/\"/>"))
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
        assertTrue(manifest.contains("android:name=\"androidx.core.content.FileProvider\""))
        assertEquals("com.abysl.afm.files", fileProviderAuthority("com.abysl.afm"))
    }
}
