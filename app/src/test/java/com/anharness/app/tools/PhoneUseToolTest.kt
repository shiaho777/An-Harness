package com.anharness.app.tools

import com.anharness.app.phone.PhoneSafetyGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-phone-native-tool] JVM-testable surface of phone_use: the tool
 * definition/schema, action validation, and the gate-action mapping that
 * decides which safety rules apply. Device-dependent execution (gestures,
 * screenshots) is exercised on-device; here we pin the contract that does
 * not need a running accessibility service.
 */
class PhoneUseToolTest {

    @Test
    fun `definition exposes action enum and required fields`() {
        val def = PhoneUseTool.definition()
        assertEquals("phone_use", def.name)
        assertTrue(def.required.contains("action"))
        assertTrue(def.required.contains("tool_title"))
        val actionParam = def.parameters["action"]
        assertNotNull(actionParam)
        assertEquals(PhoneUseTool.actions, actionParam!!.enumValues)
    }

    @Test
    fun `unknown action rejected before any device access`() {
        // No accessibility service running in JVM — an unknown action must
        // fail on validation, not on SERVICE_NOT_RUNNING.
        val result = PhoneUseTool.execute(
            JSONObject().put("action", "launch_missiles").put("tool_title", "x").toString(),
            sessionId = null,
            context = android.app.Application(),
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("unknown action"))
    }

    @Test
    fun `invalid json rejected gracefully`() {
        val result = PhoneUseTool.execute("not json", null, android.app.Application())
        assertFalse(result.success)
        assertTrue(result.output.contains("invalid"))
    }

    @Test
    fun `valid action without service reports service not running`() {
        // A known action passes validation, then hits the service check.
        val result = PhoneUseTool.execute(
            JSONObject().put("action", "status").put("tool_title", "x").toString(),
            sessionId = null,
            context = android.app.Application(),
        )
        assertFalse(result.success)
        assertTrue(
            "expected service/permission error, got: ${result.output}",
            result.output.contains("accessibility") || result.output.contains("not permitted"),
        )
    }

    // ── gate-action mapping contract ─────────────────────────────────────
    // These mirror PhoneUseTool.canonicalGateAction; pinning them here keeps
    // the tool and the CLI gate semantics aligned.

    @Test
    fun `read actions map to read-only gate rules`() {
        // dump_ui/find behave as ui.dump (ALLOW in normal apps).
        val (v, _) = PhoneSafetyGate.decide("ui.dump", "com.example.app", false, false)
        assertEquals(PhoneSafetyGate.Verdict.ALLOW, v)
    }

    @Test
    fun `mutation actions map to ask rules`() {
        for (gate in listOf("tap", "input.text", "scroll", "input.key")) {
            val (v, _) = PhoneSafetyGate.decide(gate, "com.example.app", false, false)
            assertEquals("$gate should ASK", PhoneSafetyGate.Verdict.ASK, v)
        }
    }

    @Test
    fun `screenshot maps to ask even though it observes`() {
        val (v, _) = PhoneSafetyGate.decide("ui.screenshot", "com.example.app", false, false)
        assertEquals(PhoneSafetyGate.Verdict.ASK, v)
    }

    @Test
    fun `payment app blocks phone mutations`() {
        val (v, r) = PhoneSafetyGate.decide("tap", "com.eg.android.AlipayGphone", false, false)
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PAYMENT_APP, r)
    }

    @Test
    fun `password input is absolutely blocked`() {
        val (v, r) = PhoneSafetyGate.decide("input.text", "com.example.app", true, true)
        assertEquals(PhoneSafetyGate.Verdict.DENY, v)
        assertEquals(PhoneSafetyGate.Reason.PASSWORD_FIELD, r)
    }
}