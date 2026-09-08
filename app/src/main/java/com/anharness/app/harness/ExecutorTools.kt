package com.anharness.app.harness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.anharness.app.agent.shell.BashismDetector
import com.anharness.app.agent.shell.BashismReminder
import com.anharness.app.agent.shell.OnDemandBash
import com.anharness.app.browser.BrowserActionInput
import com.anharness.app.browser.BrowserTabPool
import com.anharness.app.data.EnvVarRedactor
import com.anharness.app.data.repository.MemoryRepository
import com.anharness.app.sandbox.ExecutionCoordinator
import com.anharness.app.terminal.MinisOpenUrlBroker
import com.anharness.app.terminal.MinisUrlMarker
import com.anharness.app.tools.MemoryTools
import com.anharness.app.tools.MemoryToolRecord
import com.anharness.app.tools.ToolExecutionResult
import com.anharness.agent.AgentToolResult
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder

/**
 * P2 executor migration (issue #16): the shell_execute / browser_use /
 * memory_write / memory_get executors that used to live inline in
 * ChatViewModel.executeTool, expressed as `core:agent` AgentTool
 * implementations. Shared state (browser tab pool, memory repository,
 * session id) stays ViewModel-owned and is injected via constructor —
 * no globals.
 *
 * Each wrapper exposes two entry points:
 * - `execute(call, …)` — the AgentTool contract for AgentLoop. Returns an
 *   error AgentToolResult on failure; never throws.
 * - `executeXxx(argsJson)` — returns the full app-level
 *   [ToolExecutionResult] (images, page URL, timeout flag, tool title) so
 *   ChatViewModel's legacy post-processing keeps working unchanged when the
 *   pilot routes through these wrappers.
 */
object ExecutorTools {

    /** Sentinel returned by the bash wrapper when bash is missing at run time,
     *  distinct from a script that legitimately exits 127 (T-bash-on-demand M5). */
    const val BASH_MISSING_SENTINEL = 119

    /** App-level result + the AgentToolResult projection for AgentLoop. */
    data class Outcome(
        val result: ToolExecutionResult,
    ) {
        val agentResult: AgentToolResult
            get() = AgentToolResult(text = result.output, isError = !result.success)
    }

    /**
     * Wrap a script to run under bash via a guest-side self-written temp file
     * (base64, single line, self-cleaning), guarding on `command -v bash` so a
     * vanished bash is detected precisely for inline self-heal.
     *
     * The whole wrapper runs inside a SUBSHELL `( … )`. This is load-bearing on
     * Android: PersistentShell drives commands as `{cmd}; echo …_EXIT_$?…` and
     * reads the exit code from that marker line. A bare `|| exit 119` would exit
     * the persistent shell process itself BEFORE the marker echo runs, so no
     * marker is emitted and PersistentShell.parseExitCode falls back to -1 —
     * the M5 self-heal sentinel check (== 119 / 30464) then never matches and a
     * vanished bash is never re-installed. Wrapping in a subshell makes
     * `exit 119` leave only the subshell, so `$?` = 119 reaches the marker.
     */
    fun wrapForBash(script: String): String {
        // [T-heredoc-trailing-newline] A heredoc that ends the decoded file with
        // no trailing newline fails with "unexpected end of file". Guarantee one.
        val normalized = if (script.endsWith("\n")) script else script + "\n"
        val b64 = Base64.encodeToString(
            normalized.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
            "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
            "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
    }

    /**
     * The shell executor body, ported verbatim from ChatViewModel.executeShellCommand.
     * [onLine] mirrors the legacy lineCallback tail: it receives each cleaned
     * output line (markers already stripped + brokered). The legacy path
     * updated the tool block inline and launched its UI hop on viewModelScope,
     * so this callback is plain (non-suspend) — the caller owns threading.
     */
    suspend fun executeShell(
        context: Context,
        sessionId: () -> String,
        argsJson: String,
        onLine: ((line: String) -> Unit)? = null,
        onUpdate: (String) -> Unit = {},
    ): Outcome = runCatching {
        executeShellInternal(context, sessionId, argsJson, onLine, onUpdate)
    }.getOrElse { e ->
        Outcome(ToolExecutionResult("Error: ${e.message}", false))
    }

    private suspend fun executeShellInternal(
        context: Context,
        sessionId: () -> String,
        argsJson: String,
        onLine: ((line: String) -> Unit)?,
        onUpdate: (String) -> Unit,
    ): Outcome {
        val args = org.json.JSONObject(argsJson)
        var command = args.optString("command", "")
        val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
        val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
        val toolTitle = args.optString("tool_title", "shell_execute")

        if (command.isBlank()) {
            return Outcome(ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle))
        }

        // Delay execution: block the agent flow without occupying the shell,
        // allowing other concurrent tasks to use it during the wait period.
        if (delaySec > 0) {
            for (remaining in delaySec downTo 1) {
                val mm = remaining / 60
                val ss = remaining % 60
                val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
                onUpdate("⏳ Waiting $countdown before executing...")
                delay(1000)
            }
            onUpdate("")
        }

        val dispatchSessionId = sessionId()
        android.util.Log.w("ShellExecDiag",
            "executeShell dispatch=$dispatchSessionId cmd=${command.take(120).replace('\n', ' ')}")

        // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and,
        // if found, transparently install + switch to bash. Install time is
        // NOT charged against the command timeout (OnDemandBash has its own
        // budget). `command` is rewritten to the bash-wrapped form on the S/E
        // path; `bashReminder` is attached if we fall back to sh.
        BashismDetector.ensureLoaded(context)
        val bashism = BashismDetector.detect(command)
        var bashReminder: String? = null
        val originalCommand = command
        var bashScript: String? = null   // set when we bash-wrapped; enables M5 self-heal retry
        if (bashism.needsBash) {
            val executor = OnDemandBash.Executor { c, t ->
                ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
            }
            when (val outcome = OnDemandBash.ensureBash(context, executor)) {
                is OnDemandBash.Outcome.Available -> {
                    if (bashism.mustSwitchInterpreter) {
                        // §3.2 M3: self-write the script in the guest (base64,
                        // single line, self-cleaning) and run it under bash.
                        // The `command -v bash || exit 119` guard detects a
                        // bash that vanished after our cache check (M5) so we
                        // can self-heal below instead of failing.
                        command = wrapForBash(command)
                        bashScript = originalCommand // remember for self-heal retry
                    }
                    // T1-only (script invokes bash itself) → run as-is under sh.
                }
                is OnDemandBash.Outcome.Unavailable ->
                    bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
            }
        }

        var result = ExecutionCoordinator.execute(
            sessionId = dispatchSessionId,
            command = command,
            timeout = timeoutSec * 1000L,
            lineCallback = { rawLine ->
                // Strip any OSC MinisOpenURL markers emitted by
                // /usr/local/bin/minis-open and forward the captured
                // URLs to the broker so the chat screen can present the
                // in-app preview. Lines that were *entirely* a marker
                // (nothing visible afterwards) are dropped so the tool
                // output doesn't grow blank rows.
                val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
                for (raw in capturedUrls) MinisOpenUrlBroker.offer(raw)
                if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return@execute

                onLine?.invoke(cleanedLine)
            },
        )

        // [T-bash-on-demand] M5 self-heal: our bash wrapper returns sentinel
        // 119 when bash vanished (user apk del'd) after we cached it
        // available. Re-probe + reinstall once and rerun THIS command under
        // bash inline, so it still succeeds instead of failing.
        // Accept both the raw sentinel (119) and the wait(2)-encoded status
        // (119 << 8 = 30464) the coordinator may surface.
        if ((result.exitCode == BASH_MISSING_SENTINEL ||
                result.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
            OnDemandBash.markDisappeared()
            val executor = OnDemandBash.Executor { c, t ->
                ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
            }
            val healed = OnDemandBash.ensureBash(context, executor)
            command = if (healed is OnDemandBash.Outcome.Available) wrapForBash(bashScript!!) else bashScript!!
            result = ExecutionCoordinator.execute(
                sessionId = dispatchSessionId, command = command, timeout = timeoutSec * 1000L)
        }

        // Also scrub markers from the aggregated one-shot output and
        // broker any URLs that only appeared there (defensive — handles
        // executors that don't fire lineCallback for every line).
        val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
        for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
        val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
        val exitInfo = if (result.exitCode != 0) " (exit code ${result.exitCode})" else ""
        // Exit code 124 is the BusyBox/GNU timeout-utility convention for
        // a command that exceeded its budget. PersistentShell returns this
        // when its `withTimeoutOrNull(timeout)` wrapper fires.
        val timedOut = result.exitCode == 124

        // Redact env-var values that leaked into the captured output
        // before the model sees them. No-op when Privacy Mode is OFF.
        val finalOutput = "$output$exitInfo"
        val (redactedOut, redactHits) = EnvVarRedactor.redactIfEnabled(finalOutput)
        if (redactHits > 0) {
            android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
        }

        // [T-bash-on-demand] M5 self-heal: bash disappeared (user apk del'd)
        // → re-probe next time.
        if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
            OnDemandBash.markDisappeared()
        }
        // §4.2: append the bashism reminder when we fell back to sh and the
        // command failed OR any silent-class rule was hit (S-class exit-0
        // exception, default-on).
        val withReminder = bashReminder?.let { rem ->
            if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
        } ?: redactedOut

        return Outcome(ToolExecutionResult(
            output = withReminder,
            success = result.exitCode == 0,
            toolTitle = toolTitle,
            timedOut = timedOut,
        ))
    }

    /**
     * The browser_use executor body, ported verbatim from
     * ChatViewModel.executeBrowserUseTool (screenshot resize + persistence,
     * fetched-file persistence, minis:// URL minting).
     */
    suspend fun executeBrowser(
        tabPool: BrowserTabPool,
        sessionId: () -> String,
        filesDir: File,
        argsJson: String,
    ): Outcome = runCatching {
        val input = BrowserActionInput.parse(argsJson)
            ?: return@runCatching Outcome(ToolExecutionResult("Error: Invalid browser_use input", false))

        val result = tabPool.execute(input)
        val toolTitle = try {
            org.json.JSONObject(argsJson).optString("tool_title", "browser_use")
        } catch (_: Exception) { "browser_use" }

        var output = result.text
        var persistentImagePath: String? = result.imageFilePath
        var inferenceBytes: ByteArray? = null

        // Persist browser screenshots to /var/minis/browser/<session>/ so the
        // agent can reference them via minis:// in subsequent tool calls
        // (mirrors iOS AIChatViewModel case "browser_use").
        val base64 = result.base64Image
        var linuxImagePath: String? = null
        if (base64 != null) {
            val raw = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (_: Exception) { null }
            if (raw != null) {
                // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                // long edge across attachments / browser / read_image.
                inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                val persistPath = persistBrowserArtifact(sessionId, filesDir, filename, raw)
                if (persistPath != null) {
                    persistentImagePath = persistPath
                    linuxImagePath = "/var/minis/browser/$filename"
                    linuxPathToMinisURL(linuxImagePath)?.let {
                        output = "$output\nminis_url: $it"
                    }
                }
            }
        }

        // Persist fetched files (fetch action) and append minis_url
        val fetchData = result.fetchedFileData
        val fetchName = result.fetchedFileName
        if (fetchData != null && fetchName != null) {
            persistBrowserArtifact(sessionId, filesDir, fetchName, fetchData)
            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                output = "$output\nminis_url: $it"
            }
        }

        Outcome(ToolExecutionResult(
            output = output,
            success = result.success,
            imageData = inferenceBytes,
            imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
            toolTitle = toolTitle,
            pageURL = result.pageURL,
            imageFilePath = persistentImagePath,
            imageLinuxPath = linuxImagePath,
        ))
    }.getOrElse { e ->
        Outcome(ToolExecutionResult("Error: ${e.message}", false))
    }

    /**
     * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
     * That directory is bind-mounted to `/var/minis/browser/` so the agent can
     * read it back via file_read / file_write / minis:// URLs.
     * Returns the host absolute path on success, null otherwise.
     */
    private fun persistBrowserArtifact(
        sessionId: () -> String,
        filesDir: File,
        filename: String,
        data: ByteArray,
    ): String? {
        val sid = sessionId().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val dir = File(filesDir, "minis-sessions/$sid/browser").apply { mkdirs() }
            val file = File(dir, filename)
            file.writeBytes(data)
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("ExecutorTools", "persistBrowserArtifact failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
     * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
     */
    internal fun linuxPathToMinisURL(path: String): String? {
        val prefix = "/var/minis/"
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val namespace = rest.substring(0, slash)
        val filename = rest.substring(slash + 1)
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "minis://$namespace/$encoded"
    }

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
     */
    internal fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) { bmp.recycle(); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(bmp, w, h, true)
        bmp.recycle()
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        resized.recycle()
        return out.toByteArray()
    }

    /**
     * The memory_write executor body, ported from
     * ChatViewModel.executeMemoryWriteTool. Records the call for the
     * SessionMemorySheet via [onRecord] when the write succeeds.
     */
    fun executeMemoryWrite(
        repository: MemoryRepository?,
        memoryEnabled: () -> Boolean,
        argsJson: String,
        onRecord: (MemoryToolRecord) -> Unit = {},
    ): Outcome = runCatching {
        val result = if (repository == null) {
            ToolExecutionResult("Error: Memory not available", false)
        } else if (!memoryEnabled()) {
            ToolExecutionResult(
                "Memory writes are disabled for this session (user toggled /memory off). Reads remain available.",
                false, toolTitle = "Memory (disabled)")
        } else {
            val r = MemoryTools.executeMemoryWrite(argsJson, repository)
            ToolExecutionResult(r.output, r.success, toolTitle = r.toolTitle)
        }
        // Record for SessionMemorySheet — including the disabled/failed path
        // so the sheet reflects what the model actually attempted.
        if (result.success) {
            val content = try {
                org.json.JSONObject(argsJson).optString("content", "")
            } catch (_: Exception) { "" }
            onRecord(MemoryToolRecord(
                title = result.toolTitle,
                isWrite = true,
                preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
                output = result.output,
                writtenContent = content,
            ))
        }
        Outcome(result)
    }.getOrElse { e ->
        Outcome(ToolExecutionResult("Error: ${e.message}", false))
    }

    /**
     * The memory_get executor body, ported from
     * ChatViewModel.executeMemoryGetTool. Reads stay available even when
     * writes are toggled off.
     */
    fun executeMemoryGet(
        repository: MemoryRepository?,
        argsJson: String,
        onRecord: (MemoryToolRecord) -> Unit = {},
    ): Outcome = runCatching {
        val result = if (repository == null) {
            ToolExecutionResult("Error: Memory not available", false)
        } else {
            val r = MemoryTools.executeMemoryGet(argsJson, repository)
            ToolExecutionResult(r.output, r.success, toolTitle = r.toolTitle)
        }
        if (result.success) {
            val keywords = try {
                org.json.JSONObject(argsJson).optString("keywords", "")
            } catch (_: Exception) { "" }
            onRecord(MemoryToolRecord(
                title = result.toolTitle,
                isWrite = false,
                preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
                output = result.output,
                keywords = keywords,
            ))
        }
        Outcome(result)
    }.getOrElse { e ->
        Outcome(ToolExecutionResult("Error: ${e.message}", false))
    }
}
