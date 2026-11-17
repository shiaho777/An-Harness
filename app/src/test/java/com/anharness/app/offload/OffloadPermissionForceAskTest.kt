package com.anharness.app.offload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-scheduled-force-approval] Contract tests for the mandatory per-call
 * approval gate and the FIFO approval queue.
 *
 * Problem: scheduled-task mutations ran ungated (handler had no gate call,
 * tool unregistered → BYPASS), and the old single pendingContinuation slot
 * silently overwrote the first waiter when two offload IPC threads asked
 * at once — that caller hung forever.
 *
 * Solution under test: enforceAlwaysAsk ignores BYPASS/grants/denials but
 * still hard-denies NOT_ALLOWED (tighten-only), demotes ALLOW_SESSION to
 * per-call, and every waiter gets exactly one card via the queue.
 *
 * Pure JVM: levelStoreOverride replaces SharedPreferences; the UI side is
 * simulated by watching pendingRequest and calling respondToRequest, the
 * same surface OffloadPermissionDialog uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffloadPermissionForceAskTest {

    @Before
    fun setUp() {
        OffloadPermissionManager.levelStoreOverride = mutableMapOf()
        OffloadPermissionManager.clearSessionGrants(SESSION)
        OffloadPermissionManager.clearSessionGrants(SESSION_2)
        // Drain any card left over from a previous test.
        OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION)
    }

    @After
    fun tearDown() {
        OffloadPermissionManager.respondToRequest(OffloadPermissionManager.Response.DENY_SESSION)
        OffloadPermissionManager.clearSessionGrants(SESSION)
        OffloadPermissionManager.clearSessionGrants(SESSION_2)
        OffloadPermissionManager.levelStoreOverride = null
    }

    private fun setLevel(tool: String, level: OffloadPermissionManager.PermissionLevel) =
        OffloadPermissionManager.setLevel(tool, level)

    private fun respond(r: OffloadPermissionManager.Response) =
        OffloadPermissionManager.respondToRequest(r)

    private fun head() = OffloadPermissionManager.pendingRequest.value

    // ── registry ─────────────────────────────────────────────────────────

    @Test
    fun `scheduled_task is registered with ASK_ONCE default`() {
        assertEquals(
            OffloadPermissionManager.PermissionLevel.ASK_ONCE,
            OffloadPermissionManager.getLevel("scheduled_task"),
        )
    }

    @Test
    fun `mutation subcommand set covers create update delete enable disable run and aliases`() {
        val expected = setOf("create", "add", "update", "edit", "delete", "remove", "rm", "enable", "disable", "run")
        assertEquals(expected, OffloadPermissionManager.scheduledMutationSubcommands)
        assertFalse("list" in OffloadPermissionManager.scheduledMutationSubcommands)
    }

    // ── enforceAlwaysAsk: no preset can skip ─────────────────────────────

    @Test
    fun `force ask prompts even under BYPASS and allow-once passes`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val call = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        yield()
        val card = head()
        assertNotNull("BYPASS must still produce a card", card)
        assertTrue(card!!.forceAsk)
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(call.await())
    }

    @Test
    fun `force ask deny rejects the call`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val call = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled delete", SESSION) }
        yield()
        assertNotNull(head())
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(call.await())
    }

    @Test
    fun `NOT_ALLOWED hard-denies without a card - gate only tightens`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        val allowed = OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled run", SESSION)
        assertFalse(allowed)
        assertNull("no dialog may be shown for NOT_ALLOWED", head())
    }

    @Test
    fun `ALLOW_SESSION on a force card is demoted - next call re-prompts`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val first = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        yield()
        respond(OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(first.await())

        val second = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        yield()
        assertNotNull("session grant must not skip the next mutation card", head())
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(second.await())
    }

    @Test
    fun `force deny does not poison later calls - no session denial cached`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val first = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        yield()
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(first.await())

        val second = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        yield()
        assertNotNull("a prior force-deny must not silently block the next call", head())
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(second.await())
    }

    @Test
    fun `prior session grant does not skip a force card`() = runTest {
        // Establish a normal ASK_ONCE session grant first.
        setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val warm = async { OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION) }
        yield()
        respond(OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(warm.await())
        // Same session, force gate on the same tool name: must still ask.
        val forced = async { OffloadPermissionManager.enforceAlwaysAsk("calendar", "android-calendar", SESSION) }
        yield()
        assertNotNull(head())
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(forced.await())
    }

    // ── checkPermission regressions: existing tri-state semantics ────────

    @Test
    fun `ASK_ONCE allow-session caches for the session`() = runTest {
        setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val first = async { OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION) }
        yield()
        assertFalse("normal cards are not force-ask", head()!!.forceAsk)
        respond(OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(first.await())
        // Second call: no card, straight through.
        assertTrue(OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION))
        assertNull(head())
        // Other session still asks.
        val other = async { OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION_2) }
        yield()
        assertNotNull(head())
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(other.await())
    }

    @Test
    fun `session denial short-circuits without re-prompting`() = runTest {
        setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val first = async { OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION) }
        yield()
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(first.await())
        assertFalse(OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION))
        assertNull("deny must be remembered, not re-asked", head())
        // clearSessionGrants resets both grants and denials.
        OffloadPermissionManager.clearSessionGrants(SESSION)
        val again = async { OffloadPermissionManager.checkPermission("calendar", "android-calendar", SESSION) }
        yield()
        assertNotNull(head())
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(again.await())
    }

    // ── FIFO queue: concurrent waiters each get exactly one card ─────────

    @Test
    fun `two concurrent force calls queue instead of overwriting`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val a = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        val b = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled delete", SESSION) }
        yield()
        val firstCard = head()
        assertNotNull(firstCard)
        assertEquals("minis-scheduled create", firstCard!!.toolTitle)
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        yield()
        val secondCard = head()
        assertNotNull("second waiter must surface after the first resolves", secondCard)
        assertEquals("minis-scheduled delete", secondCard!!.toolTitle)
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertTrue(a.await())
        assertFalse(b.await())
        assertNull(head())
    }

    @Test
    fun `respond with empty queue is a no-op`() {
        assertNull(head())
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertNull(head())
    }

    // ── timeout: fail closed, card scrubbed ──────────────────────────────

    @Test
    fun `unanswered force card times out to deny and clears the queue`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        // Real dispatcher so withTimeoutOrNull uses wall-clock virtual time
        // of the test scheduler deterministically via advanceTimeBy.
        val call = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled run", SESSION) }
        yield()
        assertNotNull(head())
        advanceTimeBy(OffloadPermissionManager.PERMISSION_DIALOG_TIMEOUT_MS + 1_000)
        assertFalse("timeout must fail closed", call.await())
        assertNull("timed-out card must not linger", head())
    }

    @Test
    fun `cancelled caller removes its card and surfaces the next`() = runTest {
        setLevel("scheduled_task", OffloadPermissionManager.PermissionLevel.BYPASS)
        val a = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled create", SESSION) }
        val b = async { OffloadPermissionManager.enforceAlwaysAsk("scheduled_task", "minis-scheduled delete", SESSION) }
        yield()
        assertEquals("minis-scheduled create", head()!!.toolTitle)
        a.cancel()
        yield()
        assertEquals("cancelled head must surface the next card", "minis-scheduled delete", head()!!.toolTitle)
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(b.await())
        assertNull(head())
    }

    private companion object {
        const val SESSION = "test-session-a"
        const val SESSION_2 = "test-session-b"
    }
}