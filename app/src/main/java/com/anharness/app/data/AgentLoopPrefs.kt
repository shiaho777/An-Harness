package com.anharness.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-level AgentLoop pilot toggle (An-Harness P1).
 *
 * When on, read-only tool execution in [ChatViewModel.executeTool] routes
 * through the pi-ported `core:agent` loop adapters (`HarnessBridge`) instead
 * of the legacy direct call — starting with `file_read` only. All surrounding
 * guard rails (ToolLoopDetector precheck, preflight validation, result
 * recording, overlay updates) stay exactly as-is; only the executor swaps.
 * Any harness-path exception falls back to the legacy executor and logs.
 *
 * Shape mirrors [FastModePrefs]: durable across sessions, primed at startup
 * so context-free reads are safe. Default OFF.
 */
object AgentLoopPrefs {
    private const val PREFS = "anharness_agent_loop_prefs"
    private const val KEY_ENABLED = "agentLoopEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    /** Context-free read. False before [prime] has ever run (fresh install). */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** In-memory only, for JVM unit tests (no Robolectric in this module). */
    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedEnabled = enabled
    }
}
