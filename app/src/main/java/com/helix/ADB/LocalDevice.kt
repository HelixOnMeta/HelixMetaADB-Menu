package com.helix

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.app.ActivityManager
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import android.app.NotificationManager

// contact blaku64th on discord if you have any issues ^^
object LocalDevice {

    private const val TAG = "LocalDevice"

    data class LocalBattery(
        val percent: Int?,
        val charging: Boolean,
        val source: String
    )

    fun readHostBattery(context: Context = AppContext.app): LocalBattery {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }

            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val stickyLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val fromSticky = if (stickyLevel >= 0 && scale > 0) {
                ((stickyLevel * 100f) / scale).toInt().coerceIn(0, 100)
            } else null

            val percent = level ?: fromSticky
            LocalBattery(
                percent = percent,
                charging = charging,
                source = if (percent != null) "local BatteryManager" else "unavailable"
            )
        } catch (e: Exception) {
            Log.w(TAG, "readHostBattery failed", e)
            LocalBattery(null, false, "error: ${e.message}")
        }
    }

    fun canWriteSettings(context: Context = AppContext.app): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    fun requestWriteSettings(context: Context = AppContext.app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestWriteSettings failed", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun getBrightness(context: Context = AppContext.app): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Exception) {
            128
        }
    }

    fun setBrightness(context: Context, value: Int): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value.coerceIn(1, 255)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness failed", e)
            false
        }
    }

    fun getScreenTimeoutMs(context: Context = AppContext.app): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        } catch (_: Exception) {
            60_000
        }
    }

    fun setScreenTimeoutMs(context: Context, ms: Int): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                ms.coerceAtLeast(5_000)
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "setScreenTimeoutMs failed", e)
            false
        }
    }

    fun setStayOnWhilePlugged(context: Context, on: Boolean): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                if (on) 7 else 0
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "setStayOnWhilePlugged failed (may need secure settings)", e)
            false
        }
    }

    fun isDeveloperOptionsEnabled(context: Context = AppContext.app): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun tryEnableDeveloperOptions(context: Context = AppContext.app): Boolean {
        if (isDeveloperOptionsEnabled(context)) return true
        return try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                1
            )
            isDeveloperOptionsEnabled(context)
        } catch (e: Exception) {
            Log.e(TAG, "tryEnableDeveloperOptions failed", e)
            false
        }
    }

    data class IntentProbeResult(val label: String, val action: String, val ok: Boolean, val error: String? = null)

    fun tryStartSettingsAction(context: Context, action: String, dataUri: String? = null): IntentProbeResult {
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (dataUri != null) intent.data = Uri.parse(dataUri)
            val ri = context.packageManager.resolveActivity(intent, 0)
            if (ri == null) {
                IntentProbeResult(action, action, false, "no activity")
            } else {
                context.startActivity(intent)
                IntentProbeResult(
                    label = action.substringAfterLast('.'),
                    action = action,
                    ok = true,
                    error = "target=${ri.activityInfo?.packageName}/${ri.activityInfo?.name}"
                )
            }
        } catch (e: Exception) {
            IntentProbeResult(action.substringAfterLast('.'), action, false, e.message)
        }
    }

    fun allSettingsProbeActions(context: Context = AppContext.app): List<Pair<String, String?>> {
        val pkg = context.packageName
        return listOf(
            "ACTION_SETTINGS" to Settings.ACTION_SETTINGS,
            "ACTION_WIFI_SETTINGS" to Settings.ACTION_WIFI_SETTINGS,
            "ACTION_WIRELESS_SETTINGS" to Settings.ACTION_WIRELESS_SETTINGS,
            "ACTION_AIRPLANE_MODE_SETTINGS" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "ACTION_BLUETOOTH_SETTINGS" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "ACTION_WIFI_IP_SETTINGS" to Settings.ACTION_WIFI_IP_SETTINGS,
            "ACTION_APPLICATION_DEVELOPMENT_SETTINGS" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "APPLICATION_DEVELOPMENT_SETTINGS (string)" to "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            "ACTION_DEVICE_INFO_SETTINGS" to Settings.ACTION_DEVICE_INFO_SETTINGS,
            "ACTION_ABOUT_DEVICE" to "android.settings.ABOUT_DEVICE",
            "ACTION_APPLICATION_DETAILS_SETTINGS" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "ACTION_MANAGE_OVERLAY_PERMISSION" to Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "ACTION_MANAGE_WRITE_SETTINGS" to Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "ACTION_APP_NOTIFICATION_SETTINGS" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "ACTION_LOCATION_SOURCE_SETTINGS" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "ACTION_SOUND_SETTINGS" to Settings.ACTION_SOUND_SETTINGS,
            "ACTION_DISPLAY_SETTINGS" to Settings.ACTION_DISPLAY_SETTINGS,
            "ACTION_DATE_SETTINGS" to Settings.ACTION_DATE_SETTINGS,
            "ACTION_LOCALE_SETTINGS" to Settings.ACTION_LOCALE_SETTINGS,
            "ACTION_INPUT_METHOD_SETTINGS" to Settings.ACTION_INPUT_METHOD_SETTINGS,
            "ACTION_SECURITY_SETTINGS" to Settings.ACTION_SECURITY_SETTINGS,
            "ACTION_PRIVACY_SETTINGS" to Settings.ACTION_PRIVACY_SETTINGS,
            "ACTION_STORAGE_SETTINGS" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "ACTION_MEMORY_CARD_SETTINGS" to Settings.ACTION_MEMORY_CARD_SETTINGS,
            "ACTION_ACCESSIBILITY_SETTINGS" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "ACTION_CAPTIONING_SETTINGS" to Settings.ACTION_CAPTIONING_SETTINGS,
            "ACTION_CAST_SETTINGS" to Settings.ACTION_CAST_SETTINGS,
            "ACTION_NFC_SETTINGS" to Settings.ACTION_NFC_SETTINGS,
            "ACTION_PRINT_SETTINGS" to Settings.ACTION_PRINT_SETTINGS,
            "ACTION_BATTERY_SAVER_SETTINGS" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "ACTION_USAGE_ACCESS_SETTINGS" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
            "ACTION_MANAGE_UNKNOWN_APP_SOURCES" to Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "ACTION_REQUEST_SCHEDULE_EXACT_ALARM" to "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            "WIRELESS_DEBUGGING_SETTINGS (unofficial)" to "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            "Settings\$DevelopmentSettingsActivity" to null,
            "Settings\$WirelessDebuggingActivity" to null,
            "Oculus Settings (legacy)" to "com.oculus.tv.settings.SETTINGS",
            "Horizon System Settings" to "com.oculus.systemux.action.SETTINGS",
            "vrshell settings" to "com.oculus.vrshell.SETTINGS"
        ).map { (label, action) -> label to action }
    }

    fun tryStartNamedSetting(context: Context, label: String, action: String?): IntentProbeResult {
        if (action == null) {
            val components = when {
                label.contains("WirelessDebugging") -> listOf(
                    "com.android.settings/com.android.settings.Settings\$WirelessDebuggingActivity",
                    "com.android.settings/.development.WirelessDebuggingActivity"
                )
                label.contains("DevelopmentSettings") -> listOf(
                    "com.android.settings/com.android.settings.Settings\$DevelopmentSettingsDashboardActivity",
                    "com.android.settings/.Settings\$DevelopmentSettingsActivity"
                )
                else -> emptyList()
            }
            for (cn in components) {
                try {
                    val parts = cn.split("/")
                    val intent = Intent().setClassName(parts[0], parts[1])
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (context.packageManager.resolveActivity(intent, 0) != null) {
                        context.startActivity(intent)
                        return IntentProbeResult(label, cn, true, "component")
                    }
                } catch (e: Exception) {
                }
            }
            return IntentProbeResult(label, "(component)", false, "no component")
        }
        val data = when (action) {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> "package:${context.packageName}"
            Settings.ACTION_APP_NOTIFICATION_SETTINGS -> null
            else -> null
        }
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
            return try {
                val intent = Intent(action)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                IntentProbeResult(label, action, true, null)
            } catch (e: Exception) {
                IntentProbeResult(label, action, false, e.message)
            }
        }
        return tryStartSettingsAction(context, action, data).copy(label = label)
    }

    fun openWirelessDebugging(context: Context = AppContext.app): String {
        val enabled = isDeveloperOptionsEnabled(context)
        if (!enabled) {
            val flipped = tryEnableDeveloperOptions(context)
            if (!flipped) {
                val aboutOpened = tryStartSettingsAction(
                    context,
                    Settings.ACTION_DEVICE_INFO_SETTINGS
                ).ok || tryStartSettingsAction(context, "android.settings.ABOUT_DEVICE").ok
                return if (aboutOpened) {
                    "Developer options OFF — opened device info. Tap Build number 7 times, then retry."
                } else {
                    tryStartSettingsAction(context, Settings.ACTION_SETTINGS)
                    "Developer options OFF — opened Settings. Enable Developer mode (Meta app or Build number)."
                }
            }
        }

        val wireless = listOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
        )
        for (action in wireless) {
            val r = tryStartSettingsAction(context, action)
            if (r.ok) {
                return "Developer options ON — opened ${r.label} (${r.error})"
            }
        }
        val comp = tryStartNamedSetting(context, "WirelessDebugging", null)
        if (comp.ok) return "Developer options ON — opened wireless debugging component"
        tryStartSettingsAction(context, Settings.ACTION_SETTINGS)
        return "Developer options ON — fell back to system Settings"
    }

    fun openAppDetails(context: Context = AppContext.app) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openOverlayPermission(context: Context = AppContext.app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }
    fun hasNotificationPolicyAccess(): Boolean {
        val nm = AppContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun requestNotificationPolicyAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        AppContext.app.startActivity(intent)
    }
}
object LocalPower {
    private const val TAG = "LocalPower"

    data class Snap(val log: String, val toast: String)

    fun ramSnapshot(context: Context = AppContext.app): Snap {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val avail = mi.availMem / (1024 * 1024)
        val total = mi.totalMem / (1024 * 1024)
        val used = (total - avail).coerceAtLeast(0)
        val pct = if (total > 0) (used * 100 / total) else 0
        val low = if (mi.lowMemory) "  [LOW MEMORY]" else ""
        val pssMb = try {
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            mem.totalPss / 1024
        } catch (_: Exception) { -1 }
        val pssLine = if (pssMb >= 0) "\nHelix PSS ~${pssMb} MB" else ""
        return Snap(
            log = "RAM: ${avail} MB free / ${total} MB total  (used ~${used} MB, ${pct}%)$low$pssLine",
            toast = "RAM ${avail}/${total} MB free"
        )
    }

    suspend fun cpuSnapshot(): Snap = withContext(Dispatchers.IO) {
        val u1 = readProcStatTotals()
        delay(400)
        val u2 = readProcStatTotals()
        val usage = if (u1 != null && u2 != null) {
            val totalDiff = (u2.first - u1.first).coerceAtLeast(1L)
            val idleDiff = (u2.second - u1.second).coerceAtLeast(0L)
            (((totalDiff - idleDiff) * 100) / totalDiff).toInt().coerceIn(0, 100)
        } else null

        val temp = readFirstInt(
            listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/thermal/thermal_zone8/temp",
                "/sys/class/thermal/thermal_zone9/temp",
                "/sys/class/thermal/thermal_zone10/temp"
            )
        )?.let { if (it > 1000) it / 1000 else it }

        val freqMhz = readFirstLong(
            listOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq"
            )
        )?.let { it / 1000 }?.toInt()

        var rootExtra = ""
        val rootInfo = runCatching { CpuUtils.getCpuMonitorInfo() }.getOrNull()
        if (rootInfo != null && (rootInfo.tempCelsius > 0 || rootInfo.allCoreUsagePercent > 0)) {
            rootExtra = "\n(root) ${CpuUtils.getThresholdStatus()}\n" +
                    "little ${rootInfo.littleCoreMinFreqMhz}-${rootInfo.littleCoreMaxFreqMhz} MHz " +
                    "big ${rootInfo.bigCoreMinFreqMhz}-${rootInfo.bigCoreMaxFreqMhz} MHz"
        }

        val usageStr = usage?.toString()?.plus("%") ?: (rootInfo?.allCoreUsagePercent?.toString()?.plus("%") ?: "?")
        val tempStr = (temp ?: rootInfo?.tempCelsius)?.toString()?.plus("°C") ?: "?"
        val freqStr = freqMhz?.toString()?.plus(" MHz") ?: "?"
        val src = when {
            usage != null && temp != null -> "no-root"
            usage != null -> "no-root usage / limited temp"
            rootInfo != null -> "root"
            else -> "limited"
        }
        Snap(
            log = "CPU usage $usageStr  temp $tempStr  freq $freqStr  [$src]$rootExtra",
            toast = "CPU $usageStr $tempStr"
        )
    }

    suspend fun gpuSnapshot(): Snap = withContext(Dispatchers.IO) {
        val freq = readFirstLong(
            listOf(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
            )
        )?.let { v ->
            when {
                v > 100_000_000 -> (v / 1_000_000).toInt()
                v > 1_000 -> (v / 1_000).toInt()
                else -> v.toInt()
            }
        }
        val busy = readText("/sys/class/kgsl/kgsl-3d0/gpubusy")
            ?: readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        val usage = parseGpuBusy(busy)
        val temp = readFirstInt(
            listOf(
                "/sys/class/kgsl/kgsl-3d0/temp",
                "/sys/class/thermal/thermal_zone10/temp",
                "/sys/class/thermal/thermal_zone9/temp"
            )
        )?.let { if (it > 1000) it / 1000 else it }

        val root = runCatching { GpuUtils.getGpuMonitorInfo() }.getOrNull()
        val usageF = usage ?: root?.usagePercent
        val tempF = temp ?: root?.tempCelsius
        val freqF = freq ?: root?.freqMhz
        val src = when {
            usage != null || freq != null -> "no-root/partial"
            root != null && (root.freqMhz > 0 || root.usagePercent > 0) -> "root"
            else -> "unavailable (need root for kgsl on many builds)"
        }
        var extra = ""
        if (root != null && root.freqMhz > 0) {
            extra = "\n(root) ${GpuUtils.getThresholdStatus()}\nmin ${root.minFreqMhz} max ${root.maxFreqMhz} MHz"
        }
        Snap(
            log = "GPU usage ${usageF ?: "?"}%  temp ${tempF ?: "?"}°C  freq ${freqF ?: "?"} MHz  [$src]$extra",
            toast = "GPU ${usageF ?: "?"}% ${tempF ?: "?"}°C"
        )
    }

    private fun readProcStatTotals(): Pair<Long, Long>? {
        return try {
            val line = RandomAccessFile("/proc/stat", "r").use { it.readLine() } ?: return null
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 5 || p[0] != "cpu") return null
            val user = p[1].toLong()
            val nice = p[2].toLong()
            val system = p[3].toLong()
            val idle = p[4].toLong()
            val iowait = p.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq = p.getOrNull(6)?.toLongOrNull() ?: 0L
            val softirq = p.getOrNull(7)?.toLongOrNull() ?: 0L
            val total = user + nice + system + idle + iowait + irq + softirq
            total to idle
        } catch (e: Exception) {
            Log.w(TAG, "readProcStat failed", e)
            null
        }
    }

    private fun readText(path: String): String? {
        return try {
            val f = File(path)
            if (!f.canRead()) return null
            f.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFirstInt(paths: List<String>): Int? {
        for (p in paths) {
            val t = readText(p) ?: continue
            t.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun readFirstLong(paths: List<String>): Long? {
        for (p in paths) {
            val t = readText(p) ?: continue
            t.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun parseGpuBusy(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("%", "").trim()
        cleaned.toIntOrNull()?.let { return it.coerceIn(0, 100) }
        val parts = cleaned.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val busy = parts[0].toLongOrNull() ?: return null
            val total = parts[1].toLongOrNull() ?: return null
            if (total > 0) return ((100.0 * busy) / total).toInt().coerceIn(0, 100)
        }
        return null
    }
}