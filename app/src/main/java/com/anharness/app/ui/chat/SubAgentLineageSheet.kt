package com.anharness.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anharness.app.ui.theme.ChatColors

/**
 * [T-subagent-app-wiring] Graphical lineage tree for sub-agent spawns (P3
 * polish, dsh-Go lineage-tree parity). Renders the structured payload the
 * /subagent command attaches to its system-info card: each node indented by
 * depth, with a status dot (running/done/failed), task label, and duration.
 *
 * Pure presentation over a JSON payload — no registry dependency, so it also
 * works for persisted historical cards.
 */
@Composable
internal fun SubAgentLineageSheet(
    payload: String,
    onDismiss: () -> Unit,
) {
    val nodes = remember(payload) { SubAgentLineageParser.parse(payload) }

    StandardChatSheet(title = "Sub-agent Lineage", onDismiss = onDismiss) {
        if (nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sub-agents recorded.", color = ChatColors.secondaryText, fontSize = 14.sp)
            }
            return@StandardChatSheet
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            nodes.forEach { node ->
                LineageRow(node)
            }
        }
    }
}

@Composable
private fun LineageRow(node: SubAgentLineageParser.Node) {
    val indent = ((node.depth - 1).coerceAtLeast(0) * 20).dp
    val statusColor = when (node.ok) {
        null -> Color(0xFFFF9500)   // running — amber
        true -> Color(0xFF34C759)   // done — green
        false -> Color(0xFFFF3B30)  // failed — red
    }
    val statusLabel = when (node.ok) {
        null -> "running"
        true -> "done"
        false -> "failed"
    }
    val duration = node.finishedAt?.let { fin ->
        val ms = (fin - node.startedAt).coerceAtLeast(0)
        if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Depth connector: a small hub glyph for parents, a dot for leaves.
        Icon(
            imageVector = Icons.Default.Hub,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.task.ifBlank { "(no task)" },
                fontSize = 13.sp,
                color = ChatColors.primaryText,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                text = "depth ${node.depth} · $statusLabel" + (duration?.let { " · $it" } ?: ""),
                fontSize = 11.sp,
                color = ChatColors.secondaryText,
            )
        }
    }
}