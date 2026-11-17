package com.anharness.app.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-remote-backend] Execution-backend abstraction (P4 slice 3).
 *
 * The agent LOOP always stays on the phone (model calls, steering,
 * permissions); what can move is the EXECUTION BACKEND of shell commands.
 * A session may be bound to a remote SSH host; while bound, shell_execute
 * runs on that host instead of the local PRoot sandbox. File/browser/phone
 * tools stay local — they operate on the phone by definition.
 *
 * This mirrors dsh-Go's "engine on another machine, phone as client" at
 * the tool-execution layer, without relocating the whole loop (a later,
 * larger slice). The binding is per-session, transient by default, and
 * every remote command still passes the mandatory approval card inside
 * RemoteShellTool — binding changes WHERE, never WHETHER approval happens.
 *
 * Routing decision is a pure function ([route]) so it is JVM-testable.
 */
object ExecutionBackend {

    enum class Backend { LOCAL, REMOTE }

    /**
     * Pure routing decision for a shell command.
     * @param boundHostId the session's remote binding, null = local.
     * @param toolName the tool being executed.
     * Only shell_execute is reroutable; everything else stays local even
     * when a binding exists (files/browser/phone are phone-side facts).
     */
    fun route(boundHostId: String?, toolName: String): Backend =
        if (boundHostId != null && toolName == "shell_execute") Backend.REMOTE else Backend.LOCAL

    /**
     * Per-session binding store. Transient in-memory by default; persisted
     * lightly so a reload keeps the binding (the host secret stays in the
     * encrypted store regardless). A storage seam supports JVM tests.
     */
    class Bindings(context: Context) {
        internal var override: MutableMap<String, String>? = null
        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        fun bind(sessionId: String, hostId: String) {
            val o = override
            if (o != null) o[sessionId] = hostId
            else prefs.edit().putString(key(sessionId), hostId).apply()
        }

        fun unbind(sessionId: String) {
            val o = override
            if (o != null) o.remove(sessionId)
            else prefs.edit().remove(key(sessionId)).apply()
        }

        fun boundHost(sessionId: String): String? {
            val o = override
            return if (o != null) o[sessionId]
            else prefs.getString(key(sessionId), null)
        }

        private fun key(sessionId: String) = "bind_$sessionId"

        companion object { private const val PREFS = "remote_bindings" }
    }
}