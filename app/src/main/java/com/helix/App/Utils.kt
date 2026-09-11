package com.helix

import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.String
import android.provider.Settings
import android.content.Context
import android.app.NotificationManager
import android.media.AudioManager
import android.app.ActivityManager
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.content.ComponentName
import android.view.InputDevice
import android.hardware.SensorEvent
import android.hardware.input.InputManager
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.app.WallpaperManager
import android.location.LocationManager

// contact blaku64th on discord if you have any issues ^^
object Utils {
    private fun setNotificationFromUrl(ctx: ActionContext, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            ctx.log("Invalid URL: $url")
            ctx.toast("Invalid URL")
            return
        }

        val safeUrl = url.replace("'", "'\\''")
        val fileName = "adbconsole_notif_${System.currentTimeMillis()}.ogg"
        val dest = "/" + Environment.getExternalStorageDirectory().path + "/Notifications/$fileName"
        val dest2 = "/sdcard/Download/$fileName"

        ctx.log("Downloading")

        val downloadCmd = """
        mkdir -p /sdcard/Notifications /sdcard/Download;
        (command -v curl >/dev/null && curl -fsSL -o '$dest' '$safeUrl') ||
        (command -v wget >/dev/null && wget -q -O '$dest' '$safeUrl') ||
        (toybox wget -O '$dest' '$safeUrl' 2>/dev/null) ||
        (command -v curl >/dev/null && curl -fsSL -o '$dest2' '$safeUrl' && echo USE_DOWNLOAD) ||
        echo FAIL
    """.trimIndent().replace("\n", " ")

        val dl = ctx.run(downloadCmd)
        ctx.log(dl)

        val path = when {
            dl.contains("FAIL") && !dl.contains("USE_DOWNLOAD") -> {
                val check = ctx.run("ls -l '$dest' '$dest2' 2>/dev/null")
                ctx.log(check)
                when {
                    check.contains(fileName) && check.contains("Notifications") -> dest
                    check.contains(fileName) -> dest2
                    else -> {
                        ctx.log("Download failed. Push the file manually.")
                        ctx.toast("Download failed")
                        return
                    }
                }
            }
            dl.contains("USE_DOWNLOAD") -> dest2
            else -> dest
        }

        val size = ctx.run("wc -c < '$path' 2>/dev/null || stat -c %s '$path' 2>/dev/null").trim()
        ctx.log("Saved: $path ($size bytes)")
        if (size.toLongOrNull() == 0L) {
            ctx.log("File empty — URL may be wrong")
            ctx.toast("Empty file")
            return
        }
        ctx.run("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$path'")
        Thread.sleep(1500)

        val q = ctx.run(
            """content query --uri content://media/external/audio/media """ +
                    """--projection _id:_data --where "_data='$path'" """
        )
        ctx.log(q)

        val id = Regex("""_id=(\d+)""").find(q)?.groupValues?.get(1)
            ?: Regex("""(\d+)\s+,?\s*$path""").find(q)?.groupValues?.get(1)
            ?: Regex("""\b(\d+)\b""").findAll(q).map { it.groupValues[1] }.firstOrNull()

        if (id == null) {
            val q2 = ctx.run(
                """content query --uri content://media/external/audio/media """ +
                        """--projection _id:_data --where "_data LIKE '%$fileName%'" """
            )
            ctx.log(q2)
            val id2 = Regex("""_id=(\d+)""").find(q2)?.groupValues?.get(1)
            if (id2 == null) {
                ctx.log("MediaStore has no ID yet. Open the file once in Files, or reboot, then retry set.")
                ctx.toast("Scanned — set manually later")
                return
            }
            applyNotifUri(ctx, id2)
            return
        }

        applyNotifUri(ctx, id)
    }

    private fun applyNotifUri(ctx: ActionContext, mediaId: String) {
        val uri = "content://media/external/audio/media/$mediaId"
        ctx.run("settings put system notification_sound $uri")
        val verify = ctx.run("settings get system notification_sound").trim()
        ctx.log("notification = $verify")
        if (verify.contains(mediaId)) {
            ctx.log("Notification sound set OK")
        } else {
            ctx.log("Settings write may have failed")
        }
    }
    enum class Category { MODS, VISUALS, HARDWARE, SETTINGS, RECORDING, MISC, OFFLINE, POWER }

    interface ActionContext {
        fun run(command: String): String
        fun runDefault(command: String): String = run(command)

        fun log(message: String)
        fun toast(message: String)
    }
    fun ActionContext.hasRoot(): Boolean {
        val output = run("su -c id 2>/dev/null")
        return output.contains("uid=0")
    }

    fun ActionContext.getDeviceSerials(): List<String> {
        val output = run("devices")
        val serials = mutableListOf<String>()

        output.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() &&
                !trimmed.startsWith("List of devices", ignoreCase = true) &&
                !trimmed.startsWith("*", ignoreCase = true) &&
                !trimmed.startsWith("daemon", ignoreCase = true)) {

                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].equals("device", ignoreCase = true)) {
                    serials.add(parts[0])
                }
            }
        }

        return serials
    }
    fun interface CustomAction {
        fun run(ctx: ActionContext)
    }
    @Volatile var OVRHeadLock = false
    @Volatile var LEDSpaz = false
    const val BaseJump = 100f
    fun readJumpHeight(ctx: ActionContext): Float {
        val raw = ctx.run("getprop debug.mod.jumpHeight").trim()
        val sliderVal = raw.toFloatOrNull() ?: 1f
        return BaseJump * sliderVal.coerceIn(0f, 10f)
    }

    data class ButtonAction(val label: String, val commands: List<String>, val category: Category) {
        constructor(label: String, category: Category, vararg commands: String) : this(label, commands.toList(), category)
    }

    data class ToggleAction(val label: String, val onCommand: String, val offCommand: String, val category: Category)

    data class CustomButtonAction(val label: String, val category: Category, val action: CustomAction)

    data class CustomToggleAction(val label: String, val category: Category, val onEnabled: CustomAction, val onDisabled: CustomAction)

    data class SliderAction(
        val label: String,
        val min: Int,
        val max: Int,
        val initial: Int,
        val commandTemplate: String,
        val category: Category,
        val step: Float? = null
    )

    /** Like CustomButtonAction but for SeekBars. onChange runs when the user releases the thumb. */
    fun interface CustomSliderHandler {
        fun onChange(ctx: ActionContext, value: Float)
    }

    data class CustomSliderAction(
        val label: String,
        val min: Int,
        val max: Int,
        val initial: Int,
        val category: Category,
        val step: Float? = null,
        val onChange: CustomSliderHandler
    )

    val Buttons = listOf(
        ButtonAction("Reboot", Category.HARDWARE, "reboot"),
        ButtonAction("Boot Loader", Category.HARDWARE, "reboot fastboot"),
        ButtonAction("Stay On While Charge", Category.SETTINGS, "settings put global stay_on_while_plugged_in 7"),
        ButtonAction(
            "Disable ADB Auth Expiration",
            Category.SETTINGS,
            "settings put global adb_allowed_connection_time 0"
        ),
        ButtonAction("Backup", Category.MISC, "backup -apk -all -f backup.ab"),
        ButtonAction("Wifi Always On", Category.MISC, "settings put global wifi_sleep_policy 2"),
        ButtonAction("Restart Wi-Fi", Category.MISC, "svc wifi disable", "svc wifi enable"),
        ButtonAction("Disable Sensor", Category.HARDWARE, "am broadcast -a com.oculus.vrpowermanager.prox_close"),
        ButtonAction("Disable Link", Category.MISC, "am force-stop com.oculus.xrstreamingclient"),
        ButtonAction("Disable Guardian", Category.VISUALS, "setprop debug.oculus.guardian_pause 1"),
        ButtonAction(
            "Better Graphics", Category.VISUALS,
            "setprop debug.oculus.textureWidth 4096",
            "setprop debug.oculus.textureHeight 4096"
        ),
        ButtonAction(
            "Better Performance", Category.HARDWARE,
            "setprop debug.oculus.cpuLevel 7",
            "setprop debug.oculus.gpuLevel 7"
        ),
        ButtonAction(
            "Disable Updates", Category.SETTINGS,
            "am force-stop com.oculus.updater",
            "pm disable-user --user 0 com.oculus.updater",
            "pm disable-user --user 0 com.oculus.nux.ota",
            "pm disable-user --user 0 com.meta.updater",
            """sed -i '/name="com\\.oculus\\.updater"/{s/enabled="1"/enabled="2"/;s/enabled="3"/enabled="2"/;/enabled="[23]"/!s/first-install-time/enabled="2" first-install-time/}' /data/system/users/0/package-restrictions.xml""",
            """sed -i '/name="com\\.oculus\\.updater"/{s/enabled="1"/enabled="2"/;s/enabled="3"/enabled="2"/;/enabled="[23]"/!s/first-install-time/enabled="2" first-install-time/}' /data/system/users/0/package-restrictions.xml.reservecopy"""
        ),
        ButtonAction(
            "Disable Telemetry", Category.SETTINGS,
            "setprop debug.oculus.telemetry 0",
            "settings put global netstats_enabled 0",
            "settings put global data_roaming 0",
            "persist.device_config.oculus_shared_os_services.oculus_enable_wakelock_telemetry false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_enable_native_telemetry false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_instruction_sampler_enabled false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_native_telemetry_legacy_sess_disable false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_native_telemetry_sessions false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_collector false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_drop_all_client_events false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_enable_event_hc false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_enable_hc_archived_upload false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_enable_history_store false",
            "persist.device_config.oculus_shared_os_services.mobile_telemetry_enable_network_manager false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_enable_qpl_hc false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_enable_upload_configs false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_event_filtering_dev false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_event_filtering_enabled false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_low_latency_allow_list false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_new_health_counter_enable false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_purge_event false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_telemetry_session_donstate_redundant false",
            "persist.device_config.oculus_shared_os_services.oculus_mobile_wifi_telemetry false",
            "persist.device_config.oculus_shared_os_services.oculus_mr_gprips_telemetry_enabled false",
            "persist.device_config.oculus_shared_os_services.oculus_mrss_sr_slam_points_quality_telemetry false",
            "persist.device_config.oculus_shared_os_services.oculus_telemetry_block_new_events_from_ossdk false",
            "persist.device_config.oculus_shared_os_services.oscontrol_enable_telemetry_session false",
            "persist.device_config.oculus_shared_os_services_sessionless.arvr_gk_nimble_use_statsd_telemetry false",
            "persist.device_config.oculus_shared_os_services_sessionless.arvr_gk_nimble_use_statsd_telemetry_iobt false",
            "persist.device_config.oculus_shared_os_services_sessionless.hzos_wifi_telemetry_research false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_mobile_telemetry_log_train_version false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_telemetry_add_screenstate false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_telemetry_add_sensorlock false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_telemetry_sessions_time_spent_pose_data false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_telemetry_dynamic_statsd_allowlist false",
            "persist.device_config.oculus_shared_os_services_sessionless.oculus_telemetry_sessions_time_spent_timeout false",
            "persist.traced.enable false",
            "persist.dumpstate.verbose_logging.enabled false",
            "persist.device_config.hzos_system_native.oculus_telemetry_cc_migration false",
            "persist.device_config.oculus_shared_os_services_sessionless.sysprops_area_usage_reporting false",
            "vendor.debug.time_services.enable false",
            "persist.device_config.oculus_shared_os_services.horizon_advertising_id false",
            "persist.device_config.oculus_shared_os_services.horizon_advertising_id_opt_in false",
            "persist.device_config.oculus_shared_os_services.horizon_advertising_id_eligibility false",
            "setprop persist.device_config.oculus_shared_os_services.oculus_mobile_bpfagent_enabled false",
            "setprop persist.device_config.vros_vendor_sessionless.oculus_mobile_bpfagent_enabled false",
            "setprop persist.device_config.oculus_shared_os_services_sessionless.oc_bug_reporter_worlds false",
        ),
        ButtonAction(
            "LED – Red", Category.HARDWARE,
            "dumpsys battery set level 5",
            "dumpsys battery set status 3"
        ),
        ButtonAction(
            "LED – Green", Category.HARDWARE,
            "dumpsys battery set level 100",
            "dumpsys battery set status 2",
            "dumpsys battery set ac 1"
        ),
        ButtonAction(
            "LED – Yellow", Category.HARDWARE,
            "dumpsys battery set level 50",
            "dumpsys battery set status 2",
            "dumpsys battery set ac 1",
            "dumpsys battery set usb 1"
        ),
        ButtonAction(
            "LED – Reset", Category.HARDWARE,
            "dumpsys battery reset"
        ),
        ButtonAction(
            "Dont Turn Off Screen", Category.HARDWARE,
            "settings put system screen_off_timeout 7200000"
        ),
        ButtonAction(
            "Battery Saver", Category.HARDWARE,
            "settings put global always_finish_activities 1; " +
                    "settings put global wifi_scan_throttle_enabled 1; " +
                    "settings put global animator_duration_scale 0; settings put global transition_animation_scale 0; settings put global window_animation_scale 0; " +
                    "setprop debug.oculus.refreshRate 60; " +
                    "setprop debug.oculus.cpuLevel 0; " +
                    "setprop debug.oculus.gpuLevel 0; " +
                    "setprop debug.oculus.foveation.level 4; " +
                    "settings put global low_power 1; " +
                    "settings put global automatic_power_save_mode 1; " +
                    "settings put global dynamic_power_savings_enabled 1; " +
                    "dumpsys deviceidle force-idle;" +
                    "dumpsys deviceidle enable; " +
                    "settings put secure adaptive_sleep 0; " +
                    "settings put system intelligent_sleep_mode 0; " +
                    ""
        ),
    )

    val Toggles = listOf(
        ToggleAction("Disable Guardian", "setprop debug.oculus.guardian_pause 1", "setprop debug.oculus.guardian_pause 0", Category.VISUALS),
        ToggleAction("Enable Overclocking", "setprop debug.oculus.allowGPU5 1; setprop debug.oculus.allowDynresGPUBoost 1; setprop debug.oculus.gpuLevel 7; setprop debug.oculus.cpuLevel 7; debug.oculus.forceThermal 1", "setprop debug.oculus.allowGPU5 0", Category.HARDWARE),
        ToggleAction("Disable Phase Sync", "setprop debug.oculus.AutoDisablePhaseSync 1", "setprop debug.oculus.AutoDisablePhaseSync 0", Category.SETTINGS),
        ToggleAction("Enable Motion Smoothing", "setprop debug.oculus.forceSpaceWarp 1", "setprop debug.oculus.forceSpaceWarp 0", Category.VISUALS),
        ToggleAction("Full Capture Rate", "setprop debug.oculus.fullRateCapture 1", "setprop debug.oculus.fullRateCapture 0", Category.RECORDING),
        ToggleAction("Disable Chroma", "setprop debug.oculus.forceChroma 0", "setprop debug.oculus.forceChroma 1", Category.VISUALS),
        ToggleAction("ADA Clocks", "setprop debug.oculus.adaclocks.force 1", "setprop debug.oculus.adaclocks.force 0", Category.VISUALS),

        )

    val CustomButtons = listOf(

        CustomButtonAction("Root Setup", Category.HARDWARE) { ctx ->
            IonStackRoot.runIonStack(ctx,false)
        },
        CustomButtonAction("Install Magisk (from GitHub)", Category.HARDWARE) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                ctx.log("Starting Magisk download")
                val success = MagiskUtils.installFromGitHub(AppContext.app) { status ->
                    CoroutineScope(Dispatchers.Main).launch {
                        ctx.log(status)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        ctx.toast("Magisk setup finished")
                    } else {
                        ctx.toast("Magisk setup failed")
                    }
                }
            }
        },
        CustomButtonAction("V79 Legacy Root", Category.HARDWARE) { ctx ->
            IonStackRoot.runV79Root(ctx)
        },
        CustomButtonAction("Try Best Root", Category.HARDWARE) { ctx ->
            IonStackRoot.runBestRoot(ctx)
        },

        CustomButtonAction("Check Root Status", Category.HARDWARE) { ctx ->
            val serials = IonStackRoot.getDeviceSerials(ctx)
            if (serials.isEmpty()) {
                ctx.log("No device connected")
                ctx.toast("No device")
                return@CustomButtonAction
            }
            val hasRoot = IonStackRoot.checkRootAccess(ctx)

            if (hasRoot) {
                ctx.log("Device is ROOTED!")
                ctx.toast("Rooted!")

                val shellId = ctx.run("id")
                val selinux = ctx.run("getenforce")
                val buildType = ctx.run("getprop ro.build.type")
                ctx.log("Shell: $shellId")
                ctx.log("SELinux: $selinux")
                ctx.log("Build Type: $buildType")
            } else {
                ctx.log("Device is NOT rooted")
                ctx.toast("Not rooted")
                ctx.log("Try running one of the root exploits above")
            }
        },
        CustomButtonAction("BL Unlock Status V29", Category.HARDWARE) { ctx ->
            QuestBootloaderUnlocker.status(ctx)
        },
        CustomButtonAction("BL Unlock Prepare V29", Category.HARDWARE) { ctx ->
            QuestBootloaderUnlocker.prepare(ctx)
        },
        CustomButtonAction("Open Android Settings", Category.SETTINGS) { ctx ->
            GoSettings.goToSettings(ctx)
        },

        CustomButtonAction("CPU Status", Category.POWER) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                val snap = LocalPower.cpuSnapshot()
                ctx.log(snap.log)
                ctx.toast(snap.toast)
            }
        },
        CustomButtonAction("GPU Status", Category.POWER) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                val snap = LocalPower.gpuSnapshot()
                ctx.log(snap.log)
                ctx.toast(snap.toast)
            }
        },
        CustomButtonAction("RAM Info", Category.POWER) { ctx ->
            val snap = LocalPower.ramSnapshot()
            ctx.log(snap.log)
            ctx.toast(snap.toast)
        },
        CustomButtonAction("Power Snapshot (CPU+GPU+RAM)", Category.POWER) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                val cpu = LocalPower.cpuSnapshot()
                val gpu = LocalPower.gpuSnapshot()
                val ram = LocalPower.ramSnapshot()
                ctx.log("=== Power snapshot ===")
                ctx.log(cpu.log)
                ctx.log(gpu.log)
                ctx.log(ram.log)
                ctx.toast("${cpu.toast} | ${gpu.toast}")
            }
        },
        CustomButtonAction("CPU Governor Performance", Category.POWER) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { CpuUtils.setGovernor("performance") }
                val (l, b) = runCatching { CpuUtils.getGovernor() }.getOrDefault("?" to "?")
                ctx.log("Governor → little=$l big=$b (root required to change)")
                ctx.toast("performance")
            }
        },
        CustomButtonAction("CPU Governor Schedutil", Category.POWER) { ctx ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { CpuUtils.setGovernor("schedutil") }
                val (l, b) = runCatching { CpuUtils.getGovernor() }.getOrDefault("?" to "?")
                ctx.log("Governor → little=$l big=$b (root required to change)")
                ctx.toast("schedutil")
            }
        },

        CustomButtonAction("Local Battery", Category.OFFLINE) { ctx ->
            val b = LocalDevice.readHostBattery()
            if (b.percent != null) {
                val charge = if (b.charging) "charging" else "discharging"
                ctx.log("Local battery: ${b.percent}% ($charge) via ${b.source}")
                ctx.toast("${b.percent}% $charge")
            } else {
                ctx.log("Local battery unavailable (${b.source})")
                ctx.toast("Battery unavailable")
            }
        },
        CustomButtonAction("Grant Permissions", Category.SETTINGS) { ctx ->
            val pkg = AppContext.app.packageName
            val perms = listOf(
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.WRITE_SETTINGS",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO"
            )
            var ok = 0
            for (perm in perms) {
                val out = ctx.run("pm grant $pkg $perm 2>&1")
                if (out.contains("Exception", true) || out.contains("Unknown permission", true) ||
                    out.contains("not a dangerous", true)
                ) {
                    ctx.log("SKIP $perm")
                } else {
                    ok++
                    ctx.log("OK $perm")
                }
            }
            ctx.run("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            ctx.run("appops set $pkg WRITE_SETTINGS allow")
            ctx.run("appops set $pkg GET_USAGE_STATS allow")
            ctx.log("Grant finished ($ok pm grants)")
            ctx.toast("Granted $ok")
        },
        CustomButtonAction("Timeout 2 hours", Category.OFFLINE) { ctx ->
            if (!LocalDevice.canWriteSettings()) {
                LocalDevice.requestWriteSettings()
                ctx.toast("Grant write settings first")
                return@CustomButtonAction
            }
            val ok = LocalDevice.setScreenTimeoutMs(AppContext.app, 2 * 60 * 60 * 1000)
            ctx.log(if (ok) "Timeout → 2h" else "Failed")
            ctx.toast(if (ok) "2 hours" else "Failed")
        },

        CustomButtonAction("Open Developer Settings", Category.OFFLINE) { ctx ->
            val intent = Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                AppContext.app.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                AppContext.app.startActivity(fallbackIntent)
            }
        },
        CustomButtonAction("Enable Wireless Debug", Category.OFFLINE) { ctx ->
            try {
                val ok = Settings.Global.putInt(AppContext.app.contentResolver, "adb_wifi_enabled", 1)
                ctx.log(if (ok) "Wireless debugging on" else "Write failed — grant WRITE_SECURE_SETTINGS via adb first")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        },
        CustomButtonAction("Open App Details", Category.OFFLINE) { ctx ->
            LocalDevice.openAppDetails()
            ctx.log("Opened Helix app details")
        },
        CustomButtonAction("Open System Settings", Category.OFFLINE) { ctx ->
            GoSettings.goToSettings(ctx)
        },

        CustomButtonAction("Open Overlay Permission", Category.OFFLINE) { ctx ->
            LocalDevice.openOverlayPermission()
            ctx.log("Opened overlay permission")
        },
        CustomButtonAction("Notification Panel", Category.OFFLINE) { ctx ->
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Alarm Settings", Category.OFFLINE) { ctx ->

            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Date Settings", Category.OFFLINE) { ctx ->

            val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },

        CustomButtonAction("Grant Write Settings", Category.OFFLINE) { ctx ->
            if (LocalDevice.canWriteSettings()) {
                ctx.log("WRITE_SETTINGS already granted")
                ctx.toast("Already granted")
            } else {
                LocalDevice.requestWriteSettings()
                ctx.log("Enable Allow modify system settings for Helix")
                ctx.toast("Enable write settings")
            }
        },
        CustomButtonAction("Battery Saver Settings", Category.OFFLINE) { ctx ->
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Remove Helix From Optimization", Category.OFFLINE) { ctx ->
            val pkg = AppContext.app.packageName
            val pm = AppContext.app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                ctx.toast("Already exempted")
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                AppContext.app.startActivity(intent)
            }
        },
        CustomButtonAction("Open Files", Category.OFFLINE) { ctx ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Open App Settings", Category.OFFLINE) { ctx ->
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${AppContext.app.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Open Location Settings", Category.OFFLINE) { ctx ->
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            AppContext.app.startActivity(intent)
        },
        CustomButtonAction("Battery Status", Category.OFFLINE) { ctx ->
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = AppContext.app.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            ctx.log("Battery: $pct% ${if (charging) "(charging)" else "(not charging)"}")
        },
        CustomButtonAction("Free Space (Internal)", Category.OFFLINE) { ctx ->
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBytes
            val freeMb = freeBytes / (1024 * 1024)
            ctx.log("Free space: ${freeMb} MB")
        },
        CustomButtonAction("List Connected Controllers", Category.OFFLINE) { ctx ->
            val deviceIds: IntArray = InputDevice.getDeviceIds()
            val controllers = mutableListOf<String>()
            for (id: Int in deviceIds) {
                val device: InputDevice? = InputDevice.getDevice(id)
                if (device != null) {
                    val isGamepad = (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    val isJoystick = (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (isGamepad || isJoystick) {
                        controllers.add(device.name)
                    }
                }
            }
            if (controllers.isEmpty()) {
                ctx.log("No controllers connected")
            } else {
                ctx.log("Connected: ${controllers.joinToString(", ")}")
            }
        },
        CustomButtonAction("Screen Resolution", Category.OFFLINE) { ctx ->
            val metrics = AppContext.app.resources.displayMetrics
            ctx.log("Resolution: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
        },
        CustomButtonAction("Recenter Orientation", Category.MODS) { ctx ->
            Input.startfallback()
            Input.recenter()
            Fly.resetPosition()
            ctx.run(
                "setprop debug.oculus.headlock.rotation.x \"\"; " +
                        "setprop debug.oculus.headlock.rotation.y \"\"; " +
                        "setprop debug.oculus.headlock.rotation.z \"\"; " +
                        "setprop debug.oculus.headlock 0; " +
                        "dumpsys DumpsysProxy VrRuntime cmd input controllers override none"
            )
            ctx.log("Orientation recentered")
            ctx.toast("Recentered")
        },
        CustomButtonAction("Jump", Category.MODS) { ctx ->
            val y = readJumpHeight(ctx)
            val cmd = """
                setprop debug.oculus.headlock 3;
                setprop debug.oculus.headlock.translation.y $y;
                setprop debug.oculus.headlock -1
            """.trimIndent().replace("\n", " ")
            ctx.run(cmd)
            ctx.log("Jump ($y, from Jump Height slider)")
        },
        CustomButtonAction("Battery check", Category.HARDWARE) { ctx ->
            val output = ctx.run("dumpsys battery | grep level")
            val level = output.replace(Regex("[^0-9]"), "").trim().toIntOrNull() ?: -1
            if (level in 0 until 20) {
                ctx.log("Battery is low ($level%)")
            } else {
                ctx.log("Battery ($level%)")
            }
        },
        CustomButtonAction("RGB Color Picker", Category.HARDWARE) { ctx ->
            val activity = (AppContext.app as? android.app.Activity)
                ?: run {
                    RgbLedEngine.applyColor(ctx, 255, 0, 0)
                    ctx.toast("Applied red (no Activity for picker)")
                    return@CustomButtonAction
                }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                RgbPickerDialog.show(
                    context = activity,
                    initialR = RgbLedEngine.customRed ?: 255,
                    initialG = RgbLedEngine.customGreen ?: 0,
                    initialB = RgbLedEngine.customBlue ?: 0
                ) { r, g, b ->
                    RgbLedEngine.applyColorAndHold(ctx, r, g, b)
                    ctx.toast("LED R$r G$g B$b")
                }
            }
        },
        CustomButtonAction(
            "Enable Teleporting", Category.MISC
        ) { ctx ->
            RootHelper.runRoot(ctx, "oculuspreferences --setc shell_teleport_anywhere true")
        },
        CustomButtonAction(
            "Hand Tracking All Games", Category.MISC
        ) { ctx ->
            RootHelper.runRoot(
                ctx,
                "oculuspreferences --setc vrshell_skip_launchcheck_requires_controllers_enabled true"
            )
            RootHelper.runRoot(ctx, "am force-stop com.oculus.vrshell")
        },
        CustomButtonAction(
            "Disable Updates Root", Category.SETTINGS
        ) { ctx ->
            RootHelper.runRoot(
                ctx, "am force-stop com.oculus.updater; " +
                        "pm disable-user --user 0 com.oculus.updater; " +
                        "pm disable-user --user 0 com.oculus.nux.ota; " +
                        "pm disable-user --user 0 com.meta.updater; " +
                        """sed -i '/name="com\\.oculus\\.updater"/{s/enabled="1"/enabled="2"/;s/enabled="3"/enabled="2"/;/enabled="[23]"/!s/first-install-time/enabled="2" first-install-time/}' /data/system/users/0/package-restrictions.xml""" +
                        "; " +
                        """sed -i '/name="com\\.oculus\\.updater"/{s/enabled="1"/enabled="2"/;s/enabled="3"/enabled="2"/;/enabled="[23]"/!s/first-install-time/enabled="2" first-install-time/}' /data/system/users/0/package-restrictions.xml.reservecopy"""
            )
        },
        CustomButtonAction("Make System App (root)", Category.HARDWARE) { ctx ->
            if (!RootHelper.hasRoot(ctx)) {
                ctx.log("Root required")
                ctx.toast("Root required")
                return@CustomButtonAction
            }

            val pkg = AppContext.app.packageName
            val pathOut = ctx.run("pm path $pkg").trim()
            val apkPath = pathOut
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?: run {
                    ctx.log("Could not find APK path for $pkg\n$pathOut")
                    ctx.toast("APK path not found")
                    return@CustomButtonAction
                }

            val appDirName = pkg.substringAfterLast('.').ifBlank { "AdbConsole" }
            val destDir = "/system/priv-app/$appDirName"
            val destApk = "$destDir/$appDirName.apk"

            ctx.log("Source: $apkPath")
            ctx.log("Dest:   $destApk")
            val script = """
        #!/system/bin/sh
        mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
        mkdir -p '$destDir' || exit 1
        cp '$apkPath' '$destApk' || exit 2
        chmod 755 '$destDir'
        chmod 644 '$destApk'
        chown root:root '$destApk'
        restorecon -v '$destApk' 2>/dev/null
        echo SUCCESS
        ls -l '$destApk'
    """.trimIndent()

            RootHelper.writeScript(ctx, "make_system_app.sh", script)
            val result = RootHelper.runRoot(ctx, "sh /data/local/tmp/make_system_app.sh")
            ctx.log(result)

            if (result.contains("SUCCESS")) {
                ctx.log("Installed to system partition. Reboot required.")
                ctx.toast("OK — reboot")
            } else {
                ctx.log("Failed (system may be read-only). Try Magisk module approach.")
                ctx.toast("Failed")
            }
        },
        CustomButtonAction("Notification Sound from URL", Category.MISC) { ctx ->
            val activity = AppContext.app as? android.app.Activity
                ?: run {
                    val url = ctx.run("getprop debug.mod.notifUrl").trim()
                    if (url.isBlank() || !url.startsWith("http")) {
                        ctx.log("Set URL first: setprop debug.mod.notifUrl \"https://.../sound.ogg\"")
                        ctx.toast("No URL — see log")
                        return@CustomButtonAction
                    }
                    setNotificationFromUrl(ctx, url)
                    return@CustomButtonAction
                }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val input = android.widget.EditText(activity).apply {
                    hint = "https://example.com/sound.ogg"
                    setSingleLine()
                    setText("https://")
                }
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Notification sound URL")
                    .setMessage("Direct link to .ogg / .mp3 / .wav")
                    .setView(input)
                    .setPositiveButton("Download & set") { _, _ ->
                        val url = input.text?.toString()?.trim().orEmpty()
                        Thread {
                            setNotificationFromUrl(ctx, url)
                        }.start()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        },
        CustomButtonAction("Change Boot Animation", Category.MISC) { ctx ->
            MainActivity.Instance?.changeBootAnim(ctx)
        },
        CustomButtonAction("Launch Dogfood Hub", Category.HARDWARE) { ctx ->
            ctx.run("setprop debug.oculus.experimentalEnabled 1")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_qa true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_os_services.oculus_is_trusted_user true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_employee 1")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_dogfood 1")
            ctx.log("Experimental + dogfood-style flags ON")
            val buildType = ctx.run("getprop ro.build.type").trim()
            if (buildType != "userdebug") {
                ctx.log("Dogfood Hub is not enabled (build type is $buildType)")
                ctx.toast("Enable Dogfood Hub first")
                return@CustomButtonAction
            }
            ctx.log("Enabling Dogfood Hub (step 1)…")
            RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug; stop; start")

            ctx.toast("Device UI will restart – reopen the app after")
            RootHelper.runRoot(ctx, "am broadcast -a oculus.intent.action.DC_OVERRIDE --esa config_param_value oculus_systemshell:oculus_is_trusted_user:true; stop; start")
            RootHelper.runRoot(ctx, "am start com.oculus.vrshell/com.oculus.panelapp.dogfood.DogfoodMainActivity")
            ctx.log("Launched Dogfood Hub")
        },
        CustomButtonAction(
            "Enable/Disable OVR Metric Headlock", Category.VISUALS
        ) { ctx ->
            OVRHeadLock = !OVRHeadLock
            Overlay.setHeadlocked(ctx, OVRHeadLock)
        },
        CustomButtonAction("Launch Gauntlet", Category.HARDWARE) { ctx ->
            ctx.run("setprop debug.oculus.experimentalEnabled 1")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_qa true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_os_services.oculus_is_trusted_user true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_employee 1")
            val buildType = ctx.run("getprop ro.build.type").trim()
            if (buildType != "userdebug") {
                ctx.log("Gauntlet is not enabled (build type is $buildType)")
                ctx.toast("Enable Gauntlet first")
                return@CustomButtonAction
            }
            ctx.log("Enabling Gauntlet (step 1)…")
            RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug; stop; start")

            ctx.toast("Device UI will restart – reopen the app after")
            RootHelper.runRoot(ctx, "am broadcast -a oculus.intent.action.DC_OVERRIDE --esa config_param_value oculus_systemshell:oculus_is_trusted_user:true; stop; start")
            RootHelper.runRoot(ctx, "am start com.oculus.vrshell/com.oculus.panelapp.gauntlettest.GauntletTestPanelActivity")
            ctx.log("Launched Gauntlet")
        },
        CustomButtonAction("Launch Debug Panel", Category.HARDWARE) { ctx ->
            ctx.run("setprop debug.oculus.experimentalEnabled 1")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_qa true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_os_services.oculus_is_trusted_user true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_employee 1")
            val buildType = ctx.run("getprop ro.build.type").trim()
            if (buildType != "userdebug") {
                ctx.log("Debug is not enabled (build type is $buildType)")
                ctx.toast("Enable Debug first")
                return@CustomButtonAction
            }
            ctx.log("Enabling Debug (step 1)…")
            RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug; stop; start")

            ctx.toast("Device UI will restart – reopen the app after")
            RootHelper.runRoot(ctx, "am broadcast -a oculus.intent.action.DC_OVERRIDE --esa config_param_value oculus_systemshell:oculus_is_trusted_user:true; stop; start")
            RootHelper.runRoot(ctx, "am start com.oculus.vrshell/com.oculus.panelapp.debug.ShellDebugActivity")
            ctx.log("Launched Debug")
        },
        CustomButtonAction("Launch Toast", Category.HARDWARE) { ctx ->
            ctx.run("setprop debug.oculus.experimentalEnabled 1")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_qa true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_os_services.oculus_is_trusted_user true")
            RootHelper.runRoot(ctx, "setprop persist.device_config.oculus_shared_vision.oculus_gk_is_employee 1")
            val buildType = ctx.run("getprop ro.build.type").trim()
            if (buildType != "userdebug") {
                ctx.log("Toast is not enabled (build type is $buildType)")
                ctx.toast("Enable Toast first")
                return@CustomButtonAction
            }
            ctx.log("Enabling Toast (step 1)…")
            RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug; stop; start")

            ctx.toast("Device UI will restart – reopen the app after")
            RootHelper.runRoot(ctx, "am broadcast -a oculus.intent.action.DC_OVERRIDE --esa config_param_value oculus_systemshell:oculus_is_trusted_user:true; stop; start")
            RootHelper.runRoot(ctx, "am start com.oculus.vrshell/com.oculus.panelapp.toasts.ToastsActivity")
            ctx.log("Launched Toast")
        },
    )

    val CustomToggles = listOf(
        CustomToggleAction(
            "OTA Blocker (root)", Category.SETTINGS,
            onEnabled = { ctx ->
                if (!RootHelper.hasRoot(ctx)) {
                    ctx.log("Root required for full OTA blocker")
                    ctx.toast("Root required")
                    return@CustomToggleAction
                }

                UpdateBlocker.run(ctx)
                RootHelper.runRoot(ctx, """
            nohup sh -c '
            while true; do
              update_engine_client --cancel 2>/dev/null
              sleep 8
            done' >/dev/null 2>&1 &
        """.trimIndent())
                ctx.log("OTA blocker started (root)")
            },
            onDisabled = { ctx ->
                RootHelper.runRoot(ctx, "pkill -f update_engine_client || true")
                UpdateBlocker.stop()
                ctx.log("OTA blocker stopped")
            }
        ),
        CustomToggleAction(
            "Spoof Build Type (userdebug)", Category.SETTINGS,
            onEnabled = { ctx ->
                if (!RootHelper.hasRoot(ctx)) {
                    ctx.log("Root recommended for durable spoof; trying setprop anyway")
                }
                RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.odm.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.product.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.system.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.system_ext.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.vendor.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.vendor_dlkm.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop resetprop ro.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.build.type userdebug")
                RootHelper.runRoot(ctx, "resetprop ro.boot.flash.locked 0")
                RootHelper.runRoot(ctx, "resetprop resetprop ro.boot.vbmeta.device_state unlocked")
                RootHelper.runRoot(ctx, "resetprop ro.debuggable 1")
                RootHelper.runRoot(ctx, "resetprop sys.retaildemo.enabled 1")
                ctx.log("Spoof Build Type ON (userdebug). May need Magisk module for persistence across reboot.")
                ctx.toast("Build type -> userdebug")
            },
            onDisabled = { ctx ->
                RootHelper.runRoot(ctx, "setprop ro.build.type user")
                RootHelper.runRoot(ctx, "setprop ro.debuggable 0")
                ctx.log("Spoof Build Type OFF (user)")
                ctx.toast("Build type -> user")
            }
        ),
        CustomToggleAction(
            "Hosts Blocker (root)", Category.SETTINGS,
            onEnabled = { ctx ->
                if (!RootHelper.hasRoot(ctx)) {
                    ctx.toast("Root required"); return@CustomToggleAction
                }
                val hosts = """
                127.0.0.1 localhost
                ::1 ip6-localhost
                127.0.0.1 graph.oculus.com
                127.0.0.1 graph.facebook.com
                127.0.0.1 portal.fb.com
            """.trimIndent()
                RootHelper.runRoot(ctx, "mkdir -p /data/adb/modules/singularity-hosts/system/etc 2>/dev/null")
                RootHelper.writeScript(ctx, "hosts", hosts)
                RootHelper.runRoot(ctx, "cp /data/local/tmp/hosts /data/adb/modules/singularity-hosts/system/etc/hosts")
                RootHelper.runRoot(ctx, "touch /data/adb/modules/singularity-hosts/auto_mount")
                ctx.log("Hosts module written – reboot or remount for full effect")
                ctx.toast("Hosts written (reboot recommended)")
            },
            onDisabled = { ctx ->
                RootHelper.runRoot(ctx, "rm -rf /data/adb/modules/singularity-hosts 2>/dev/null || true")
                ctx.log("Hosts module removed")
            }
        ),
        CustomToggleAction(
            "RGB LED (root)", Category.HARDWARE,
            onEnabled = { ctx ->
                RgbLedEngine.run(ctx)
            },
            onDisabled = {
                RgbLedEngine.stop()
            }
        ),
        CustomToggleAction(
            "Battery Gradient LED", Category.HARDWARE,
            onEnabled = { ctx ->
                RgbLedEngine.startBatteryLed(ctx)
            },
            onDisabled = {
                RgbLedEngine.stopBatteryLed()
            }
        ),
        CustomToggleAction(
            "LED Spaz (Red ↔ Green)", Category.HARDWARE,
            onEnabled = { ctx ->
                LEDSpaz = true
                ctx.log("LED Spaz started (red ↔ green ↔ yellow)")

                try {
                    var step = 0
                    while (LEDSpaz) {
                        when (step % 3) {
                            0 -> {
                                ctx.run("dumpsys battery set level 5")
                                ctx.run("dumpsys battery set status 3")
                                ctx.run("dumpsys battery set ac 0")
                                ctx.run("dumpsys battery set usb 0")
                                ctx.log("Red")
                            }
                            1 -> {
                                ctx.run("dumpsys battery set level 100")
                                ctx.run("dumpsys battery set status 5")
                                ctx.run("dumpsys battery set ac 0")
                                ctx.run("dumpsys battery set usb 0")
                                ctx.log("Green")
                            }
                            2 -> {
                                ctx.run("dumpsys battery set level 50")
                                ctx.run("dumpsys battery set status 2")
                                ctx.run("dumpsys battery set ac 1")
                                ctx.run("dumpsys battery set usb 1")
                                ctx.log("Yellow")
                            }
                        }
                        step++
                        Thread.sleep(500)
                    }
                } finally {
                    LEDSpaz = false
                    runCatching { ctx.run("dumpsys battery reset") }
                    ctx.log("LED Spaz stopped")
                }
            },
            onDisabled = { ctx ->
                LEDSpaz = false
                runCatching { ctx.run("dumpsys battery reset") }
            }
        ),

        CustomToggleAction(
            "OVR Overlay", Category.VISUALS,
            onEnabled = { ctx ->
                Overlay.enable(ctx)
            },
            onDisabled = { ctx ->
                Overlay.disable(ctx)
            }
        ),

        CustomToggleAction(
            "Mini Overlay", Category.VISUALS,
            onEnabled = { ctx -> MiniMenu.start(ctx) },
            onDisabled = { ctx -> MiniMenu.stop(ctx) }
        ),
        CustomToggleAction(
            "Stay On While Charging (local)", Category.OFFLINE,
            onEnabled = { ctx ->
                if (!LocalDevice.canWriteSettings()) {
                    LocalDevice.requestWriteSettings()
                    ctx.toast("Grant write settings first")
                    return@CustomToggleAction
                }
                val ok = LocalDevice.setStayOnWhilePlugged(AppContext.app, true)
                ctx.log(if (ok) "Stay-on ON" else "Failed (may need secure settings)")
                ctx.toast(if (ok) "Stay on ON" else "Failed")
            },
            onDisabled = { ctx ->
                if (!LocalDevice.canWriteSettings()) {
                    ctx.toast("Grant write settings first")
                    return@CustomToggleAction
                }
                val ok = LocalDevice.setStayOnWhilePlugged(AppContext.app, false)
                ctx.log(if (ok) "Stay-on OFF" else "Failed")
                ctx.toast(if (ok) "Stay on OFF" else "Failed")
            }
        ),
        CustomToggleAction(
            "Do Not Disturb", Category.SETTINGS,
            onEnabled = { ctx ->
                if (!LocalDevice.hasNotificationPolicyAccess()) {
                    LocalDevice.requestNotificationPolicyAccess()
                    ctx.toast("Grant DND access first")
                } else {
                    val nm = AppContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    ctx.log("DND on")
                }
            },
            onDisabled = { ctx ->
                val nm = AppContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (LocalDevice.hasNotificationPolicyAccess()) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    ctx.log("DND off")
                }
            }
        ),
        CustomToggleAction(
            "Run Macros on Boot", Category.SETTINGS,
            onEnabled = { ctx ->
                Macros.setBootEnabled(AppContext.app, true)
                val n = Macros.macrosForBoot(AppContext.app).size
                val all = Macros.loadAll(AppContext.app).size
                ctx.log(
                    "Boot macros ON — will run ${if (n == all) "all $all" else "$n of $all"} macro(s) after reboot"
                )
                ctx.toast("Macros on boot ON")
            },
            onDisabled = { ctx ->
                Macros.setBootEnabled(AppContext.app, false)
                ctx.log("Boot macros OFF")
                ctx.toast("Macros on boot OFF")
            }
        ),

        )

    val Sliders = listOf(
        SliderAction("Root Attempts", 1, 10, 5, "setprop debug.ionstack.attempts %VALUE%", Category.HARDWARE),
        SliderAction("Screen Timeout", 0, 1000, 10, "settings put system screen_off_timeout %VALUE%", Category.SETTINGS),
        SliderAction("Screen Brightness", 1, 255, 100, "settings put system screen_brightness %VALUE%", Category.SETTINGS),
        SliderAction("FPS", 60, 120, 90, "setprop debug.oculus.refreshRate %VALUE%", Category.HARDWARE),
        SliderAction("GPU Level", 0, 7, 2, "setprop debug.oculus.gpuLevel %VALUE%", Category.HARDWARE),
        SliderAction("CPU Level", 0, 7, 2, "setprop debug.oculus.cpuLevel %VALUE%", Category.HARDWARE),
        SliderAction("Prediction Level", 0, 15, 0, "setprop debug.oculus.predictionSeconds %VALUE%", Category.SETTINGS),
        SliderAction(
            "Texture Amount", 300, 4096, 2048,
            "setprop debug.oculus.textureWidth %VALUE%; setprop debug.oculus.textureHeight %VALUE%", Category.VISUALS
        ),
        SliderAction("Compression Level", 0, 5, 3, "setprop debug.oculus.foveation.level %VALUE%", Category.VISUALS),
        SliderAction("Divide Framerate", -1, 5, 0, "setprop debug.oculus.swapInterval %VALUE%", Category.HARDWARE),
        SliderAction("MSAA Level", 0, 8, 4, "setprop debug.oculus.msaaLevel %VALUE%", Category.VISUALS),
        SliderAction("Anisotropy", 0, 16, 2, "setprop debug.oculus.maxAnisotropy %VALUE%", Category.VISUALS),
        SliderAction("Record FPS", 30, 120, 30, "setprop debug.oculus.capture.fps %VALUE%", Category.RECORDING),
        SliderAction("Recording Size", 0, 16, 2, "setprop debug.oculus.capture.width %VALUE%; setprop debug.oculus.capture.height %VALUE%", Category.RECORDING),
        SliderAction("Recording Bitrate", 0, 16, 2, "setprop debug.oculus.capture.bitrate %VALUE%", Category.RECORDING),

        SliderAction("Long Arms Value", 0, 10, 1, "setprop debug.mod.longArms %VALUE%", Category.MODS),
        SliderAction("Fly Speed", 0, 20, 5, "setprop debug.mod.flySpeed %VALUE%", Category.MODS),
        SliderAction("Jump Height", 0, 10, 1, "setprop debug.mod.jumpHeight %VALUE%", Category.MODS),
        SliderAction("Wall Strength", 0, 10, 5, "setprop debug.mod.wallStrength %VALUE%", Category.MODS),
        SliderAction("Grapple Speed", 0, 20, 5, "setprop debug.mod.grappleSpeed %VALUE%", Category.MODS),
        SliderAction("Vertical Speed", 0, 20, 5, "setprop debug.mod.verticalSpeed %VALUE%", Category.MODS),

        SliderAction("OVR Pitch", -90, 90, 0, "@@OVR@@ pitch %VALUE%", Category.VISUALS, step = 5f),
        SliderAction("OVR Yaw", -180, 180, 0, "@@OVR@@ yaw %VALUE%", Category.VISUALS, step = 10f),
        SliderAction("OVR Scale", 1, 5, 2, "@@OVR@@ scale %VALUE%", Category.VISUALS),
        SliderAction("OVR Distance (x0.1m)", 1, 50, 10, "@@OVR@@ distance %VALUE%", Category.VISUALS)
    )

    val CustomSliders = listOf(
        CustomSliderAction(
            label = "Brightness %",
            min = 0,
            max = 100,
            initial = 50,
            category = Category.OFFLINE
        ) { ctx, value ->
            if (!LocalDevice.canWriteSettings()) {
                LocalDevice.requestWriteSettings()
                ctx.toast("Grant write settings first")
                ctx.log("Grant write settings first")
            } else {
                val percent = value.toInt().coerceIn(0, 100)
                val level = if (percent <= 0) 1 else ((percent / 100.0) * 255).toInt().coerceIn(1, 255)
                val ok = LocalDevice.setBrightness(AppContext.app, level)
                ctx.log(if (ok) "Brightness → $percent% (level $level)" else "Failed")
                if (ok) ctx.toast("$percent%")
            }
        },
        CustomSliderAction(
            "Screen Timeout min (local)", 1, 120, 30, Category.OFFLINE
        ) { ctx, value ->
            if (!LocalDevice.canWriteSettings()) {
                LocalDevice.requestWriteSettings()
                ctx.toast("Grant write settings first")
            } else {
                val mins = value.toInt().coerceIn(1, 120)
                val ok = LocalDevice.setScreenTimeoutMs(AppContext.app, mins * 60_000)
                ctx.log(if (ok) "Timeout → ${mins}m" else "Timeout write failed")
            }
        },
        CustomSliderAction(
            "Font scale x100", 1, 130, 100, Category.OFFLINE
        ) { ctx, value ->
            if (!LocalDevice.canWriteSettings()) {
                LocalDevice.requestWriteSettings()
                ctx.toast("Grant write settings first")
            } else {
                val scale = value.toInt().coerceIn(85, 130) / 100f
                val ok = Settings.System.putFloat(AppContext.app.contentResolver, Settings.System.FONT_SCALE, scale)
                ctx.log(if (ok) "Font scale → $scale" else "Font scale write failed")
            }
        },
        CustomSliderAction(
            "Media volume", 0, 15, 5, Category.OFFLINE
        ) { ctx, value ->
            val am = AppContext.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val level = value.toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            ctx.log("Media volume → $level/$max")
        },
        CustomSliderAction(
            "Custom LED Red", 0, 255, 255, Category.HARDWARE
        ) { ctx, value ->
            val r = value.toInt().coerceIn(0, 255)
            val g = RgbLedEngine.customGreen ?: 0
            val b = RgbLedEngine.customBlue ?: 0
            RgbLedEngine.applyColorAndHold(ctx, r, g, b)
            ctx.log("LED R=$r G=$g B=$b")
        },
        CustomSliderAction(
            "Custom LED Green", 0, 255, 0, Category.HARDWARE
        ) { ctx, value ->
            val g = value.toInt().coerceIn(0, 255)
            val r = RgbLedEngine.customRed ?: 255
            val b = RgbLedEngine.customBlue ?: 0
            RgbLedEngine.applyColorAndHold(ctx, r, g, b)
            ctx.log("LED R=$r G=$g B=$b")
        },
        CustomSliderAction(
            "Custom LED Blue", 0, 255, 0, Category.HARDWARE
        ) { ctx, value ->
            val b = value.toInt().coerceIn(0, 255)
            val r = RgbLedEngine.customRed ?: 255
            val g = RgbLedEngine.customGreen ?: 0
            RgbLedEngine.applyColorAndHold(ctx, r, g, b)
            ctx.log("LED R=$r G=$g B=$b")
        },
        CustomSliderAction(
            label = "CPU Max Temp °C",
            min = 60, max = 100, initial = 85,
            category = Category.POWER
        ) { ctx, value ->
            val v = value.toInt().coerceIn(60, 100)
            CpuUtils.thresholds = CpuUtils.thresholds.copy(maxTempCelsius = v)
            ctx.log("CPU max temp threshold → ${v}°C")
        },
        CustomSliderAction(
            label = "CPU High Usage %",
            min = 50, max = 100, initial = 90,
            category = Category.POWER
        ) { ctx, value ->
            val v = value.toInt().coerceIn(50, 100)
            CpuUtils.thresholds = CpuUtils.thresholds.copy(highUsagePercent = v)
            ctx.log("CPU high usage threshold → ${v}%")
        },
        CustomSliderAction(
            label = "GPU Max Temp °C",
            min = 60, max = 100, initial = 80,
            category = Category.POWER
        ) { ctx, value ->
            val v = value.toInt().coerceIn(60, 100)
            GpuUtils.thresholds = GpuUtils.thresholds.copy(maxTempCelsius = v)
            ctx.log("GPU max temp threshold → ${v}°C")
        },
        CustomSliderAction(
            label = "GPU High Usage %",
            min = 50, max = 100, initial = 95,
            category = Category.POWER
        ) { ctx, value ->
            val v = value.toInt().coerceIn(50, 100)
            GpuUtils.thresholds = GpuUtils.thresholds.copy(highUsagePercent = v)
            ctx.log("GPU high usage threshold → ${v}%")
        },
        CustomSliderAction(
            label = "GPU Max Freq MHz",
            min = 200, max = 600, initial = 492,
            category = Category.POWER,
            step = 10f
        ) { ctx, value ->
            val mhz = value.toInt().coerceIn(200, 600)
            CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { GpuUtils.setGpuMaxFreqMhz(mhz) }.getOrDefault(false)
                ctx.log(if (ok) "GPU max → ${mhz} MHz" else "Need root to set GPU max")
            }
        },
        CustomSliderAction(
            label = "GPU Min Freq MHz",
            min = 100, max = 400, initial = 285,
            category = Category.POWER,
            step = 5f
        ) { ctx, value ->
            val mhz = value.toInt().coerceIn(100, 400)
            CoroutineScope(Dispatchers.IO).launch {
                val ok = runCatching { GpuUtils.setGpuMinFreqMhz(mhz) }.getOrDefault(false)
                ctx.log(if (ok) "GPU min → ${mhz} MHz" else "Need root to set GPU min")
            }
        },
    )

}