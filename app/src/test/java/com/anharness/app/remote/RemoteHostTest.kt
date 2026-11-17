package com.anharness.app.remote

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [T-remote-ssh] Pure config contract + manager CRUD with in-memory storage
 * seams (no Android prefs / no network). Mirrors OffloadPermissionForceAskTest's
 * override-injection pattern.
 */
class RemoteHostTest {

    // ── RemoteHostConfig pure contract ───────────────────────────────────

    @Test
    fun `valid host passes validation`() {
        assertNull(RemoteHostConfig.validate("build.example.com", 22, "deploy"))
        assertNull(RemoteHostConfig.validate("10.0.0.5", 2222, "root"))
    }

    @Test
    fun `blank or malformed host rejected`() {
        assertNotNull(RemoteHostConfig.validate("", 22, "u"))
        assertNotNull(RemoteHostConfig.validate("has space", 22, "u"))
        assertNotNull(RemoteHostConfig.validate("host;rm", 22, "u"))
        assertNotNull(RemoteHostConfig.validate("host|pipe", 22, "u"))
        assertNotNull(RemoteHostConfig.validate("h`cmd`", 22, "u"))
    }

    @Test
    fun `port range enforced`() {
        assertNotNull(RemoteHostConfig.validate("h", 0, "u"))
        assertNotNull(RemoteHostConfig.validate("h", 65536, "u"))
        assertNull(RemoteHostConfig.validate("h", 1, "u"))
        assertNull(RemoteHostConfig.validate("h", 65535, "u"))
    }

    @Test
    fun `username required and no whitespace`() {
        assertNotNull(RemoteHostConfig.validate("h", 22, ""))
        assertNotNull(RemoteHostConfig.validate("h", 22, "a b"))
    }

    @Test
    fun `timeout clamped to range`() {
        assertEquals(60, RemoteHostConfig.clampTimeout(null))
        assertEquals(1, RemoteHostConfig.clampTimeout(0))
        assertEquals(1, RemoteHostConfig.clampTimeout(-5))
        assertEquals(900, RemoteHostConfig.clampTimeout(5000))
        assertEquals(120, RemoteHostConfig.clampTimeout(120))
    }

    @Test
    fun `output truncation keeps head and tail`() {
        val short = "hello"
        assertEquals(short, RemoteHostConfig.truncateOutput(short))
        val long = "A".repeat(30_000) + "B".repeat(30_000)
        val trunc = RemoteHostConfig.truncateOutput(long)
        assertTrue(trunc.length <= RemoteHostConfig.MAX_OUTPUT_CHARS + 64)
        assertTrue(trunc.startsWith("A"))
        assertTrue(trunc.endsWith("B"))
        assertTrue(trunc.contains("omitted"))
    }

    @Test
    fun `blank command rejected`() {
        assertNull(RemoteHostConfig.buildCommand("   "))
        assertEquals("ls -la", RemoteHostConfig.buildCommand("  ls -la  "))
    }

    // ── RemoteHostManager CRUD with in-memory seams ──────────────────────

    private lateinit var manager: RemoteHostManager

    @Before
    fun setUp() {
        manager = RemoteHostManager(Application())
        manager.storeOverride = mutableMapOf()
        manager.secretOverride = mutableMapOf()
    }

    @Test
    fun `add stores host and secret separately`() {
        val host = manager.upsert(null, "build", "build.example.com", 22, "deploy",
            RemoteHostConfig.AuthMethod.PASSWORD, "s3cret")
        assertNotNull(host.id)
        assertEquals("build", host.label)
        assertEquals(1, manager.list().size)
        assertEquals("s3cret", manager.loadSecret(host.id))
        // The host record itself must NOT contain the secret.
        val record = manager.get(host.id)!!
        assertNull(record.pinnedFingerprint)
    }

    @Test
    fun `invalid host add throws`() {
        try {
            manager.upsert(null, "", "bad host", 22, "u", RemoteHostConfig.AuthMethod.PASSWORD, "x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("whitespace"))
        }
    }

    @Test
    fun `upsert updates existing and keeps pin`() {
        val host = manager.upsert(null, "a", "h1", 22, "u", RemoteHostConfig.AuthMethod.PASSWORD, "p1")
        manager.pinFingerprint(host.id, "FP1")
        val updated = manager.upsert(host.id, "b", "h2", 2222, "u2", RemoteHostConfig.AuthMethod.PASSWORD, null)
        assertEquals(host.id, updated.id)
        assertEquals("h2", updated.hostname)
        assertEquals("FP1", updated.pinnedFingerprint) // pin preserved
        assertEquals("p1", manager.loadSecret(host.id)) // secret kept when null
        assertEquals(1, manager.list().size)
    }

    @Test
    fun `delete removes host and secret`() {
        val host = manager.upsert(null, "a", "h", 22, "u", RemoteHostConfig.AuthMethod.PASSWORD, "p")
        manager.delete(host.id)
        assertTrue(manager.list().isEmpty())
        assertNull(manager.loadSecret(host.id))
    }

    @Test
    fun `pin and clear fingerprint`() {
        val host = manager.upsert(null, "a", "h", 22, "u", RemoteHostConfig.AuthMethod.PASSWORD, "p")
        assertNull(manager.get(host.id)!!.pinnedFingerprint)
        manager.pinFingerprint(host.id, "FP")
        assertEquals("FP", manager.get(host.id)!!.pinnedFingerprint)
        manager.clearPin(host.id)
        assertNull(manager.get(host.id)!!.pinnedFingerprint)
    }

    @Test
    fun `default label derived from user and host`() {
        val host = manager.upsert(null, "", "h.example", 22, "deploy", RemoteHostConfig.AuthMethod.PASSWORD, "p")
        assertEquals("deploy@h.example", host.label)
    }

    @Test
    fun `private key auth stored`() {
        val host = manager.upsert(null, "k", "h", 22, "u", RemoteHostConfig.AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----")
        assertEquals(RemoteHostConfig.AuthMethod.PRIVATE_KEY, host.authMethod)
        assertEquals("-----BEGIN KEY-----", manager.loadSecret(host.id))
    }
}