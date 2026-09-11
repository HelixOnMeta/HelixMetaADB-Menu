package com.helix

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.InputDevice
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// contact blaku64th on discord if you have any issues ^^
class MainActivity : AppCompatActivity() {

    lateinit var actionContext: Utils.ActionContext
    lateinit var tabLayout: TabLayout
    lateinit var tabConsoleRoot: View
    lateinit var tabPresetsRoot: View
    lateinit var ivGradientBg: ImageView
    lateinit var ivHeroBg: ImageView
    lateinit var tabInputTestRoot: LinearLayout
    var inputTestController: InputTesting? = null

    companion object {
        @SuppressLint("StaticFieldLeak")
        var Instance: MainActivity? = null
    }
    lateinit var cardPair: View
    lateinit var etPairHost: EditText
    lateinit var etPairPort: EditText
    lateinit var etPairCode: EditText
    lateinit var btnPair: Button

    lateinit var etConnectHost: EditText
    lateinit var etConnectPort: EditText
    lateinit var btnConnect: Button
    lateinit var btnOpenSettings: Button
    lateinit var tvStatus: TextView

    lateinit var scrollOutput: ScrollView
    lateinit var tvOutput: TextView
    lateinit var etCommand: EditText
    lateinit var btnRun: Button

    lateinit var tvBatteryRefresh: TextView
    lateinit var ivHeadsetBattery: ImageView
    lateinit var tvHeadsetBattery: TextView
    lateinit var ivLeftControllerBattery: ImageView
    lateinit var tvLeftControllerBattery: TextView
    lateinit var ivRightControllerBattery: ImageView
    lateinit var tvRightControllerBattery: TextView
    private var batteryPollJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var secureSettingsGrantedThisSession = false

    lateinit var presetsContainer: LinearLayout
    lateinit var tvPresetsLog: TextView
    lateinit var scrollPresetsLog: ScrollView

    lateinit var manager: AppAdbConnectionManager

    var isAdbConnected: Boolean = false
        set(value) {
            val was = field
            field = value
            updateOpenSettingsVisibility()
            if (value && !was) {
                // First successful connect this session → grant WRITE_SECURE_SETTINGS
                lifecycleScope.launch { onAdbConnectedGrantSecureSettings() }
                if (intent?.getBooleanExtra(BootReceiver.fromboot, false) == true) {
                    maybeRunBootMacros("connect-after-boot")
                }
            }
            if (value) {
                stopReconnectLoop()
            } else {
                startReconnectLoop()
            }
        }

    var pendingAfterPermission: (() -> Unit)? = null
    val nearbyWifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingAfterPermission?.invoke()
            pendingAfterPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Instance = this
        super.onCreate(savedInstanceState)
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
        setContentView(R.layout.activity_main)

        AppContext.init(applicationContext)
        manager = AppAdbConnectionManager.getInstance(applicationContext)

        actionContext = object : Utils.ActionContext {
            override fun run(command: String): String {
                // Prefer local root / sh (real Linux), fall back to ADB shell
                return ShellExecutor.run(
                    command = command,
                    preferRoot = true,
                    adbManager = manager
                )
            }
            override fun log(message: String) { runOnUiThread { appendOutput("$message\n") } }
            override fun toast(message: String) { runOnUiThread { toast(message) } }
        }

        bindViews()
        wireTabs()
        wireConsoleTab()
        wirePresetsTab()
        wireInputTestTab()
        prefillFromPrefs()
        isAdbConnected = false
        // Try to flip wireless ADB on before discovery (needs WRITE_SECURE_SETTINGS once granted)
        tryEnableWirelessDebugging()
        maybeAutoConnect()
        startReconnectLoop()
        runIntroAnimation()
        startBatteryPolling()

        // If launched by BootReceiver, run boot macros once ADB is up (or best-effort now)
        if (intent?.getBooleanExtra(BootReceiver.fromboot, false) == true) {
            appendOutput("[Boot] launched from BootReceiver\n")
            // Delay until reconnect may succeed
            lifecycleScope.launch {
                kotlinx.coroutines.delay(5_000L)
                if (isAdbConnected) maybeRunBootMacros("boot")
                else {
                    // Still try after another wait while reconnect loop runs
                    kotlinx.coroutines.delay(15_000L)
                    if (isAdbConnected) maybeRunBootMacros("boot-delayed")
                    else appendOutput("[BootMacros] skipped — ADB not connected yet\n")
                }
            }
        }
    }

    // ── Wireless debugging helpers ──────────────────────────────────────────

    /** Best-effort enable of wireless ADB via Settings.Global. Needs WRITE_SECURE_SETTINGS. */
    fun tryEnableWirelessDebugging(): Boolean {
        return try {
            val okWifi = Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            val okAdb = runCatching {
                Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
            }.getOrDefault(false)
            if (okWifi || okAdb) {
                appendOutput("Wireless debugging flag set (adb_wifi_enabled)\n")
                true
            } else {
                appendOutput("putInt(adb_wifi_enabled) returned false\n")
                false
            }
        } catch (e: SecurityException) {
            appendOutput("SecurityException enabling wireless ADB: ${e.message}\n")
            appendOutput("Missing WRITE_SECURE_SETTINGS — will grant after first ADB connect\n")
            false
        } catch (e: Exception) {
            appendOutput("Error enabling wireless ADB: ${e.message}\n")
            false
        }
    }

    fun promptEnableWirelessDebugging() {
        setStatus("Enable Wireless Debugging in Developer options")
        appendOutput(
            "→ Open Settings → System → Developer → Wireless debugging (ON)\n" +
            "→ Then Pair with pairing code, or hit Connect\n"
        )
        toast("Enable Wireless Debugging in Settings")
    }

    /** After ADB is live, grant WRITE_SECURE_SETTINGS so next boot can flip adb_wifi_enabled. */
    suspend fun onAdbConnectedGrantSecureSettings() {
        if (secureSettingsGrantedThisSession) return
        withContext(Dispatchers.IO) {
            val pkg = packageName
            val output = runCatching {
                val stream = manager.openStream("shell:pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                try {
                    stream.openInputStream().bufferedReader().use { it.readText() }.trim()
                } finally {
                    runCatching { stream.close() }
                }
            }.getOrElse { "ERROR: ${it.message}" }

            withContext(Dispatchers.Main) {
                val failed = output.contains("Exception", ignoreCase = true) ||
                        output.contains("Error", ignoreCase = true) ||
                        output.contains("SecurityException", ignoreCase = true) ||
                        output.startsWith("ERROR:")
                if (failed && output.isNotBlank()) {
                    appendOutput("Grant WRITE_SECURE_SETTINGS failed: $output\n")
                } else {
                    secureSettingsGrantedThisSession = true
                    appendOutput("Granted WRITE_SECURE_SETTINGS to $pkg\n")
                    // Immediately try to turn wireless debugging on for future sessions
                    tryEnableWirelessDebugging()
                    toast("Permission granted — wireless ADB can auto-enable next time")
                }
            }
        }
    }

    fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        if (isAdbConnected) return
        // Only auto-retry if we've paired before
        val everPaired = Prefs.hasPairedBefore(this) || Prefs.wasLastConnectSuccessful(this)
        if (!everPaired) return

        reconnectJob = lifecycleScope.launch {
            while (isActive && !isAdbConnected) {
                delay(5_000L)
                if (isAdbConnected) break
                // Keep trying to set the flag (works once permission is granted)
                tryEnableWirelessDebugging()
                setStatus("Reconnecting every 5s…")
                doConnect(auto = true, fromReconnect = true)
            }
        }
    }

    fun stopReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Run saved macros if "Run Macros on Boot" is on and we arrived from BootReceiver or first connect after boot. */
    fun maybeRunBootMacros(reason: String = "connect") {
        if (!Macros.isBootEnabled(this)) return
        val list = Macros.macrosForBoot(this)
        if (list.isEmpty()) {
            appendOutput("[BootMacros] enabled but no macros selected/saved\n")
            return
        }
        appendOutput("[BootMacros] $reason — scheduling ${list.size} macro(s)\n")
        Macros.runBootMacros(
            context = applicationContext,
            ctx = actionContext,
            scope = lifecycleScope,
            delayBetweenMs = 400L,
            initialDelayMs = if (reason == "boot") 3_000L else 1_000L
        )
    }


    fun startBatteryPolling() {
        if (batteryPollJob?.isActive == true) return
        batteryPollJob = lifecycleScope.launch {
            while (isActive) {
                refreshBatteryOnce()
                delay(15_000L)
            }
        }
    }

    fun stopBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = null
    }

    suspend fun refreshBatteryOnce() {
        // Always try local host battery (works without ADB when Helix runs on Quest)
        val local = withContext(Dispatchers.IO) {
            runCatching { LocalDevice.readHostBattery(applicationContext) }.getOrNull()
        }

        if (!isAdbConnected) {
            // Controllers unknown offline — only fill headset from local API
            applyBatteryReading(
                BatteryStatus.Reading(
                    headset = local?.percent,
                    left = null,
                    right = null
                )
            )
            return
        }

        val (batteryDump, remoteDump) = withContext(Dispatchers.IO) {
            val battery = runCatching { actionContext.run("dumpsys battery") }.getOrDefault("")
            val remote = runCatching { actionContext.run("dumpsys OVRRemoteService") }.getOrDefault("")
            battery to remote
        }
        val adbReading = BatteryStatus.parse(batteryDump, remoteDump)
        // Prefer ADB headset level when available; fall back to local
        applyBatteryReading(
            BatteryStatus.Reading(
                headset = adbReading.headset ?: local?.percent,
                left = adbReading.left,
                right = adbReading.right
            )
        )
    }

    fun applyBatteryReading(reading: BatteryStatus.Reading) {
        setBatterySlot(ivHeadsetBattery, tvHeadsetBattery, reading.headset)
        setBatterySlot(ivLeftControllerBattery, tvLeftControllerBattery, reading.left)
        setBatterySlot(ivRightControllerBattery, tvRightControllerBattery, reading.right)
    }

    private fun setBatterySlot(icon: ImageView, label: TextView, percent: Int?) {
        if (percent == null) return
        val color = BatteryStatus.colorFor(percent)
        icon.alpha = 1f
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        label.text = "$percent%"
        label.setTextColor(color)
    }

    fun runIntroAnimation() {
        val fadeInGradient = ObjectAnimator.ofFloat(ivGradientBg, "alpha", 0f, 1f).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
        }

        fadeInGradient.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                ivGradientBg.postDelayed({
                    val fadeInHero = ObjectAnimator.ofFloat(ivHeroBg, "alpha", 0f, 1f)
                    val fadeOutGradient = ObjectAnimator.ofFloat(ivGradientBg, "alpha", 1f, 0f)

                    AnimatorSet().apply {
                        playTogether(fadeInHero, fadeOutGradient)
                        duration = 900
                        interpolator = AccelerateDecelerateInterpolator()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                playOpenAnimation()
                            }
                        })
                        start()
                    }
                }, 400)
            }
        })

        fadeInGradient.start()
    }
    fun updateOpenSettingsVisibility() {
        if (!::btnOpenSettings.isInitialized) return
        btnOpenSettings.visibility = if (isAdbConnected) View.GONE else View.VISIBLE
        if (::cardPair.isInitialized) {
            cardPair.visibility = if (isAdbConnected) View.GONE else View.VISIBLE
        }
    }

    private val inputManager by lazy {
        getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    override fun onStart() {
        super.onStart()
        GamepadInput.startWatching(inputManager)
    }

    override fun onStop() {
        super.onStop()
        GamepadInput.stopWatching(inputManager)
    }

    override fun onDestroy() {
        stopReconnectLoop()
        stopBatteryPolling()
        if (Instance === this) Instance = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadInput.handleKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadInput.handleKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (GamepadInput.handleMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> bindId(name: String): T {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) {
            throw IllegalStateException(
                "Missing @+id/$name in layout. Add it to res/layout/activity_main.xml"
            )
        }
        return findViewById(id) as T
    }

    private fun viewId(name: String): View {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) {
            throw IllegalStateException(
                "Missing @+id/$name in layout. Add it to res/layout/activity_main.xml"
            )
        }
        return findViewById(id)
    }

    fun playOpenAnimation() {
        val root = viewId("contentContainer")
        root.scaleX = 0.82f
        root.scaleY = 0.82f
        root.alpha = 0f
        root.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(20L)
            .setDuration(260L)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    fun bindViews() {
        ivGradientBg = bindId("ivGradientBg")
        ivHeroBg = bindId("ivHeroBg")
        tabLayout = bindId("tabLayout")
        tabConsoleRoot = bindId("tabConsoleRoot")
        tabPresetsRoot = bindId("tabPresetsRoot")

        cardPair = bindId("cardPair")
        etPairHost = bindId("etPairHost")
        etPairPort = bindId("etPairPort")
        etPairCode = bindId("etPairCode")
        btnPair = bindId("btnPair")

        etConnectHost = bindId("etConnectHost")
        etConnectPort = bindId("etConnectPort")
        btnConnect = bindId("btnConnect")
        btnOpenSettings = bindId("btnOpenSettings")
        tvStatus = bindId("tvStatus")

        scrollOutput = bindId("scrollOutput")
        tvOutput = bindId("tvOutput")
        etCommand = bindId("etCommand")
        btnRun = bindId("btnRun")

        tvBatteryRefresh = bindId("tvBatteryRefresh")
        ivHeadsetBattery = bindId("ivHeadsetBattery")
        tvHeadsetBattery = bindId("tvHeadsetBattery")
        ivLeftControllerBattery = bindId("ivLeftControllerBattery")
        tvLeftControllerBattery = bindId("tvLeftControllerBattery")
        ivRightControllerBattery = bindId("ivRightControllerBattery")
        tvRightControllerBattery = bindId("tvRightControllerBattery")

        presetsContainer = bindId("presetsContainer")
        tvPresetsLog = bindId("tvPresetsLog")
        scrollPresetsLog = bindId("scrollPresetsLog")
    }

    fun wireTabs() {
        while (tabLayout.tabCount < 3) {
            val title = when (tabLayout.tabCount) {
                0 -> "Console"
                1 -> "Presets"
                else -> "Input Test"
            }
            tabLayout.addTab(tabLayout.newTab().setText(title))
        }
        tabLayout.getTabAt(2)?.text = "Input Test"

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val otherRoots = mutableListOf(tabConsoleRoot, tabPresetsRoot)
                if (::tabInputTestRoot.isInitialized) otherRoots += tabInputTestRoot

                when (tab.position) {
                    0 -> {
                        Anim.crossfadeSwap(otherRoots.filterNot { it === tabConsoleRoot }, tabConsoleRoot)
                        inputTestController?.stopPolling()
                        startBatteryPolling()
                    }
                    1 -> {
                        Anim.crossfadeSwap(otherRoots.filterNot { it === tabPresetsRoot }, tabPresetsRoot)
                        inputTestController?.stopPolling()
                        stopBatteryPolling()
                    }
                    else -> {
                        if (::tabInputTestRoot.isInitialized) {
                            Anim.crossfadeSwap(otherRoots.filterNot { it === tabInputTestRoot }, tabInputTestRoot)
                            inputTestController?.startPolling()
                        }
                        stopBatteryPolling()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    fun wireInputTestTab() {
        val parent = (tabPresetsRoot.parent as? android.view.ViewGroup)
            ?: (tabConsoleRoot.parent as? android.view.ViewGroup)
            ?: return

        tabInputTestRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = tabPresetsRoot.layoutParams
                ?: android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(content)
        tabInputTestRoot.addView(scroll)

        val index = parent.indexOfChild(tabPresetsRoot).takeIf { it >= 0 }
            ?: parent.indexOfChild(tabConsoleRoot).takeIf { it >= 0 }
            ?: -1
        if (index >= 0) parent.addView(tabInputTestRoot, index + 1)
        else parent.addView(tabInputTestRoot)

        val testCtx = object : Utils.ActionContext {
            override fun run(command: String): String {
                return ShellExecutor.run(
                    command = command,
                    preferRoot = true,
                    adbManager = manager
                )
            }
            override fun log(message: String) {}
            override fun toast(message: String) {
                runOnUiThread { toast(message) }
            }
        }

        inputTestController = InputTesting(this, content, lifecycleScope, testCtx).also { it.build() }
    }
    private fun openShell(cmd: String): String =
        openService(if (cmd.isEmpty()) "shell:" else "shell:$cmd")

    private fun openService(destination: String): String {
        val stream = manager.openStream(destination)
        return try {
            stream.openInputStream().bufferedReader().use { it.readText() }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun parseHostPort(s: String): Pair<String, Int>? {
        val m = Regex("""^([^:]+):(\d+)$""").matchEntire(s.trim()) ?: return null
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return m.groupValues[1] to port
    }
    fun changeBootAnim(
        ctx: Utils.ActionContext
    ) {
        val customization = Customization()
        val adbFile = File(filesDir, "adb")

        if (!adbFile.exists()) {
            assets.open("adb").use { input ->
                FileOutputStream(adbFile).use { output ->
                    input.copyTo(output)
                }
            }

            adbFile.setExecutable(true)
        }

        val adbHomeDir = filesDir.absolutePath
        customization.stagePayloadChain(
            this,
            ctx,
            adbFile,
            adbHomeDir,
            assets
        )
    }
    private fun firstNonEmpty(vararg parts: String): String =
        parts.first { it.isNotEmpty() }
    fun wireConsoleTab() {
        btnPair.setOnClickListener { doPair() }
        btnConnect.setOnClickListener { doConnect() }
        btnRun.setOnClickListener { runTypedCommand() }
        tvBatteryRefresh.setOnClickListener { lifecycleScope.launch { refreshBatteryOnce() } }
        btnOpenSettings.setOnClickListener {
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
        }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runTypedCommand()
                true
            } else {
                false
            }
        }

        etPairHost.visibility = View.GONE
        etPairPort.visibility = View.GONE
        etConnectHost.visibility = View.GONE
        etConnectPort.visibility = View.GONE

        listOf(btnPair, btnConnect, btnRun, btnOpenSettings, tvBatteryRefresh)
            .forEach { Anim.bindPressFeedback(it) }
    }
    fun wirePresetsTab() {
        val presetsCtx = object : Utils.ActionContext {
            override fun run(command: String): String {
                return ShellExecutor.run(
                    command = command,
                    preferRoot = true,
                    adbManager = manager
                )
            }

            override fun log(message: String) {
                runOnUiThread { appendPresetsLog("$message\n") }
            }

            override fun toast(message: String) {
                runOnUiThread { this@MainActivity.toast(message) }
            }
        }

        PresetsController(this, presetsContainer, lifecycleScope, presetsCtx).build()
    }

    fun prefillFromPrefs() {
        etPairHost.setText(Prefs.loadPairHost(this))
        etPairPort.setText(Prefs.loadPairPort(this))
        etPairCode.setText(Prefs.loadPairCode(this))
        etConnectHost.setText(Prefs.loadConnectHost(this))
        etConnectPort.setText(Prefs.loadConnectPort(this))
    }

    fun maybeAutoConnect() {
        val everPaired = Prefs.hasPairedBefore(this) || Prefs.wasLastConnectSuccessful(this)
        if (!everPaired) {
            setStatus("Pair once with a 6-digit code to enable auto-connect")
            return
        }

        setStatus("Looking for paired device…")
        ensureDiscoveryPermission {
            lifecycleScope.launch {
                tryEnableWirelessDebugging()
                val discovered = withContext(Dispatchers.IO) {
                    runCatching { AdbDiscovery.discoverConnectEndpoint(applicationContext) }.getOrNull()
                }
                if (discovered != null) {
                    etConnectHost.setText(discovered.host)
                    etConnectPort.setText(discovered.port.toString())
                }

                val host = etConnectHost.text.toString().trim()
                val port = etConnectPort.text.toString().trim().toIntOrNull()
                if (host.isEmpty() || port == null) {
                    setStatus("No wireless ADB found — enable Wireless Debugging in Settings")
                    promptEnableWirelessDebugging()
                    return@launch
                }
                doConnect(auto = true)
            }
        }
    }

    fun ensureDiscoveryPermission(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onReady()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onReady()
        } else {
            pendingAfterPermission = onReady
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
    fun doPair() {

        val code = etPairCode.text
            .toString()
            .trim()

        if (code.isEmpty()) {
            toast("Enter the pairing code")
            etPairCode.requestFocus()
            return
        }

        if (code.length != 6 || !code.all { it.isDigit() }) {
            toast("Pairing code must be 6 digits")
            etPairCode.requestFocus()
            return
        }

        setStatus("Finding Wireless Debugging…")

        ensureDiscoveryPermission {

            lifecycleScope.launch {

                val endpoint = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbDiscovery.discoverPairingEndpoint(
                            applicationContext
                        )
                    }.getOrNull()
                }

                if (endpoint == null) {

                    setStatus(
                        "Could not find Wireless Debugging pairing service"
                    )

                    toast(
                        "Open Wireless Debugging → Pair device with pairing code"
                    )

                    return@launch
                }

                val host = endpoint.host
                val port = endpoint.port

                etPairHost.setText(host)
                etPairPort.setText(port.toString())

                setStatus(
                    "Pairing with $host:$port…"
                )

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        manager.pair(
                            host,
                            port,
                            code
                        )
                    }
                }

                result.onSuccess { ok ->

                    if (!ok) {

                        setStatus(
                            "Pairing failed — check the pairing code"
                        )

                        return@onSuccess
                    }

                    Prefs.savePair(
                        this@MainActivity,
                        host,
                        port.toString(),
                        code
                    )

                    Prefs.saveHasPaired(
                        this@MainActivity,
                        true
                    )

                    setStatus(
                        "Paired. Finding ADB…"
                    )

                    val connectEndpoint =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                AdbDiscovery.discoverConnectEndpoint(
                                    applicationContext
                                )
                            }.getOrNull()
                        }

                    if (connectEndpoint == null) {

                        setStatus(
                            "Paired, but ADB connection was not found"
                        )

                        return@onSuccess
                    }

                    val connectHost = connectEndpoint.host
                    val connectPort = connectEndpoint.port

                    etConnectHost.setText(connectHost)
                    etConnectPort.setText(
                        connectPort.toString()
                    )

                    Prefs.saveConnect(
                        this@MainActivity,
                        connectHost,
                        connectPort.toString()
                    )

                    setStatus(
                        "Connecting to $connectHost:$connectPort…"
                    )

                    val connectResult =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                manager.connect(
                                    connectHost,
                                    connectPort
                                )
                            }
                        }

                    connectResult.onSuccess { connected ->

                        Prefs.saveConnectSucceeded(
                            this@MainActivity,
                            connected
                        )

                        isAdbConnected = connected

                        if (connected) {
                            setStatus("Connected ✓")
                            lifecycleScope.launch {
                                refreshBatteryOnce()
                                // isAdbConnected setter also triggers grant
                            }
                        } else {
                            setStatus("Paired, but ADB connection failed — enable Wireless Debugging")
                            promptEnableWirelessDebugging()
                            startReconnectLoop()
                        }

                    }.onFailure { error ->

                        Prefs.saveConnectSucceeded(
                            this@MainActivity,
                            false
                        )

                        isAdbConnected = false

                        setStatus(
                            "Connection error: ${error.message}"
                        )
                    }

                }.onFailure { error ->

                    setStatus(
                        "Pairing error: ${error.message}"
                    )
                }
            }
        }
    }

    fun doConnect(auto: Boolean = false, fromReconnect: Boolean = false) {
        if (!fromReconnect) {
            setStatus(if (auto) "Discovering wireless ADB…" else "Finding ADB endpoint…")
        }
        ensureDiscoveryPermission {
            lifecycleScope.launch {
                // Always try the secure setting first (no-op without permission)
                tryEnableWirelessDebugging()

                val discovered = withContext(Dispatchers.IO) {
                    runCatching { AdbDiscovery.discoverConnectEndpoint(applicationContext) }.getOrNull()
                }

                var host = discovered?.host?.trim().orEmpty()
                var port = discovered?.port

                if (host.isEmpty() || port == null) {
                    host = etConnectHost.text.toString().trim()
                    port = etConnectPort.text.toString().trim().toIntOrNull()
                } else {
                    etConnectHost.setText(host)
                    etConnectPort.setText(port.toString())
                }

                if (host.isEmpty() || port == null) {
                    setStatus("No wireless ADB found — enable it in Developer options")
                    if (!fromReconnect) {
                        appendOutput("Connect failed: wireless debugging not advertising. Enable it in Settings.\n")
                        promptEnableWirelessDebugging()
                    }
                    // Keep reconnect loop running
                    if (!isAdbConnected) startReconnectLoop()
                    return@launch
                }

                Prefs.saveConnect(this@MainActivity, host, port.toString())
                if (!fromReconnect) {
                    setStatus(if (auto) "Auto-connecting to $host:$port…" else "Connecting to $host:$port…")
                }

                val result = withContext(Dispatchers.IO) {
                    runCatching { manager.connect(host, port) }
                }
                result.onSuccess { ok ->
                    Prefs.saveConnectSucceeded(this@MainActivity, ok)
                    isAdbConnected = ok
                    setStatus(
                        when {
                            ok -> "Connected ✓  $host:$port"
                            auto || fromReconnect -> "Auto-connect failed — enable Wireless Debugging in Settings"
                            else -> "Connect failed — is wireless debugging on?"
                        }
                    )
                    if (ok) {
                        lifecycleScope.launch { refreshBatteryOnce() }
                    } else if (!fromReconnect) {
                        promptEnableWirelessDebugging()
                        startReconnectLoop()
                    }
                }.onFailure { e ->
                    Prefs.saveConnectSucceeded(this@MainActivity, false)
                    isAdbConnected = false
                    setStatus(
                        if (auto || fromReconnect) "Auto-connect error: ${e.message}"
                        else "Connect error: ${e.message}"
                    )
                    if (!fromReconnect) {
                        appendOutput("Connect error: ${e.message}\n")
                        promptEnableWirelessDebugging()
                    }
                    startReconnectLoop()
                }
            }
        }
    }

    fun runTypedCommand() {
        val cmd = etCommand.text.toString()
        if (cmd.isBlank()) return
        etCommand.text.clear()
        runCommand(cmd)
    }

    fun runCommand(cmd: String) {
        val line = cmd.trim()
        if (line.isEmpty()) return
        appendOutput("$ $line\n")
        lifecycleScope.launch(Dispatchers.IO) {
            val output = runCatching {
                ShellExecutor.run(
                    command = line,
                    preferRoot = true,
                    adbManager = manager
                )
            }.getOrElse { "[error] ${it.message}" }

            withContext(Dispatchers.Main) {
                appendOutput(if (output.isBlank()) "(no output)\n" else "$output\n")
            }
        }
    }

    fun appendOutput(text: String) {
        tvOutput.append(text)
        scrollOutput.post { scrollOutput.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun appendPresetsLog(text: String) {
        tvPresetsLog.append(text)
        scrollPresetsLog.post { scrollPresetsLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun setStatus(text: String) {
        tvStatus.text = "Status: $text"
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

object AdbFileTransfer {
    private const val S_IFREG = 0x8000
    private const val PERM_644 = 0b110_100_100
    fun pushFile(
        manager: AppAdbConnectionManager,
        local: File,
        remote: String,
        mode: Int = S_IFREG or PERM_644
    ): String {
        if (!local.isFile) return "[error] not a file: ${local.absolutePath}"

        return try {
            val stream = manager.openStream("sync:")
            try {
                val out = stream.openOutputStream()
                val inp = stream.openInputStream()

                val pathAndMode = "$remote,$mode"
                writePacket(out, "SEND", pathAndMode.toByteArray(Charsets.UTF_8))

                local.inputStream().use { fis ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        writePacket(out, "DATA", buf, 0, n)
                    }
                }

                val mtime = (local.lastModified() / 1000L).toInt()
                writePacket(out, "DONE", intLe(mtime))
                out.flush()

                val id = readExact(inp, 4)
                val len = leInt(readExact(inp, 4))
                val msg = if (len > 0) String(readExact(inp, len), Charsets.UTF_8) else ""

                when (String(id, Charsets.UTF_8)) {
                    "OKAY" -> "pushed ${local.name} → $remote (${local.length()} bytes)"
                    "FAIL" -> "[error] sync FAIL: $msg"
                    else -> "[error] unexpected sync id=${String(id)} msg=$msg"
                }
            } finally {
                runCatching { stream.close() }
            }
        } catch (e: Exception) {
            "[error] push: ${e.message}"
        }
    }
    fun pushDir(
        manager: AppAdbConnectionManager,
        localDir: File,
        remoteDir: String
    ): String {
        if (!localDir.isDirectory) return "[error] not a directory: ${localDir.absolutePath}"
        val lines = mutableListOf<String>()
        localDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(localDir).invariantSeparatorsPath
            val remote = "$remoteDir/$rel".replace("//", "/")
            val parent = remote.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                runCatching {
                    manager.openStream("shell:mkdir -p '$parent'").use { s ->
                        s.openInputStream().bufferedReader().use { it.readText() }
                    }
                }
            }
            lines += pushFile(manager, f, remote)
        }
        return lines.joinToString("\n").ifBlank { "(nothing to push)" }
    }


    private fun writePacket(out: OutputStream, id: String, data: ByteArray, off: Int = 0, len: Int = data.size) {
        out.write(id.toByteArray(Charsets.UTF_8))
        out.write(intLe(len))
        if (len > 0) out.write(data, off, len)
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )

    private fun leInt(b: ByteArray): Int =
        (b[0].toInt() and 0xff) or
                ((b[1].toInt() and 0xff) shl 8) or
                ((b[2].toInt() and 0xff) shl 16) or
                ((b[3].toInt() and 0xff) shl 24)

    private fun readExact(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("EOF reading sync response")
            off += r
        }
        return buf
    }
}

object BatteryStatus {

    data class Reading(
        val headset: Int?,
        val left: Int?,
        val right: Int?
    )

    private val HEADSET_LEVEL_REGEX = Regex("""level:\s*(\d+)""")
    private fun controllerRegex(type: String) =
        Regex("""Type:\s*$type[^\n]*?Battery:\s*(\d+)%""", RegexOption.IGNORE_CASE)

    fun parse(batteryDump: String, remoteDump: String): Reading {
        val headset = HEADSET_LEVEL_REGEX.find(batteryDump)?.groupValues?.get(1)?.toIntOrNull()
        val left = controllerRegex("Left").find(remoteDump)?.groupValues?.get(1)?.toIntOrNull()
        val right = controllerRegex("Right").find(remoteDump)?.groupValues?.get(1)?.toIntOrNull()
        return Reading(headset, left, right)
    }

    fun colorFor(percent: Int): Int {
        val p = percent.coerceIn(0, 100) / 100f
        val hue = 120f * p
        return Color.HSVToColor(floatArrayOf(hue, 0.80f, 0.95f))
    }
}