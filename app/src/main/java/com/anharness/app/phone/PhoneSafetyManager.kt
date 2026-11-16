package com.anharness.app.phone

import com.anharness.app.offload.OffloadPermissionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-phone-safety-gate] Runtime side of [PhoneSafetyGate]: session-scoped
 * overrides + the per-call approval plumbing.
 *
 * The decision core stays pure; this object holds the mutable facts:
 *  - overrides: sessionId → set of packages the user explicitly unlocked
 *    for THIS session (granted via a force-ask card, never persisted,
 *    cleared on session delete via [clearSession]).
 *  - approval: reuses OffloadPermissionManager's FIFO card queue through
 *    [OffloadPermissionManager.enforceAlwaysAsk] under the registered
 *    "phone_control" tool — BYPASS/grants cannot skip it, NOT_ALLOWED
 *    hard-denies. The gate only tightens the tool-level policy.
 *
 * Handler flow (a11y / shizuku input paths):
 *   verdict = PhoneSafetyGate.decide(action, pkg, isPassword, hasOverride)
 *   DENY  → if overridable: offer override card; on allow, record override
 *           and re-decide (now ASK); on deny/timeout: error envelope.
 *           PASSWORD_FIELD is never overridable.
 *   ASK   → mandatory per-call card; deny/timeout → error envelope.
 *   ALLOW → proceed.
 */
object PhoneSafetyManager {

    /** Tool-registry name for the approval cards (default NOT_ALLOWED is
     *  NOT used here — enforceAlwaysAsk prompts regardless of level; the
     *  registry entry exists so the card pipeline recognises the name). */
    const val TOOL_NAME = "phone_control"

    private val overrides = ConcurrentHashMap<String, MutableSet<String>>()

    fun hasOverride(sessionId: String, packageName: String?): Boolean {
        if (packageName == null) return false
        return overrides[sessionId]?.contains(packageName.lowercase()) == true
    }

    fun grantOverride(sessionId: String, packageName: String) {
        overrides.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
            .add(packageName.lowercase())
    }

    /** Wired into session deletion (ChatRepository) — overrides die with
     *  the session, exactly like tool grants. */
    fun clearSession(sessionId: String) {
        overrides.remove(sessionId)
    }

    /**
     * Ask the user to unlock a blocklisted app for this session only.
     * Returns true when the override was granted. Fail-closed on
     * timeout/cancel/deny. The card is a force-ask card: no preset skips
     * it and "allow in session" is demoted to this one decision.
     */
    suspend fun requestOverride(sessionId: String, packageName: String): Boolean {
        val granted = OffloadPermissionManager.enforceAlwaysAsk(
            TOOL_NAME,
            "Unlock phone control: $packageName (this session only)",
            sessionId,
        )
        if (granted) grantOverride(sessionId, packageName)
        return granted
    }

    /** Mandatory per-call approval for a mutation. Fail-closed. */
    suspend fun requestActionApproval(sessionId: String, actionLabel: String): Boolean =
        OffloadPermissionManager.enforceAlwaysAsk(TOOL_NAME, actionLabel, sessionId)
}