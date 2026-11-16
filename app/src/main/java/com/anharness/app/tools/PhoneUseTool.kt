package com.anharness.app.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.anharness.app.accessibility.MinisAccessibilityService
import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.AgentToolParam
import com.anharness.app.offload.OffloadPermissionManager
import com.anharness.app.phone.PhoneSafetyGate
import com.anharness.app.phone.PhoneSafetyManager
import com.anharness.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * [T-phone-native-tool] Native `phone_use` tool — the agent-loop surface
 * for phone control (P1). Mirrors browser_use's shape: one tool, an
 * `action` enum, action-specific params.
 *
 * Every call goes through the SAME final safety gate as the offload CLIs
 * (PhoneSafetyGate + PhoneSafetyManager): payment/settings blocklist,
 * password absolute-deny, session-scoped overrides, mandatory per-call
 * cards for mutations. The tool-level a11y_cli permission is checked
 * first — this tool can only tighten, never bypass it.
 *
 * Screenshots are written into the session's offloads dir
 * (/var/minis/offloads/phone/...) so read_image and shell commands can
 * address them, and the pixels are returned inline for vision models.
 */
object PhoneUseTool {
    const val NAME = "phone_use"

    val actions = listOf(
        "status", "dump_ui", "find", "screenshot",
        "tap", "long_press", "input_text", "clear_text",
        "scroll", "swipe", "key",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Control the phone UI via the accessibility service. " +
            "Requires the Minis accessibility service enabled and the a11y integration permission. " +
            "Safety: payment/banking apps and system Settings are blocked by default (the user may grant a session-scoped override); " +
            "password fields can NEVER be automated; every mutating action shows an approval card the user must accept. " +
            "Actions: status (service/foreground info), dump_ui (visible node tree with nodeId/text/bounds/isPassword), " +
            "find (locate nodes by text/resource-id), screenshot (returns the image inline AND a /var/minis/offloads/phone/ path for read_image), " +
            "tap / long_press (by node_id, text, or x+y), input_text / clear_text (focused editable or node_id), " +
            "scroll (direction at x+y or node_id), swipe (x1,y1 -> x2,y2), key (BACK/HOME/RECENTS/NOTIFICATIONS). " +
            "Typical loop: dump_ui or screenshot to see the screen, then tap/input by node_id or text. " +
            "Node ids expire after ~60s — re-dump if a tap reports NODE_NOT_FOUND.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Tap the login button', 'Screenshot the home screen'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The phone action to perform", enumValues = actions),
            "node_id" to AgentToolParam("string", "Target node id from dump_ui/find (tap, long_press, input_text, clear_text, scroll)"),
            "text" to AgentToolParam("string", "For tap/long_press: exact node text to match. For input_text: the text to type. For find: text to search."),
            "contains" to AgentToolParam("boolean", "For tap/find by text: match by substring instead of exact equality (default false)"),
            "resource_id" to AgentToolParam("string", "For find: Android resource-id to match (e.g. com.app:id/button)"),
            "x" to AgentToolParam("integer", "Screen X coordinate (tap/long_press/scroll when no node_id/text)"),
            "y" to AgentToolParam("integer", "Screen Y coordinate (tap/long_press/scroll when no node_id/text)"),
            "x1" to AgentToolParam("integer", "Swipe start X"),
            "y1" to AgentToolParam("integer", "Swipe start Y"),
            "x2" to AgentToolParam("integer", "Swipe end X"),
            "y2" to AgentToolParam("integer", "Swipe end Y"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down", "left", "right")),
            "key" to AgentToolParam("string", "Global key for the key action", enumValues = listOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS")),
            "clear" to AgentToolParam("boolean", "For input_text: replace the field content instead of setting it (default true behavior is set; pass append=true to append)"),
            "append" to AgentToolParam("boolean", "For input_text: append to existing field content"),
            "scale" to AgentToolParam("number", "Screenshot scale factor 0.05-1.0 (default 0.5)"),
            "limit" to AgentToolParam("integer", "Max nodes returned by dump_ui/find (default 80)"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "node_id", "text", "contains", "resource_id", "x", "y", "x1", "y1", "x2", "y2", "direction", "key", "clear", "append", "scale", "limit"),
    )

    /**
     * Execute one phone action. [sessionId] scopes safety overrides and
     * the screenshot offload dir; null falls back to the process-global
     * slot (interactive/diagnostic use).
     */
    fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid phone_use arguments", false)
        }
        val action = args.optString("action", "")
        val toolTitle = args.optString("tool_title", NAME)
        if (action !in actions) {
            return ToolExecutionResult("Error: unknown action '$action'. Valid: ${actions.joinToString()}", false, toolTitle = toolTitle)
        }

        // Tool-level permission first (a11y_cli tri-state). The phone gate
        // below only tightens on top of it.
        val sid = sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        val toolAllowed = runBlocking {
            OffloadPermissionManager.checkPermission("a11y_cli", "phone_use", sid)
        }
        if (!toolAllowed) {
            return ToolExecutionResult(
                "Error: phone control is not permitted. Enable it under Settings → Permissions → Integrations (android-a11y-cli).",
                false, toolTitle = toolTitle,
            )
        }

        val svc = MinisAccessibilityService.getInstance()
            ?: return ToolExecutionResult(
                "Error: accessibility service is not running. Enable Minis under Settings → Accessibility.",
                false, toolTitle = toolTitle,
            )

        // ── safety gate (same rules as the CLI paths) ──
        val gateAction = canonicalGateAction(action)
        if (gateAction != null) {
            val (pkg, _) = svc.foregroundPackage()
            val targetIsPassword = targetIsPassword(svc, args)
            val override = PhoneSafetyManager.hasOverride(sid, pkg)
            var (verdict, reason) = PhoneSafetyGate.decide(gateAction, pkg, targetIsPassword, override)
            if (verdict == PhoneSafetyGate.Verdict.DENY) {
                if (reason == PhoneSafetyGate.Reason.PASSWORD_FIELD) {
                    return ToolExecutionResult("Error: " + PhoneSafetyGate.denyMessage(reason, pkg), false, toolTitle = toolTitle)
                }
                val unlocked = pkg != null && runBlocking { PhoneSafetyManager.requestOverride(sid, pkg) }
                if (!unlocked) {
                    return ToolExecutionResult("Error: " + PhoneSafetyGate.denyMessage(reason!!, pkg), false, toolTitle = toolTitle)
                }
                val re = PhoneSafetyGate.decide(gateAction, pkg, targetIsPassword, true)
                verdict = re.first; reason = re.second
            }
            if (verdict == PhoneSafetyGate.Verdict.ASK) {
                val label = "Phone control: $action" + (pkg?.let { " in $it" } ?: "")
                val allowed = runBlocking { PhoneSafetyManager.requestActionApproval(sid, label) }
                if (!allowed) {
                    return ToolExecutionResult("Error: phone action '$action' was not approved by the user.", false, toolTitle = toolTitle)
                }
            }
        }

        return try {
            when (action) {
                "status" -> status(svc, toolTitle)
                "dump_ui" -> dumpUi(svc, args, toolTitle)
                "find" -> find(svc, args, toolTitle)
                "screenshot" -> screenshot(svc, args, sessionId, context, toolTitle)
                "tap", "long_press" -> tap(svc, args, long = action == "long_press", toolTitle)
                "input_text" -> inputText(svc, args, toolTitle)
                "clear_text" -> clearText(svc, args, toolTitle)
                "scroll" -> scroll(svc, args, toolTitle)
                "swipe" -> swipe(svc, args, toolTitle)
                "key" -> key(svc, args, toolTitle)
                else -> ToolExecutionResult("Error: unhandled action", false, toolTitle = toolTitle)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: ${t.javaClass.simpleName}: ${t.message ?: ""}", false, toolTitle = toolTitle)
        }
    }

    // ── gate helpers ─────────────────────────────────────────────────────

    private fun canonicalGateAction(action: String): String? = when (action) {
        "status" -> null
        "dump_ui", "find" -> "ui.dump"
        "screenshot" -> "ui.screenshot"
        "tap", "long_press" -> "tap"
        "input_text", "clear_text" -> "input.text"
        "scroll", "swipe" -> "scroll"
        "key" -> "input.key"
        else -> null
    }

    private fun targetIsPassword(svc: MinisAccessibilityService, args: JSONObject): Boolean {
        val node = resolveTargetNode(svc, args) ?: return false
        return node.isPassword
    }

    private fun resolveTargetNode(svc: MinisAccessibilityService, args: JSONObject): AccessibilityNodeInfo? {
        args.optString("node_id", "").takeIf { it.isNotEmpty() }?.let { return svc.nodeRegistry.get(it) }
        val action = args.optString("action", "")
        if (action == "input_text" || action == "clear_text") {
            for (root in svc.rootNodes()) {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            }
        }
        return null
    }

    // ── actions ──────────────────────────────────────────────────────────

    private fun status(svc: MinisAccessibilityService, title: String): ToolExecutionResult {
        val (pkg, cls) = svc.foregroundPackage()
        val out = JSONObject()
            .put("running", true)
            .put("foregroundPackage", pkg ?: "")
            .put("foregroundActivity", cls ?: "")
            .put("androidVersion", Build.VERSION.SDK_INT)
            .put("screenshotSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        return ToolExecutionResult(out.toString(2), true, toolTitle = title)
    }

    private fun dumpUi(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val limit = args.optInt("limit", 80).coerceIn(1, 500)
        val arr = JSONArray()
        for (root in svc.rootNodes()) {
            walk(root, 0, 12, arr, limit)
            if (arr.length() >= limit) break
        }
        val out = JSONObject().put("count", arr.length()).put("nodes", arr)
        return ToolExecutionResult(out.toString(2), true, toolTitle = title)
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int, out: JSONArray, limit: Int) {
        if (node == null || depth > maxDepth || out.length() >= limit) return
        if (node.isVisibleToUser) out.put(nodeJson(node, depth))
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, maxDepth, out, limit)
            if (out.length() >= limit) return
        }
    }

    private fun nodeJson(n: AccessibilityNodeInfo, depth: Int): JSONObject {
        val rect = Rect(); n.getBoundsInScreen(rect)
        return JSONObject()
            .put("nodeId", MinisAccessibilityService.getInstance()!!.nodeRegistry.put(n))
            .put("text", n.text?.toString() ?: "")
            .put("contentDesc", n.contentDescription?.toString() ?: "")
            .put("resourceId", n.viewIdResourceName ?: "")
            .put("packageName", n.packageName?.toString() ?: "")
            .put("className", n.className?.toString() ?: "")
            .put("center", JSONObject().put("x", rect.centerX()).put("y", rect.centerY()))
            .put("clickable", n.isClickable)
            .put("editable", n.isEditable)
            .put("isPassword", n.isPassword)
            .put("scrollable", n.isScrollable)
            .put("focused", n.isFocused)
            .put("depth", depth)
    }

    private fun find(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val text = args.optString("text", "")
        val rid = args.optString("resource_id", "")
        if (text.isEmpty() && rid.isEmpty()) {
            return ToolExecutionResult("Error: find requires text or resource_id", false, toolTitle = title)
        }
        val contains = args.optBoolean("contains", false)
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in svc.rootNodes()) {
            findMatch(root, text, contains, rid, 30, 0, matches, limit)
            if (matches.size >= limit) break
        }
        val arr = JSONArray()
        for (m in matches) arr.put(nodeJson(m, 0))
        return ToolExecutionResult(JSONObject().put("count", arr.length()).put("nodes", arr).toString(2), true, toolTitle = title)
    }

    private fun findMatch(
        node: AccessibilityNodeInfo?, text: String, contains: Boolean, rid: String,
        maxDepth: Int, depth: Int, out: MutableList<AccessibilityNodeInfo>, limit: Int,
    ) {
        if (node == null || depth > maxDepth || out.size >= limit) return
        val textOk = text.isEmpty() || run {
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (contains) t.contains(text) || d.contains(text) else t == text || d == text
        }
        val ridOk = rid.isEmpty() || node.viewIdResourceName == rid
        if (textOk && ridOk) out.add(node)
        for (i in 0 until node.childCount) findMatch(node.getChild(i), text, contains, rid, maxDepth, depth + 1, out, limit)
    }

    private fun screenshot(
        svc: MinisAccessibilityService, args: JSONObject,
        sessionId: String?, context: Context, title: String,
    ): ToolExecutionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolExecutionResult("Error: screenshot requires Android 11+ (API 30).", false, toolTitle = title)
        }
        val scale = args.optDouble("scale", 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val shot = svc.captureScreenshot(Display.DEFAULT_DISPLAY)
        val raw = shot.bitmap
            ?: return ToolExecutionResult("Error: screenshot failed: ${shot.errorCode} ${shot.errorMessage ?: ""}", false, toolTitle = title)
        val scaled = if (scale == 1.0f) raw else {
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        }
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        val w = scaled.width; val h = scaled.height
        scaled.recycle()

        // Persist into the session offloads dir so read_image / shell can
        // address it via /var/minis/offloads/phone/<file>.
        var linuxPath: String? = null
        var hostPath: String? = null
        if (sessionId != null) {
            val dir = File(context.filesDir, "minis-sessions/$sessionId/offloads/phone")
            dir.mkdirs()
            val file = File(dir, "phone_${System.currentTimeMillis()}.png")
            runCatching { FileOutputStream(file).use { it.write(bytes) } }
                .onSuccess {
                    hostPath = file.absolutePath
                    linuxPath = "/var/minis/offloads/phone/${file.name}"
                }
        }
        val meta = JSONObject()
            .put("width", w).put("height", h).put("sizeBytes", bytes.size)
            .apply {
                if (linuxPath != null) put("path", linuxPath)
                put("note", "Image returned inline. Re-read later with read_image at the path above.")
            }
        return ToolExecutionResult(
            output = meta.toString(2),
            success = true,
            imageData = bytes,
            imageMimeType = "image/png",
            toolTitle = title,
            imageFilePath = hostPath,
            imageLinuxPath = linuxPath,
        )
    }

    private fun tap(svc: MinisAccessibilityService, args: JSONObject, long: Boolean, title: String): ToolExecutionResult {
        val node = resolveTapTarget(svc, args)
        if (node != null) {
            val rect = Rect(); node.getBoundsInScreen(rect)
            if (node.isClickable && !long) {
                return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    ToolExecutionResult(JSONObject().put("tapped", "node").put("nodeId", args.optString("node_id")).toString(), true, toolTitle = title)
                else tapAt(svc, rect.centerX(), rect.centerY(), long, title)
            }
            if (long && node.isLongClickable) {
                return if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                    ToolExecutionResult(JSONObject().put("tapped", "long-node").toString(), true, toolTitle = title)
                else tapAt(svc, rect.centerX(), rect.centerY(), long, title)
            }
            return tapAt(svc, rect.centerX(), rect.centerY(), long, title)
        }
        val x = args.optInt("x", -1); val y = args.optInt("y", -1)
        if (x < 0 || y < 0) {
            return ToolExecutionResult("Error: tap needs node_id, text, or x+y", false, toolTitle = title)
        }
        return tapAt(svc, x, y, long, title)
    }

    private fun resolveTapTarget(svc: MinisAccessibilityService, args: JSONObject): AccessibilityNodeInfo? {
        args.optString("node_id", "").takeIf { it.isNotEmpty() }?.let { id ->
            return svc.nodeRegistry.get(id)
        }
        val text = args.optString("text", "")
        if (text.isNotEmpty()) {
            val contains = args.optBoolean("contains", false)
            val matches = ArrayList<AccessibilityNodeInfo>()
            for (root in svc.rootNodes()) findMatch(root, text, contains, "", 30, 0, matches, 1)
            return matches.firstOrNull()
        }
        return null
    }

    private fun tapAt(svc: MinisAccessibilityService, x: Int, y: Int, long: Boolean, title: String): ToolExecutionResult {
        val duration = if (long) 1000L else 50L
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 0.1f, y + 0.1f) }
        return if (svc.dispatchSimpleGesture(path, 0L, duration))
            ToolExecutionResult(JSONObject().put("tapped", "$x,$y").put("long", long).toString(), true, toolTitle = title)
        else ToolExecutionResult("Error: tap gesture cancelled/timed out at $x,$y", false, toolTitle = title)
    }

    private fun inputText(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val text = args.optString("text", "")
        if (text.isEmpty()) return ToolExecutionResult("Error: input_text needs text", false, toolTitle = title)
        val node = resolveTargetNode(svc, args)
            ?: return ToolExecutionResult("Error: no editable focus and no node_id", false, toolTitle = title)
        if (node.isPassword) {
            return ToolExecutionResult("Error: " + PhoneSafetyGate.denyMessage(PhoneSafetyGate.Reason.PASSWORD_FIELD, null), false, toolTitle = title)
        }
        val finalText = if (args.optBoolean("append", false)) (node.text?.toString() ?: "") + text else text
        return if (svc.setNodeText(node, finalText))
            ToolExecutionResult(JSONObject().put("typed", finalText.length).put("action", "setText").toString(), true, toolTitle = title)
        else ToolExecutionResult("Error: ACTION_SET_TEXT failed (node may not be editable)", false, toolTitle = title)
    }

    private fun clearText(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val node = resolveTargetNode(svc, args)
            ?: return ToolExecutionResult("Error: no editable focus and no node_id", false, toolTitle = title)
        if (node.isPassword) {
            return ToolExecutionResult("Error: " + PhoneSafetyGate.denyMessage(PhoneSafetyGate.Reason.PASSWORD_FIELD, null), false, toolTitle = title)
        }
        return if (svc.setNodeText(node, ""))
            ToolExecutionResult(JSONObject().put("action", "clear").toString(), true, toolTitle = title)
        else ToolExecutionResult("Error: clear failed", false, toolTitle = title)
    }

    private fun scroll(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val direction = args.optString("direction", "down")
        val node = args.optString("node_id", "").takeIf { it.isNotEmpty() }?.let { svc.nodeRegistry.get(it) }
        if (node != null && node.isScrollable) {
            val action = if (direction == "down" || direction == "right")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return if (node.performAction(action))
                ToolExecutionResult(JSONObject().put("scrolled", direction).put("via", "node").toString(), true, toolTitle = title)
            else ToolExecutionResult("Error: scroll action rejected", false, toolTitle = title)
        }
        val x = args.optInt("x", -1); val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return ToolExecutionResult("Error: scroll needs node_id or x+y", false, toolTitle = title)
        val distance = 500
        val (dx, dy) = when (direction) {
            "down" -> 0 to -distance; "up" -> 0 to distance
            "left" -> distance to 0; "right" -> -distance to 0
            else -> return ToolExecutionResult("Error: bad direction", false, toolTitle = title)
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo((x + dx).toFloat(), (y + dy).toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, 300L))
            ToolExecutionResult(JSONObject().put("scrolled", direction).put("via", "gesture").toString(), true, toolTitle = title)
        else ToolExecutionResult("Error: scroll gesture cancelled", false, toolTitle = title)
    }

    private fun swipe(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val x1 = args.optInt("x1", -1); val y1 = args.optInt("y1", -1)
        val x2 = args.optInt("x2", -1); val y2 = args.optInt("y2", -1)
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return ToolExecutionResult("Error: swipe needs x1,y1,x2,y2", false, toolTitle = title)
        }
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return if (svc.dispatchSimpleGesture(path, 0L, 300L))
            ToolExecutionResult(JSONObject().put("swiped", "$x1,$y1 -> $x2,$y2").toString(), true, toolTitle = title)
        else ToolExecutionResult("Error: swipe cancelled", false, toolTitle = title)
    }

    private fun key(svc: MinisAccessibilityService, args: JSONObject, title: String): ToolExecutionResult {
        val key = args.optString("key", "").uppercase()
        val action = when (key) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return ToolExecutionResult("Error: key must be BACK/HOME/RECENTS/NOTIFICATIONS", false, toolTitle = title)
        }
        svc.performGlobalAction(action)
        return ToolExecutionResult(JSONObject().put("key", key).toString(), true, toolTitle = title)
    }
}