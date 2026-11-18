package com.anharness.app.sandbox.offload

import android.content.Context
import com.anharness.app.remote.RemoteHostConfig
import com.anharness.app.remote.RemoteHostManager
import com.anharness.app.remote.SshTransport
import com.anharness.app.sandbox.NativeOffloadHandler
import com.anharness.app.sandbox.NativeOffloadRequest
import com.anharness.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-remote-ssh] `minis-remote` CLI: manage remote SSH hosts (P4).
 *
 * Subcommands:
 *   list                          — hosts (no secrets)
 *   add --host H --user U [--port P] [--label L] (--password PW | --key KEY)
 *   remove --id ID
 *   probe --id ID                 — connect + pin host key (TOFU), no command
 *   unpin --id ID                 — clear the pinned host key
 *
 * Secrets are stored encrypted by the manager and NEVER echoed back. `probe`
 * and `remove`/`unpin` mutate trust state, so they go through the mandatory
 * approval card (enforceAlwaysAsk) like other sensitive offload mutations.
 * `list`/`add` are gated at the tool level only (add stores a credential the
 * user explicitly provides).
 */
class RemoteHostOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("h", "help"))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(0, HELP)
        }
        val manager = RemoteHostManager(context)
        val sub = args.positional.first()
        return try {
            when (sub) {
                "list" -> handleList(manager, args)
                "add" -> handleAdd(manager, args)
                "remove" -> handleRemove(manager, args, request)
                "probe" -> handleProbe(manager, args, request)
                "unpin" -> handleUnpin(manager, args, request)
                "deploy" -> handleDeploy(manager, args, request)
                else -> error(2, "unknown_subcommand", "unknown subcommand '$sub'", args)
            }
        } catch (e: IllegalArgumentException) {
            error(2, "invalid_arguments", e.message ?: "invalid arguments", args)
        } catch (e: Throwable) {
            error(1, "internal", e.message ?: "unknown", args)
        }
    }

    private fun handleList(manager: RemoteHostManager, args: OffloadArgs): NativeOffloadResult {
        val arr = JSONArray()
        manager.list().forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("label", h.label)
                put("hostname", h.hostname)
                put("port", h.port)
                put("username", h.username)
                put("authMethod", h.authMethod.name)
                put("pinned", h.pinnedFingerprint != null)
                put("hasSecret", manager.loadSecret(h.id) != null)
            })
        }
        val out = JSONObject().put("hosts", arr).put("count", arr.length())
        return ok(out, args)
    }

    private fun handleAdd(manager: RemoteHostManager, args: OffloadArgs): NativeOffloadResult {
        val hostname = args.get("host") ?: throw IllegalArgumentException("--host required")
        val username = args.get("user") ?: throw IllegalArgumentException("--user required")
        val port = args.get("port")?.toIntOrNull() ?: RemoteHostConfig.DEFAULT_PORT
        val password = args.get("password")
        val key = args.get("key")
        val (method, secret) = when {
            key != null -> RemoteHostConfig.AuthMethod.PRIVATE_KEY to key
            password != null -> RemoteHostConfig.AuthMethod.PASSWORD to password
            else -> throw IllegalArgumentException("--password or --key required")
        }
        val host = manager.upsert(
            id = args.get("id"),
            label = args.get("label").orEmpty(),
            hostname = hostname,
            port = port,
            username = username,
            authMethod = method,
            secret = secret,
        )
        // Never echo the secret back.
        val out = JSONObject().put("added", JSONObject().apply {
            put("id", host.id)
            put("label", host.label)
            put("hostname", host.hostname)
            put("port", host.port)
        })
        return ok(out, args)
    }

    private fun handleRemove(manager: RemoteHostManager, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return error(1, "not_found", "no host with id=$id", args)
        gate(request, "remove host $id", args)?.let { return it }
        manager.delete(id)
        return ok(JSONObject().put("removed", id), args)
    }

    private fun handleProbe(manager: RemoteHostManager, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        val host = manager.get(id) ?: return error(1, "not_found", "no host with id=$id", args)
        val secret = manager.loadSecret(id) ?: return error(1, "no_secret", "no credential for host", args)
        gate(request, "probe host ${host.label}", args)?.let { return it }
        val outcome = runBlocking { SshTransport.probe(host, secret) }
        return when (outcome) {
            is SshTransport.ConnectOutcome.Ok -> {
                manager.pinFingerprint(id, outcome.fingerprint)
                ok(JSONObject().put("id", id).put("pinnedFingerprint", outcome.fingerprint), args)
            }
            is SshTransport.ConnectOutcome.HostKeyMismatch ->
                error(1, "host_key_mismatch", "host key changed (expected ${outcome.expected}, got ${outcome.actual})", args)
            is SshTransport.ConnectOutcome.Failure ->
                error(1, "connect_failed", outcome.message, args)
        }
    }

    private fun handleUnpin(manager: RemoteHostManager, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return error(1, "not_found", "no host with id=$id", args)
        gate(request, "unpin host $id", args)?.let { return it }
        manager.clearPin(id)
        return ok(JSONObject().put("unpinned", id), args)
    }

    /**
     * [T-remote-session] Push the session-server script to the host and
     * make it executable, so /remote session can run the loop there.
     * Deploying code to another machine is a mutation → mandatory card.
     */
    private fun handleDeploy(manager: RemoteHostManager, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        val host = manager.get(id) ?: return error(1, "not_found", "no host with id=$id", args)
        val secret = manager.loadSecret(id) ?: return error(1, "no_secret", "no credential for host", args)
        gate(request, "deploy session server to ${host.label}", args)?.let { return it }
        val script = context.assets.open("remote/minis-session-server.py")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        var remotePath = args.get("path") ?: "~/.minis/bin/minis-session-server.py"
        // '~' does not expand inside the quoted upload command — resolve
        // the remote home first and substitute an absolute path.
        if (remotePath.startsWith("~")) {
            val home = runBlocking { SshTransport.exec(host, secret, "echo \$HOME", 15) }
            val homeDir = home.output.trim()
            if (home.exitCode != 0 || homeDir.isEmpty() || !homeDir.startsWith("/")) {
                return error(1, "home_resolve_failed", "could not resolve remote \$HOME", args)
            }
            remotePath = homeDir + remotePath.removePrefix("~")
        }
        val exit = runBlocking {
            SshTransport.uploadTextFile(host, secret, remotePath, script,
                onFirstPin = { fp -> manager.pinFingerprint(id, fp) })
        }
        if (exit != 0) return error(1, "upload_failed", "upload exit=$exit", args)
        val binDir = remotePath.substringBeforeLast('/')
        val chmod = runBlocking {
            SshTransport.exec(host, secret,
                "chmod +x '$remotePath' && ln -sf '$remotePath' '$binDir/minis-session-server' && command -v python3", 30)
        }
        val out = JSONObject()
            .put("id", id)
            .put("path", remotePath)
            .put("python3", chmod.output.trim())
            .put("note", "Set MINIS_API_KEY/MINIS_MODEL on the host (env or ~/.minis-session.json), then use /remote session.")
        return ok(out, args)
    }

    /** Mandatory approval for trust-state mutations. */
    private fun gate(request: NativeOffloadRequest, label: String, args: OffloadArgs): NativeOffloadResult? {
        val sid = request.sessionId
            ?: com.anharness.app.offload.OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        val allowed = runBlocking {
            com.anharness.app.offload.OffloadPermissionManager.enforceAlwaysAsk("remote_shell", "Remote host: $label", sid)
        }
        if (allowed) return null
        return error(77, "not_approved", "action was not approved", args)
    }

    private fun ok(body: JSONObject, args: OffloadArgs): NativeOffloadResult =
        NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(2), args) + "\n")

    private fun error(code: Int, error: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val body = JSONObject().put("error", error).put("message", message).toString()
        return NativeOffloadResult(code, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        private val HELP = """
            minis-remote — manage remote SSH hosts

            Usage:
              minis-remote list
              minis-remote add --host H --user U [--port P] [--label L] (--password PW | --key KEY)
              minis-remote remove --id ID
              minis-remote probe --id ID      (connect + pin host key, TOFU)
              minis-remote unpin --id ID
              minis-remote deploy --id ID [--path P]   (push session server)

            Secrets are stored encrypted and never echoed. Run commands with the
            remote_shell tool (each call requires approval).
        """.trimIndent()
    }
}