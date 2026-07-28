package com.neulketing.openthumb.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neulketing.openthumb.sync.SyncClient
import com.neulketing.openthumb.sync.SyncManager
import com.neulketing.openthumb.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-thumb-sync-v1] Settings page for the optional BYO-backend sync
 * (tools/sync-worker/). Strings are English literals, matching the trigger
 * UI (ui/trigger/NotificationTriggersScreen.kt).
 *
 * Fields persist on edit (no Save button). "Test connection" and
 * "Sync now" both run off the main thread; "Sync now" pushes the three v1
 * kinds (trigger_rule / trigger_run / scheduled_task) outbound only and
 * reports the result inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SyncSettings(context) }

    var url by remember { mutableStateOf(settings.workerUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var busy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    SettingsScaffold(title = "Sync", onBack = onBack) {
        SettingsSection(
            header = "Server",
            footer = "Syncs to your own server. Off by default. Nothing leaves " +
                "this device until you enter a URL and token here.",
        ) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        settings.workerUrl = it
                    },
                    label = { Text("Worker URL") },
                    placeholder = { Text("https://openthumb-sync.you.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        settings.token = it
                    },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SettingsSection(header = "Sync") {
            SettingsSwitchRow(
                title = "Enable sync",
                subtitle = "Uploads trigger rules, trigger run history and scheduled " +
                    "tasks to your server. Chats are not synced.",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.enabled = it
                },
                showDivider = false,
            )
        }

        SettingsSection {
            SettingsCardBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                testResult = null
                                val ok = withContext(Dispatchers.IO) {
                                    SyncClient(url.trim(), token.trim()).testConnection()
                                }
                                testResult = if (ok) "Connection OK" else "Connection failed"
                                busy = false
                            }
                        },
                        enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Test connection") }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                syncResult = null
                                val report = withContext(Dispatchers.IO) {
                                    SyncManager.create(context).syncNow()
                                }
                                val time = timeFormat.format(Date())
                                syncResult = if (report.ok) {
                                    "${report.totalPushed} items pushed, $time"
                                } else {
                                    "${report.totalPushed} pushed, failed: " +
                                        report.failedKinds.joinToString()
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Sync now") }
                }
                if (testResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = testResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult == "Connection OK") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (syncResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = syncResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
