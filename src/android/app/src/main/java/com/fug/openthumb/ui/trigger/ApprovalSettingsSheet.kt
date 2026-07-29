package com.fug.openthumb.ui.trigger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fug.openthumb.R
import com.fug.openthumb.trigger.ApprovalNotifier
import com.fug.openthumb.trigger.OutboundApproval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Choosing when to be asked before the agent answers someone for you.
 *
 * The three modes are written as sentences rather than switch labels, because
 * the difference between them is a difference in consequence, not in
 * configuration — "Never ask" is a reasonable choice for a rule that only files
 * your own bank alerts, and a bad one for a rule that answers your messages.
 * The screen says which is which instead of leaving it to be discovered.
 */
@Composable
fun ApprovalSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(OutboundApproval.mode(context)) }
    var expiry by remember { mutableStateOf(OutboundApproval.expiryMinutes(context)) }
    val waiting = remember(mode) { OutboundApproval.pending(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringOf(R.string.approval_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChoice(
                    selected = mode == OutboundApproval.Mode.ALWAYS,
                    title = stringOf(R.string.approval_mode_always),
                    detail = stringOf(R.string.approval_mode_always_desc),
                ) { mode = OutboundApproval.Mode.ALWAYS; OutboundApproval.setMode(context, mode) }

                ModeChoice(
                    selected = mode == OutboundApproval.Mode.ALLOWLIST,
                    title = stringOf(R.string.approval_mode_allowlist),
                    detail = stringOf(R.string.approval_mode_allowlist_desc),
                ) { mode = OutboundApproval.Mode.ALLOWLIST; OutboundApproval.setMode(context, mode) }

                ModeChoice(
                    selected = mode == OutboundApproval.Mode.NEVER,
                    title = stringOf(R.string.approval_mode_never),
                    detail = stringOf(R.string.approval_mode_never_desc),
                ) { mode = OutboundApproval.Mode.NEVER; OutboundApproval.setMode(context, mode) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text(
                    stringOf(R.string.approval_expiry_title) + ": $expiry min",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringOf(R.string.approval_expiry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(10, 30, 60, 180)) {
                        TextButton(onClick = {
                            expiry = m
                            OutboundApproval.setExpiryMinutes(context, m)
                        }) {
                            Text(
                                "${m}m",
                                fontWeight = if (expiry == m) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                if (waiting.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringOf(R.string.approval_pending_title) + " (${waiting.size})",
                        fontWeight = FontWeight.Medium,
                    )
                    // Re-posting is the recovery for a notification the user
                    // swiped from the shade before deciding: the draft is still
                    // queued, but nothing on screen says so.
                    TextButton(onClick = {
                        for (p in waiting) ApprovalNotifier.post(context, p)
                    }) { Text("Show them again") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ModeChoice(
    selected: Boolean,
    title: String,
    detail: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every draft and every decision, newest first — including the ones nobody
 * approved. A record that only holds what was sent cannot answer the question
 * people actually have, which is what it tried to send.
 */
@Composable
fun ApprovalLedgerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows = remember { OutboundApproval.ledger(context) }
    val stamp = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringOf(R.string.approval_ledger_title)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringOf(R.string.approval_ledger_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    "${stamp.format(Date(row.optLong("at")))}  ·  " +
                                        row.optString("outcome"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                val to = row.optString("conversation")
                                if (to.isNotBlank()) {
                                    Text(
                                        to,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(row.optString("draft"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun stringOf(id: Int): String = androidx.compose.ui.res.stringResource(id)
