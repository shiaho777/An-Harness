package com.anharness.app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-phone-safety-gate] Decision-table tests for the phone-control safety
 * gate. Pure JVM — the whole rule surface (blocklist, password absolute
 * block, override downgrade, read-only exemption) is exercised without a
 * device, mirroring ShizukuBackendStateTest's decideState pattern.
 */
class PhoneSafetyGateTest {

    private fun decide(
        action: String,
        pkg: String? = "com.example.app",
        password: Boolean = false,
        override: Boolean = false,
    ) = PhoneSafetyGate.decide(action, pkg, password, override)

    // ── read-only actions pass outside sensitive apps ────────────────────

    @Test
    fun `read-only actions allow in normal apps`() {
        for (action in listOf("ui.dump", "ui.find", "ui.info", "extract", "wait", "service")) {
            val (v, r) = decide(action)
            assertEquals("$action should ALLOW", PhoneSafetyGate.Verdict.ALLOW, v)
            assertEquals(null, r)
        }
    }

    @Test
    fun `mutations ask in normal apps`() {
        for (action in listOf("tap", "input.text", "input.key", "scroll", "gesture", "dialog.dismiss")) {
            val (v, r) = decide(action)
            assertEquals("$action should ASK", PhoneSafetyGate.Verdict.ASK, v)
            assertEquals(PhoneSafetyGate.Reason.MUTATION, r)
        }
    }

    @Test
    fun `screenshot always asks even though it observes`() {
        val (v, _) = decide("ui.screenshot")
        assertEquals(PhoneSafetyGate.Verdict.ASK, v)
    }

    // ── payment blocklist ────────────────────────────────────────────────

    @Test
    fun `payment app denies mutations without override`() {
        val (v, r) = decide("tap", pkg = "com.eg.android.AlipayGphone")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PAYMENT_APP, r)
    }

    @Test
    fun `payment app denies reads too - screen content is sensitive`() {
        val (v, r) = decide("ui.dump", pkg = "com.tencent.mm")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PAYMENT_APP, r)
    }

    @Test
    fun `payment prefix matches regional variants`() {
        val (v, _) = decide("tap", pkg = "com.eg.android.AlipayGphone.lite")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        val (v2, _) = decide("tap", pkg = "com.unionpay.cloudpay")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v2)
    }

    @Test
    fun `package match is case-insensitive`() {
        val (v, _) = decide("tap", pkg = "COM.EG.ANDROID.ALIPAYGPHONE")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
    }

    @Test
    fun `override downgrades payment deny to ask, never to allow`() {
        val (v, r) = decide("tap", pkg = "com.eg.android.AlipayGphone", override = true)
        assertEquals(PhoneSafetyGate.Verdict.ASK, v)
        assertEquals(PhoneSafetyGate.Reason.PAYMENT_APP, r)
    }

    @Test
    fun `unknown package is treated as normal app`() {
        val (v, _) = decide("tap", pkg = null)
        assertEquals(PhoneSafetyGate.Verdict.ASK, v)
    }

    // ── system settings ──────────────────────────────────────────────────

    @Test
    fun `settings denies mutations without override`() {
        val (v, r) = decide("tap", pkg = "com.android.settings")
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.SYSTEM_SETTINGS, r)
    }

    @Test
    fun `settings reads stay allowed for diagnosis`() {
        val (v, _) = decide("ui.dump", pkg = "com.android.settings")
        assertEquals(PhoneSafetyGate.Verdict.ALLOW, v)
    }

    @Test
    fun `oem settings variants are blocked`() {
        for (pkg in listOf("com.miui.securitycenter", "com.huawei.systemmanager", "com.coloros.safecenter")) {
            val (v, r) = decide("input.text", pkg = pkg)
            assertEquals("$pkg should DENY", PhoneSafetyGate.Verdict.DENY, v)
            assertEquals(PhoneSafetyGate.Reason.SYSTEM_SETTINGS, r)
        }
    }

    @Test
    fun `settings override downgrades to ask`() {
        val (v, _) = decide("tap", pkg = "com.android.settings", override = true)
        assertEquals(PhoneSafetyGate.Verdict.ASK, v)
    }

    // ── password fields: absolute ────────────────────────────────────────

    @Test
    fun `password field denies mutations even with override`() {
        val (v, r) = decide("input.text", password = true, override = true)
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PASSWORD_FIELD, r)
    }

    @Test
    fun `password field denies tap too`() {
        val (v, r) = decide("tap", password = true)
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PASSWORD_FIELD, r)
    }

    @Test
    fun `password flag does not block reads`() {
        val (v, _) = decide("ui.dump", password = true)
        assertEquals(PhoneSafetyGate.Verdict.ALLOW, v)
    }

    @Test
    fun `password block wins over payment override`() {
        val (v, r) = decide("input.text", pkg = "com.eg.android.AlipayGphone", password = true, override = true)
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PASSWORD_FIELD, r)
    }

    // ── override store semantics ─────────────────────────────────────────

    @Test
    fun `override store is session-scoped and case-insensitive`() {
        val sid = "phone-test-session"
        try {
            assertFalse(PhoneSafetyManager.hasOverride(sid, "com.eg.android.AlipayGphone"))
            PhoneSafetyManager.grantOverride(sid, "COM.EG.ANDROID.ALIPAYGPHONE")
            assertTrue(PhoneSafetyManager.hasOverride(sid, "com.eg.android.alipaygphone"))
            assertFalse("other sessions must not inherit", PhoneSafetyManager.hasOverride("other", "com.eg.android.AlipayGphone"))
            assertFalse("null package never matches", PhoneSafetyManager.hasOverride(sid, null))
            PhoneSafetyManager.clearSession(sid)
            assertFalse("cleared session re-locks", PhoneSafetyManager.hasOverride(sid, "com.eg.android.AlipayGphone"))
        } finally {
            PhoneSafetyManager.clearSession(sid)
        }
    }

    @Test
    fun `deny messages name the reason and package`() {
        val msg = PhoneSafetyGate.denyMessage(PhoneSafetyGate.Reason.PAYMENT_APP, "com.tencent.mm")
        assertTrue(msg.contains("com.tencent.mm"))
        assertTrue(msg.contains("session"))
        val pw = PhoneSafetyGate.denyMessage(PhoneSafetyGate.Reason.PASSWORD_FIELD, null)
        assertTrue(pw.contains("never"))
    }
}