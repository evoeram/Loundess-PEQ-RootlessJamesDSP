package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * Regression tests for BUG-04: tar extraction must reject path traversal,
 * absolute paths, symlink-like entries and oversized inputs, and the
 * metadata entry must never be written to disk.
 */
class TarReaderSecurityTest {

    // ---------- validateEntryName ----------

    @Test
    fun `valid backup entry names accepted`() {
        val reader = Tar.Reader(ByteArrayInputStream(ByteArray(0)))
        assertEquals("shared_prefs/dsp_test.xml", reader.validateEntryName("shared_prefs/dsp_test.xml"))
        assertEquals("profiles/phone/p.xml", reader.validateEntryName("profiles/phone/p.xml"))
        assertEquals("Convolver/ir.irs", reader.validateEntryName("Convolver/ir.irs"))
    }

    @Test
    fun `parent traversal rejected`() {
        val reader = Tar.Reader(ByteArrayInputStream(ByteArray(0)))
        listOf(
            "../escape.txt",
            "shared_prefs/../../escape.txt",
            "profiles/../../../data/data/evil.xml",
            "..",
            "a/./b",
        ).forEach { name ->
            try {
                reader.validateEntryName(name)
                throw AssertionError("Expected rejection: $name")
            } catch (expected: Tar.Reader.ExtractionException) {
            }
        }
    }

    @Test
    fun `backslash traversal rejected`() {
        val reader = Tar.Reader(ByteArrayInputStream(ByteArray(0)))
        listOf("..\\..\\escape.txt", "profiles\\..\\..\\escape.txt").forEach { name ->
            try {
                reader.validateEntryName(name)
                throw AssertionError("Expected rejection: $name")
            } catch (expected: Tar.Reader.ExtractionException) {
            }
        }
    }

    @Test
    fun `absolute and drive paths rejected`() {
        val reader = Tar.Reader(ByteArrayInputStream(ByteArray(0)))
        listOf("/etc/passwd", "C:/Windows/x", "C:\\Windows\\x", "~/x").forEach { name ->
            try {
                reader.validateEntryName(name)
                throw AssertionError("Expected rejection: $name")
            } catch (expected: Tar.Reader.ExtractionException) {
            }
        }
    }

    @Test
    fun `malformed names rejected`() {
        val reader = Tar.Reader(ByteArrayInputStream(ByteArray(0)))
        listOf("", "a//b", "a/\u0000b").forEach { name ->
            try {
                reader.validateEntryName(name)
                throw AssertionError("Expected rejection: ${name.escape()}")
            } catch (expected: Tar.Reader.ExtractionException) {
            }
        }
    }

    // ---------- extract ----------

    @Test
    fun `valid archive extracts with metadata kept off disk`() {
        val tar = buildTar(
            TarFile("shared_prefs/dsp_test.xml", "payload".toByteArray()),
            TarFile("metadata", "min_version_code=1\nflavor=full\n".toByteArray()),
        )
        val target = Files.createTempDirectory("tar-valid").toFile()
        try {
            val metadata = Tar.Reader(ByteArrayInputStream(tar)).extract(target)
            assertNotNull(metadata)
            assertEquals("1", metadata!!["min_version_code"])
            assertEquals("full", metadata["flavor"])
            assertArrayEquals("payload".toByteArray(), target.resolve("shared_prefs/dsp_test.xml").readBytes())
            // metadata is collected in memory, never materialized as a file
            assertFalse(target.resolve("metadata").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `traversal entry fails extraction and escapes nothing`() {
        val parent = Files.createTempDirectory("tar-attack").toFile()
        val target = parent.resolve("restore")
        val tar = buildTar(
            TarFile("../escape.txt", "owned".toByteArray()),
            TarFile("shared_prefs/dsp_ok.xml", "ok".toByteArray()),
        )
        try {
            val metadata = Tar.Reader(ByteArrayInputStream(tar)).extract(target)
            assertNull(metadata)
            assertFalse(parent.resolve("escape.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `nested traversal entry fails extraction`() {
        val parent = Files.createTempDirectory("tar-attack-nested").toFile()
        val target = parent.resolve("restore")
        val tar = buildTar(TarFile("profiles/../../escape.txt", "owned".toByteArray()))
        try {
            assertNull(Tar.Reader(ByteArrayInputStream(tar)).extract(target))
            assertFalse(parent.resolve("escape.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `backslash traversal entry fails extraction`() {
        val parent = Files.createTempDirectory("tar-attack-backslash").toFile()
        val target = parent.resolve("restore")
        val tar = buildTar(TarFile("..\\..\\escape.txt", "owned".toByteArray()))
        try {
            assertNull(Tar.Reader(ByteArrayInputStream(tar)).extract(target))
            assertFalse(parent.resolve("escape.txt").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `symlink-like entry is rejected`() {
        val target = Files.createTempDirectory("tar-symlink").toFile()
        val tar = buildTar(
            TarFile("link.lnk", ByteArray(0), linkFlag = '2'.code.toByte()),
        )
        try {
            assertNull(Tar.Reader(ByteArrayInputStream(tar)).extract(target))
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `directory entry materializes only the directory`() {
        val target = Files.createTempDirectory("tar-dir").toFile()
        val tar = buildTar(
            TarFile("profiles/", ByteArray(0), linkFlag = '5'.code.toByte()),
            TarFile("profiles/p.xml", "x".toByteArray()),
        )
        try {
            val metadata = Tar.Reader(ByteArrayInputStream(tar)).extract(target)
            assertNotNull(metadata)
            assertTrue(target.resolve("profiles").isDirectory)
            assertTrue(target.resolve("profiles/p.xml").isFile)
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `per-entry size limit stops oversized files`() {
        val target = Files.createTempDirectory("tar-size").toFile()
        val tar = buildTar(TarFile("shared_prefs/dsp_big.xml", ByteArray(100)))
        try {
            val reader = Tar.Reader(
                ByteArrayInputStream(tar),
                maxEntryBytes = 16,
            )
            assertNull(reader.extract(target))
            assertFalse(target.resolve("shared_prefs/dsp_big.xml").exists())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `entry count limit stops archive bombs`() {
        val target = Files.createTempDirectory("tar-count").toFile()
        val tar = buildTar(
            TarFile("shared_prefs/a.xml", "1".toByteArray()),
            TarFile("shared_prefs/b.xml", "2".toByteArray()),
        )
        try {
            val reader = Tar.Reader(
                ByteArrayInputStream(tar),
                maxEntries = 1,
            )
            assertNull(reader.extract(target))
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `metadata size limit stops oversized metadata`() {
        val target = Files.createTempDirectory("tar-meta").toFile()
        val tar = buildTar(
            TarFile("metadata", ByteArray(4096)),
            TarFile("shared_prefs/a.xml", "1".toByteArray()),
        )
        try {
            val reader = Tar.Reader(
                ByteArrayInputStream(tar),
                maxMetadataBytes = 64,
            )
            assertNull(reader.extract(target))
        } finally {
            target.deleteRecursively()
        }
    }

    // ---------- helpers ----------

    private data class TarFile(val name: String, val data: ByteArray, val linkFlag: Byte = '0'.code.toByte())

    /** Builds a minimal POSIX tar archive (512-byte headers, octal fields). */
    private fun buildTar(vararg files: TarFile): ByteArray {
        val out = ByteArrayOutputStream()
        for (file in files) {
            val header = ByteArray(512)
            val nameBytes = file.name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            writeOctal(header, 100, 8, 0x1A4)   // mode 644
            writeOctal(header, 108, 8, 0)       // uid
            writeOctal(header, 116, 8, 0)       // gid
            writeOctal(header, 124, 12, file.data.size.toLong())
            writeOctal(header, 136, 12, 0)      // mtime
            header.fill(' '.code.toByte(), 148, 156)
            header[156] = file.linkFlag
            header.fill(0, 157, 512)
            var checksum = 0
            for (byte in header) checksum += byte.toInt() and 0xFF
            val chk = checksum.toString(8).padStart(6, '0') + "\u0000 "
            System.arraycopy(chk.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
            out.write(header)
            out.write(file.data)
            val padding = (512 - (file.data.size % 512)) % 512
            out.write(ByteArray(padding))
        }
        out.write(ByteArray(1024)) // end-of-archive zero blocks
        return out.toByteArray()
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val field = value.toString(8).padStart(length - 1, '0') + "\u0000"
        val bytes = field.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, header, offset, length)
    }

    private fun String.escape() = replace("\u0000", "<NUL>")
}
