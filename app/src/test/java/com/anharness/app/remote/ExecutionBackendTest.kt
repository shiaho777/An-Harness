package com.anharness.app.remote

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-remote-backend] Routing decision + per-session binding contract. Pure
 * JVM with an in-memory binding seam (no Android prefs / no network).
 */
class ExecutionBackendTest {

    // ── pure routing ─────────────────────────────────────────────────────

    @Test
    fun `unbound session routes shell local`() {
        assertEquals(ExecutionBackend.Backend.LOCAL, ExecutionBackend.route(null, "shell_execute"))
    }

    @Test
    fun `bound session routes shell remote`() {
        assertEquals(ExecutionBackend.Backend.REMOTE, ExecutionBackend.route("host-1", "shell_execute"))
    }

    @Test
    fun `non-shell tools stay local even when bound`() {
        for (tool in listOf("file_read", "file_write", "browser_use", "phone_use", "read_image", "memory_get", "spawn_subagent")) {
            assertEquals(
                "$tool must stay local",
                ExecutionBackend.Backend.LOCAL,
                ExecutionBackend.route("host-1", tool),
            )
        }
    }

    @Test
    fun `remote_shell tool is not double-routed`() {
        // remote_shell is already remote by definition; the router only
        // reroutes shell_execute, so remote_shell stays LOCAL here (its own
        // executor handles the remote call). Prevents accidental re-wrap.
        assertEquals(ExecutionBackend.Backend.LOCAL, ExecutionBackend.route("host-1", "remote_shell"))
    }

    // ── binding store ────────────────────────────────────────────────────

    private lateinit var bindings: ExecutionBackend.Bindings

    @Before
    fun setUp() {
        bindings = ExecutionBackend.Bindings(Application())
        bindings.override = mutableMapOf()
    }

    @Test
    fun `bind and read back`() {
        assertNull(bindings.boundHost("s1"))
        bindings.bind("s1", "host-a")
        assertEquals("host-a", bindings.boundHost("s1"))
    }

    @Test
    fun `bindings are per-session isolated`() {
        bindings.bind("s1", "host-a")
        bindings.bind("s2", "host-b")
        assertEquals("host-a", bindings.boundHost("s1"))
        assertEquals("host-b", bindings.boundHost("s2"))
        assertNull(bindings.boundHost("s3"))
    }

    @Test
    fun `unbind clears only that session`() {
        bindings.bind("s1", "host-a")
        bindings.bind("s2", "host-b")
        bindings.unbind("s1")
        assertNull(bindings.boundHost("s1"))
        assertEquals("host-b", bindings.boundHost("s2"))
    }

    @Test
    fun `rebind replaces host`() {
        bindings.bind("s1", "host-a")
        bindings.bind("s1", "host-b")
        assertEquals("host-b", bindings.boundHost("s1"))
    }

    @Test
    fun `route reflects live binding`() {
        val sid = "s1"
        assertEquals(ExecutionBackend.Backend.LOCAL, ExecutionBackend.route(bindings.boundHost(sid), "shell_execute"))
        bindings.bind(sid, "host-a")
        assertEquals(ExecutionBackend.Backend.REMOTE, ExecutionBackend.route(bindings.boundHost(sid), "shell_execute"))
        bindings.unbind(sid)
        assertEquals(ExecutionBackend.Backend.LOCAL, ExecutionBackend.route(bindings.boundHost(sid), "shell_execute"))
    }
}