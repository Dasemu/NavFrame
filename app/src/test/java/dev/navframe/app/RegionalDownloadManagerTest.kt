package dev.navframe.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class RegionalDownloadManagerTest {
    @Test fun onlyHttpsWithoutCredentialsOrFragments() {
        for (url in listOf("http://example.com/catalog.json", "https://user:pass@example.com/a", "https://example.com/a#fragment", "file:///tmp/catalog", "https://example.com:8080/a")) {
            assertThrows(IllegalArgumentException::class.java) { RegionalDownloadManager.validateAddress(url) }
        }
        assertEquals("github.com", RegionalDownloadManager.validateAddress("https://github.com/Dasemu/NavFrame/releases/download/v1/a.navframe").host)
    }
    @Test fun archiveMustMatchLengthAndHashBeforeImportCanCommit() {
        val bytes = "verified archive".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertArrayEquals(bytes, VerifiedArchiveStream(ByteArrayInputStream(bytes), bytes.size.toLong(), hash).readBytes())
        assertThrows(IllegalArgumentException::class.java) { VerifiedArchiveStream(ByteArrayInputStream(bytes), bytes.size.toLong() + 1, hash).readBytes() }
        assertThrows(IllegalArgumentException::class.java) { VerifiedArchiveStream(ByteArrayInputStream(bytes), bytes.size.toLong() - 1, hash).readBytes() }
        assertThrows(IllegalArgumentException::class.java) { VerifiedArchiveStream(ByteArrayInputStream(bytes), bytes.size.toLong(), "0".repeat(64)).readBytes() }
    }
}
