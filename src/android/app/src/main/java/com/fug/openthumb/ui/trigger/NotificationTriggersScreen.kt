package com.fug.openthumb.ui.trigger

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fug.openthumb.offload.MinisNotificationListenerService
import com.fug.openthumb.trigger.NotificationTriggerEngine
import com.fug.openthumb.trigger.NotificationTriggerRule
import com.fug.openthumb.trigger.NotificationTriggerRunStore
import com.fug.openthumb.trigger.NotificationTriggerStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-thumb-notification-triggers] List + manage notification trigger rules.
 * Reached from the Scheduled tasks screen's top bar. Tap a row to edit,
 * FAB to create, switch to enable/disable, trash to delete (confirmed).
 * A banner surfaces when Notification Access has not been granted.
 *
 * ponytail: UI strings are English literals for now — move to string
 * resources when the fork grows a localization pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationTriggersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { NotificationTriggerStore(context) }
    val runStore = remember { NotificationTriggerRunStore(context) }
    val rules by store.observe().collectAsState(initial = store.all())
    var editing by remember { mutableStateOf<NotificationTriggerRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NotificationTriggerRule?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showQuietHours by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Notification triggers", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showQuietHours = true }) {
                        Icon(Icons.Filled.NightsStay, contentDescription = "Quiet hours")
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "Run history")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = "New trigger rule")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!MinisNotificationListenerService.isEnabled(context)) {
                AccessBanner(
                    onOpenSettings = {
                        context.startActivity(
                            Intent(MinisNotificationListenerService.SETTINGS_ACTION)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            if (store.quietHoursEnabled()) {
                Card(
                    onClick = { showQuietHours = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.NightsStay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Quiet hours ${formatMin(store.quietStartMin)} – ${formatMin(store.quietEndMin)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (rules.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No trigger rules yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A rule runs the agent when a matching notification " +
                            "arrives — e.g. summarise every message from a chat app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(rules.sortedBy { it.createdAt }, key = { it.id }) { rule ->
                        RuleRow(
                            rule = rule,
                            onClick = { editing = rule },
                            onToggle = { on -> store.upsert(rule.copy(enabled = on)) },
                            onDelete = { pendingDelete = rule },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        RuleEditDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { rule ->
                store.upsert(rule)
                creating = false
                editing = null
            },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete rule?") },
            text = { Text("\"${rule.label}\" will stop firing. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(rule.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showHistory) {
        RunHistoryDialog(runStore = runStore, onDismiss = { showHistory = false })
    }

    if (showQuietHours) {
        QuietHoursDialog(store = store, onDismiss = { showQuietHours = false })
    }
}

/** "23:00" style label for a minutes-of-day value; "…" for an open end. */
private fun formatMin(min: Int?): String =
    if (min == null) "…" else "%02d:%02d".format(min / 60, min % 60)

/** "09:00" → 540, blank/garbage → null. */
private fun parseMin(raw: String): Int? {
    val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(raw.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h * 60 + min else null
}

@Composable
private fun QuietHoursDialog(store: NotificationTriggerStore, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(store.quietHoursEnabled()) }
    var start by remember { mutableStateOf(formatMin(store.quietStartMin).takeIf { it != "…" } ?: "22:00") }
    var end by remember { mutableStateOf(formatMin(store.quietEndMin).takeIf { it != "…" } ?: "07:00") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet hours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No triggers fire inside this window", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Start (HH:MM)") },
                    singleLine = true,
                    enabled = enabled,
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("End (HH:MM)") },
                    singleLine = true,
                    enabled = enabled,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled) {
                    store.quietStartMin = parseMin(start)
                    store.quietEndMin = parseMin(end)
                } else {
                    store.quietStartMin = null
                    store.quietEndMin = null
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RunHistoryDialog(runStore: NotificationTriggerRunStore, onDismiss: () -> Unit) {
    val runs by runStore.observe().collectAsState(initial = runStore.all())
    val timeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run history") },
        text = {
            if (runs.isEmpty()) {
                Text(
                    "No runs yet. Runs land here when a rule fires — " +
                        "including test runs from the rule editor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(runs, key = { it.ruleId + it.firedAt }) { run ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                run.ruleLabel.ifBlank { "(unnamed rule)" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(timeFmt.format(Date(run.firedAt)))
                                    append(" · ")
                                    append(run.pkg)
                                    if (!run.ok) append(" · failed to launch")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (run.title.isNotBlank()) {
                                Text(
                                    run.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AccessBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Notification access is off",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Triggers cannot see notifications until you grant " +
                    "Notification access to OpenThumb.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun RuleRow(
    rule: NotificationTriggerRule,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rule.label.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(rule.appPackage ?: "any app")
                        val needle = rule.matchContains?.takeIf { it.isNotBlank() }
                        if (needle != null) append(" · contains \"$needle\"")
                        if (rule.activeStartMin != null || rule.activeEndMin != null) {
                            append(" · ${formatMin(rule.activeStartMin)}–${formatMin(rule.activeEndMin)}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuleEditDialog(
    initial: NotificationTriggerRule?,
    onDismiss: () -> Unit,
    onSave: (NotificationTriggerRule) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var appPackage by remember { mutableStateOf(initial?.appPackage ?: "") }
    var matchContains by remember { mutableStateOf(initial?.matchContains ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var cooldown by remember {
        mutableStateOf((initial?.cooldownSec ?: NotificationTriggerRule.DEFAULT_COOLDOWN_SEC).toString())
    }
    var activeStart by remember { mutableStateOf(initial?.activeStartMin?.let { formatMin(it) } ?: "") }
    var activeEnd by remember { mutableStateOf(initial?.activeEndMin?.let { formatMin(it) } ?: "") }

    fun buildRule(): NotificationTriggerRule {
        val base = initial ?: NotificationTriggerRule(label = "", prompt = "")
        return base.copy(
            label = label.trim(),
            appPackage = appPackage.trim().ifBlank { null },
            matchContains = matchContains.trim().ifBlank { null },
            prompt = prompt.trim(),
            cooldownSec = cooldown.toIntOrNull()
                ?: NotificationTriggerRule.DEFAULT_COOLDOWN_SEC,
            activeStartMin = parseMin(activeStart),
            activeEndMin = parseMin(activeEnd),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New trigger rule" else "Edit trigger rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = appPackage,
                    onValueChange = { appPackage = it },
                    label = { Text("App package (empty = any)") },
                    placeholder = { Text("com.kakao.talk") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = matchContains,
                    onValueChange = { matchContains = it },
                    label = { Text("Text contains (empty = all)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Agent prompt — {app} {title} {text}") },
                    minLines = 3,
                )
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit) },
                    label = { Text("Cooldown seconds") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = activeStart,
                        onValueChange = { activeStart = it },
                        label = { Text("Active from") },
                        placeholder = { Text("HH:MM") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = activeEnd,
                        onValueChange = { activeEnd = it },
                        label = { Text("Active until") },
                        placeholder = { Text("HH:MM") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank(),
                onClick = { onSave(buildRule()) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = prompt.isNotBlank(),
                    onClick = {
                        NotificationTriggerEngine.testFire(context, buildRule())
                        Toast.makeText(
                            context,
                            "Test run started — see Run history",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
