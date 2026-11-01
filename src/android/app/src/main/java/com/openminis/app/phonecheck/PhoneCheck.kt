package com.openminis.app.phonecheck

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The first thing an install shows: what this phone looks like right now.
 *
 * An agent app opens on an empty chat, and nobody knows what to type into it.
 * This needs no model, no key and no permission, so it has something to say
 * thirty seconds after install, and every finding is a job the agent can then
 * be handed. Reads only; nothing here leaves the phone.
 */
object PhoneCheck {

    enum class Level { OK, WARN, BAD }

    data class Facts(
        val freeBytes: Long,
        val totalBytes: Long,
        /** Null when the battery will not report its health. */
        val batteryGood: Boolean?,
        val securityPatch: String,
        val readsScreen: List<String>,
        val readsNotifications: List<String>,
        val deviceAdmins: List<String>,
    )

    data class Finding(
        val id: String,
        val level: Level,
        @StringRes val title: Int,
        @StringRes val detail: Int,
        val args: List<Any> = emptyList(),
        val fixPrompt: String? = null,
    )

    private const val NO_CHANGE = "Do not change, delete or uninstall anything until I approve each step."

    fun read(context: Context): Facts {
        val stat = StatFs(Environment.getDataDirectory().path)
        val health = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val screen = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').map { it.substringBefore('/') }
        return Facts(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            batteryGood = when (health) {
                BatteryManager.BATTERY_HEALTH_UNKNOWN -> null
                else -> health == BatteryManager.BATTERY_HEALTH_GOOD
            },
            securityPatch = android.os.Build.VERSION.SECURITY_PATCH.orEmpty(),
            readsScreen = labels(context, screen),
            readsNotifications = labels(context, NotificationManagerCompat.getEnabledListenerPackages(context)),
            deviceAdmins = labels(context, dpm?.activeAdmins.orEmpty().map { it.packageName }),
        )
    }

    /** Third-party apps only, ours excluded: the system's own services are not news. */
    private fun labels(context: Context, packages: Collection<String>): List<String> {
        val pm = context.packageManager
        return packages.filter { it.isNotBlank() && it != context.packageName }.distinct().mapNotNull { pkg ->
            // A package hidden from us by package visibility still counts: show its id.
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull pkg
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) null else pm.getApplicationLabel(info).toString()
        }.sorted()
    }

    fun judge(f: Facts, today: LocalDate): List<Finding> = buildList {
        val total = f.totalBytes.coerceAtLeast(1)
        val freePct = (f.freeBytes * 100 / total).toInt()
        add(
            Finding(
                id = "storage",
                level = when {
                    freePct <= 5 -> Level.BAD
                    freePct <= 15 -> Level.WARN
                    else -> Level.OK
                },
                title = R.string.phone_check_storage,
                detail = R.string.phone_check_storage_detail,
                args = listOf(freePct, f.freeBytes / GB, f.totalBytes / GB),
                fixPrompt = "My phone has ${f.freeBytes / GB} GB free of ${f.totalBytes / GB} GB. Find what " +
                    "is taking the space that you can see from here and show me the largest items as a " +
                    "short list. $NO_CHANGE",
            ),
        )
        val patchAge = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(f.securityPatch), today) }.getOrNull()
        if (patchAge != null) {
            add(
                Finding(
                    id = "patch",
                    level = when {
                        patchAge > 365 -> Level.BAD
                        patchAge > 180 -> Level.WARN
                        else -> Level.OK
                    },
                    title = R.string.phone_check_patch,
                    detail = R.string.phone_check_patch_detail,
                    args = listOf(patchAge, f.securityPatch),
                    fixPrompt = "My security patch level is ${f.securityPatch}. Tell me how to check for an " +
                        "update on this phone, step by step. $NO_CHANGE",
                ),
            )
        }
        if (f.batteryGood != null) {
            add(
                Finding(
                    id = "battery",
                    level = if (f.batteryGood) Level.OK else Level.WARN,
                    title = if (f.batteryGood) R.string.phone_check_battery_good else R.string.phone_check_battery_bad,
                    detail = R.string.phone_check_battery_detail,
                ),
            )
        }
        access("screen", f.readsScreen, R.string.phone_check_screen, R.string.phone_check_screen_none, "read my screen through accessibility")
        access("notifications", f.readsNotifications, R.string.phone_check_notifications, R.string.phone_check_notifications_none, "read all my notifications")
        access("admins", f.deviceAdmins, R.string.phone_check_admins, R.string.phone_check_admins_none, "act as a device administrator")
    }

    private fun MutableList<Finding>.access(
        id: String,
        apps: List<String>,
        @StringRes some: Int,
        @StringRes none: Int,
        power: String,
    ) {
        if (apps.isEmpty()) {
            add(Finding(id, Level.OK, none, R.string.phone_check_read_here))
            return
        }
        val names = apps.joinToString(", ")
        add(
            Finding(
                id = id,
                level = Level.WARN,
                title = some,
                detail = R.string.phone_check_apps_detail,
                args = listOf(apps.size, names),
                fixPrompt = "These apps can $power: $names. For each one tell me why it would normally " +
                    "need that, and how to turn it off if I do not recognise it. $NO_CHANGE",
            ),
        )
    }

    fun score(findings: List<Finding>): Int =
        (100 - findings.sumOf {
            when (it.level) {
                Level.OK -> 0
                Level.WARN -> WARN_COST
                Level.BAD -> BAD_COST
            }.toInt()
        }).coerceAtLeast(0)

    /** Hands the finding to the agent in a new chat; returns its session id. */
    suspend fun fix(context: Context, finding: Finding, label: String): String? {
        val prompt = finding.fixPrompt ?: return null
        val task = ScheduledTask(
            id = "check-${finding.id}",
            label = label,
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = prompt,
            targetMode = ScheduledTargetMode.NewSession,
        )
        return ScheduledAgentRunner.run(context, task, waitForCompletion = false)
    }

    private const val GB = 1_000_000_000L
    private const val WARN_COST = 8
    private const val BAD_COST = 25
}
