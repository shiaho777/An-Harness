package com.anharness.app.tools

import android.content.Context
import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.AgentToolParam
import com.anharness.app.offload.OffloadPermissionManager
import com.anharness.app.remote.RemoteHostConfig
import com.anharness.app.remote.RemoteHostManager
import com.anharness.app.remote.SshTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * [T-remote-ssh] Native `remote_shell` tool (P4): run a command on a
 * configured remote SSH host.
 *
 * Safety: EVERY call goes through a mandatory per-call approval card
 * (enforceAlwaysAsk) — running commands on another machine is high-impact,
 * so no preset can skip it. The card names the host and the command.
 * Host-key TOFU pinning is enforced by the transport; a changed key aborts.
 *
 * Output/timeout semantics mirror the local shell (50k char head+tail cap,
 * 1..900s clamp) so the agent gets a consistent contract.
 */
object RemoteShellTool {
    const val NAME = "remote_shell"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run a shell command on a configured REMOTE host over SSH. " +
            "Use host_id from the remote-host list (manage hosts via the minis-remote CLI or Settings). " +
            "Every call requires explicit user approval — this executes on another machine. " +
            "The host key is pinned on first connect (TOFU); a changed key is refused. " +
            "Output is capped and long commands time out; prefer short, focused commands.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Check disk usage on build server'). Use the same language as the user."),
            "host_id" to AgentToolParam("string", "Id of the configured remote host to run on."),
            "command" to AgentToolParam("string", "The shell command to execute on the remote host."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (1-900, default 60)."),
        ),
        required = listOf("tool_title", "host_id", "command"),
        propertyOrdering = listOf("tool_title", "host_id", "command", "timeout"),
    )

    fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid remote_shell arguments", false)
        }
        val toolTitle = args.optString("tool_title", NAME)
        val hostId = args.optString("host_id", "")
        val command = RemoteHostConfig.buildCommand(args.optString("command", ""))
            ?: return ToolExecutionResult("Error: 'command' must not be blank", false, toolTitle = toolTitle)
        if (hostId.isBlank()) {
            return ToolExecutionResult("Error: 'host_id' is required", false, toolTitle = toolTitle)
        }

        val manager = RemoteHostManager(context)
        val host = manager.get(hostId)
            ?: return ToolExecutionResult(
                "Error: no remote host with id=$hostId. Add one via minis-remote add.",
                false, toolTitle = toolTitle,
            )
        val secret = manager.loadSecret(hostId)
            ?: return ToolExecutionResult(
                "Error: no credential stored for host '${host.label}'.",
                false, toolTitle = toolTitle,
            )

        // Mandatory per-call approval — no preset skips it.
        val sid = sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        val label = "Remote shell on ${host.label}: ${command.take(80)}"
        val approved = runBlocking {
            OffloadPermissionManager.enforceAlwaysAsk(NAME, label, sid)
        }
        if (!approved) {
            return ToolExecutionResult("Error: remote command was not approved by the user.", false, toolTitle = toolTitle)
        }

        val timeoutS = RemoteHostConfig.clampTimeout(
            if (args.has("timeout")) args.optInt("timeout") else null,
        )
        return try {
            val result = runBlocking {
                SshTransport.exec(host, secret, command, timeoutS)
            }
            val meta = JSONObject()
                .put("host", host.label)
                .put("exitCode", result.exitCode)
                .put("truncated", result.truncated)
            ToolExecutionResult(
                output = meta.toString() + "\n" + result.output,
                success = result.exitCode == 0,
                toolTitle = toolTitle,
            )
        } catch (e: SecurityException) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: remote exec failed: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }
}