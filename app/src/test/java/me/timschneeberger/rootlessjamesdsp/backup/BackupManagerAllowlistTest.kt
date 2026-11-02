package me.timschneeberger.rootlessjamesdsp.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for BUG-04: the restore allowlist must match exactly the
 * layout createBackup() produces and must not admit traversal, absolute paths
 * or lookalike prefixes.
 */
class BackupManagerAllowlistTest {

    @Test
    fun `valid backup entries accepted`() {
        assertTrue(BackupManager.isKnownFile("shared_prefs/dsp_test.xml"))
        assertTrue(BackupManager.isKnownFile("profiles/phone.xml"))
        assertTrue(BackupManager.isKnownFile("profiles/dev/phone/p.xml"))
        assertTrue(BackupManager.isKnownFile("Convolver/ir.irs"))
        assertTrue(BackupManager.isKnownFile("Convolver/track.wav"))
        assertTrue(BackupManager.isKnownFile("Liveprog/script.eel"))
        assertTrue(BackupManager.isKnownFile("DDC/ddc.vdc"))
        assertTrue(BackupManager.isKnownFile("Presets/preset.tar"))
    }

    @Test
    fun `traversal rejected under every root`() {
        assertFalse(BackupManager.isKnownFile("profiles/../../shared_prefs/dsp_x.xml"))
        assertFalse(BackupManager.isKnownFile("shared_prefs/../dsp_x.xml"))
        assertFalse(BackupManager.isKnownFile("Convolver/../../escape.irs"))
        assertFalse(BackupManager.isKnownFile("../shared_prefs/dsp_x.xml"))
    }

    @Test
    fun `backslash traversal rejected`() {
        assertFalse(BackupManager.isKnownFile("profiles\\..\\..\\escape.xml"))
        assertFalse(BackupManager.isKnownFile("..\\..\\escape.xml"))
    }

    @Test
    fun `absolute paths rejected`() {
        assertFalse(BackupManager.isKnownFile("/shared_prefs/dsp_x.xml"))
        assertFalse(BackupManager.isKnownFile("C:/shared_prefs/dsp_x.xml"))
    }

    @Test
    fun `lookalike prefixes rejected`() {
        assertFalse(BackupManager.isKnownFile("xdsp_x.xml"))                 // no shared_prefs/ root
        assertFalse(BackupManager.isKnownFile("myshared_prefs/dsp_x.xml"))
        assertFalse(BackupManager.isKnownFile("profilesX/p.xml"))
        assertFalse(BackupManager.isKnownFile("ConvolverX/ir.irs"))
        assertFalse(BackupManager.isKnownFile("LiveprogX/x.eel"))
    }

    @Test
    fun `wrong extensions rejected`() {
        assertFalse(BackupManager.isKnownFile("shared_prefs/dsp_x.txt"))
        assertFalse(BackupManager.isKnownFile("shared_prefs/x.xml"))        // missing dsp_ prefix
        assertFalse(BackupManager.isKnownFile("Convolver/ir.exe"))
        assertFalse(BackupManager.isKnownFile("Liveprog/script.txt"))
    }

    @Test
    fun `nested library files rejected`() {
        // Backup layout is <LibraryDir>/<file>; deeper nesting is not produced
        assertFalse(BackupManager.isKnownFile("Convolver/sub/ir.irs"))
        assertFalse(BackupManager.isKnownFile("Presets/sub/p.tar"))
    }

    @Test
    fun `metadata is not an extractable entry`() {
        assertFalse(BackupManager.isKnownFile("metadata"))
    }
}
