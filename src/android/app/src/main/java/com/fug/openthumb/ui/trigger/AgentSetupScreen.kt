package com.fug.openthumb.ui.trigger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fug.openthumb.accessibility.MinisAccessibilityService
import com.fug.openthumb.offload.MinisNotificationListenerService
import com.fug.openthumb.power.PowerOptimizationManager
import com.fug.openthumb.trigger.NotificationTriggerRule
import com.fug.openthumb.trigger.NotificationTriggerStore

/**
 * [T-thumb-agent-setup] One screen that takes a fresh install to a phone that
 * answers its own notifications.
 *
 * The fork's one-line promise — "it answers in the app the message came from" —
 * was previously unreachable: the permissions it needs live on three different
 * system pages, and the rule that uses them had to be typed into seven free-text
 * fields with a package name the user was told to fetch over adb. This screen
 * walks the same ground in taps, re-reading every gate on ON_RESUME so returning
 * from a system page ticks the step off without the user reporting back.
 *
 * Android does not let an app grant itself Notification Access, battery
 * exemption, or (on 13+) permission to post notifications. "Automatic" here
 * means the app knows what it needs, opens the exact page for each one, and
 * notices when it is done — not that the grants happen without the user.
 *
 * ponytail: UI strings are English literals, matching the rest of this package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSetupScreen(onBack: () -> Unit, onOpenTriggers: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember { NotificationTriggerStore(context) }

    var notifAccess by remember { mutableStateOf(MinisNotificationListenerService.isEnabled(context)) }
    var canPost by remember { mutableStateOf(canPostNotifications(context)) }
    var batteryFree by remember {
        mutableStateOf(PowerOptimizationManager.isIgnoringBatteryOptimizations(context))
    }
    var canDrive by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var ruleCount by remember { mutableStateOf(store.all().size) }
    var pickingApp by remember { mutableStateOf(false) }

    // Every gate below is granted on a system page we do not control, so the
    // only reliable moment to re-read them is when this screen comes back to
    // the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Seeing it once is enough to stop it claiming cold start; the entry
        // point on the welcome screen stays for anyone who wants it again.
        AgentSetupPrefs.markSeen(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAccess = MinisNotificationListenerService.isEnabled(context)
                canPost = canPostNotifications(context)
                batteryFree = PowerOptimizationManager.isIgnoringBatteryOptimizations(context)
                canDrive = isAccessibilityEnabled(context)
                ruleCount = store.all().size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val postPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { canPost = canPostNotifications(context) }

    val oemStep = PowerOptimizationManager.needsOemAutostartGuidance()
    val ready = notifAccess && canPost && ruleCount > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up the agent", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (ready) "This phone is answering for you." else "A few taps to a phone that answers.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Each step opens the system page it needs. Come back and it ticks itself off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            SetupGate(
                number = 1,
                title = "Let it read notifications",
                subtitle = "Notification Access — how the agent sees a message arrive",
                done = notifAccess,
                onClick = {
                    context.startActivity(
                        Intent(MinisNotificationListenerService.SETTINGS_ACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )

            SetupGate(
                number = 2,
                title = "Let it show you drafts",
                subtitle = "Notifications — a reply waits here for your approval before it sends",
                done = canPost,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        postPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (activity != null) {
                        PowerOptimizationManager.openAppDetailsSettings(activity)
                    }
                },
            )

            // The phone-side answer to "use the session I am already signed in
            // to": with this on, the agent can read the screen and tap, so it
            // acts inside apps you are logged into instead of needing an API
            // for each one. Optional and off by default — it is the widest
            // permission here, and the reply flow works without it.
            SetupGate(
                number = 3,
                title = "Let it use your apps",
                subtitle = "Accessibility — read the screen and tap on your behalf, inside apps you are already signed in to",
                done = canDrive,
                optional = true,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )

            SetupGate(
                number = 4,
                title = "Keep it awake",
                subtitle = "Battery optimisation exemption — otherwise the phone sleeps through messages",
                done = batteryFree,
                optional = true,
                onClick = {
                    if (activity != null) {
                        PowerOptimizationManager.requestBatteryOptimizationExemption(activity)
                    }
                },
            )

            if (oemStep) {
                SetupGate(
                    number = 5,
                    title = "Allow autostart",
                    subtitle = "This manufacturer stops background apps on its own. Allow it here.",
                    done = false,
                    optional = true,
                    onClick = {
                        if (activity != null) {
                            PowerOptimizationManager.openOemAutostartSettings(activity)
                        }
                    },
                )
            }

            SetupGate(
                number = if (oemStep) 6 else 5,
                title = "Pick an app to answer in",
                subtitle = if (ruleCount > 0) {
                    "$ruleCount rule${if (ruleCount == 1) "" else "s"} — tap to add another"
                } else {
                    "One tap makes the rule. No package names, no adb."
                },
                done = ruleCount > 0,
                alwaysClickable = true,
                onClick = { pickingApp = true },
            )

            if (ready) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Ready.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Send yourself a message in that app. The draft arrives as a " +
                                "notification and only sends when you tap Send.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenTriggers) { Text("Open trigger rules") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickingApp) {
        AppPickerDialog(
            onDismiss = { pickingApp = false },
            onPick = { pkg, appName ->
                store.upsert(newReplyRule(pkg, appName))
                ruleCount = store.all().size
                pickingApp = false
                Toast.makeText(context, "Rule added for $appName", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/**
 * Whether a fresh install should land on setup instead of a chat.
 *
 * Verified on a Galaxy Note20 (Android 13): after `pm clear`, a cold start goes
 * straight to an empty chat and the back key leaves the app — the sessions list
 * that carries the welcome steps is never on the back stack, so an onboarding
 * entry point placed only there is unreachable on the path a real install takes.
 */
internal object AgentSetupPrefs {
    private const val PREFS = "agent_setup"
    private const val KEY_SEEN = "seen"

    /** True only until the user has seen the screen once. */
    fun shouldOfferSetup(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false) &&
            !MinisNotificationListenerService.isEnabled(context)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
    }
}

/** A rule that answers in [appName], held for approval until the user trusts it. */
internal fun newReplyRule(pkg: String, appName: String): NotificationTriggerRule =
    NotificationTriggerRule(
        label = "Answer in $appName",
        appPackage = pkg,
        prompt = "A message arrived in $appName from {title}: \"{text}\"\n\n" +
            "Write a short, natural reply on my behalf. Reply in the same language as " +
            "the message. Output only the reply text — no preamble, no quotes.",
        // Two-way is the whole point of this screen; approval stays on because a
        // first rule the user has not seen fire yet should not send unattended.
        replyToNotification = true,
        requireApproval = true,
    )

private fun canPostNotifications(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Whether the agent's accessibility service is switched on. Compared against
 * [MinisAccessibilityService.SERVICE_ID] rather than a locally built string so
 * this cannot drift from what the service actually registers as.
 */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
    return enabled.split(':').any {
        it.equals(expected, ignoreCase = true) ||
            it.equals(MinisAccessibilityService.SERVICE_ID, ignoreCase = true)
    }
}

@Composable
private fun SetupGate(
    number: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    onClick: () -> Unit,
    optional: Boolean = false,
    alwaysClickable: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = alwaysClickable || !done, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (done) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text("$number", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title + if (optional) " (optional)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Messaging apps that expose a reply action on their notifications, filtered to
 * the ones actually installed. Replaces `adb shell pm list packages` in
 * docs/recipes.md — the step that made a consumer moment need a laptop.
 */
private val REPLY_CAPABLE_APPS: List<Pair<String, String>> = listOf(
    "com.kakao.talk" to "KakaoTalk",
    "jp.naver.line.android" to "LINE",
    "org.telegram.messenger" to "Telegram",
    "com.whatsapp" to "WhatsApp",
    "com.facebook.orca" to "Messenger",
    "com.samsung.android.messaging" to "Samsung Messages",
    "com.google.android.apps.messaging" to "Messages",
    "com.discord" to "Discord",
    "com.instagram.android" to "Instagram",
    "com.Slack" to "Slack",
)

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    val installed = remember {
        val pm = context.packageManager
        REPLY_CAPABLE_APPS.filter { (pkg, _) -> pm.getLaunchIntentForPackage(pkg) != null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (installed.isEmpty()) "No messaging app found" else "Answer in which app?") },
        text = {
            if (installed.isEmpty()) {
                Text(
                    "None of the messengers this works with are installed. Install one, " +
                        "or add a rule by hand in trigger rules.",
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    installed.forEach { (pkg, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg, name) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
