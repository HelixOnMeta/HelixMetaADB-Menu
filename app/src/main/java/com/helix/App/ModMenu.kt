package com.helix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

// contact blaku64th on discord if you have any issues ^^
class ModMenu : Service() {

    companion object {
        const val channel = "mods_overlay"
        var instance: ModMenu? = null
            private set

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, ModMenu::class.java)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                ctx.startService(i)
            }
        }

        fun stop(ctx: android.content.Context) {
            instance?.shutdown()
            ctx.stopService(Intent(ctx, ModMenu::class.java))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inputJob: Job? = null

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var added = false
    private var overlayOpen = false

    private val PageSize = 7
    private var page = 0
    private var cursor = 0
    private var lastStickNs = 0L
    private var lastTrigger = false
    private var lastStickClick = false
    private var lastHome = false
    private val deadzone = 0.35f
    private val stickms = 180L

    private var items: List<Utils.CustomToggleAction> = emptyList()
    private val state = mutableMapOf<String, Boolean>()

    private var titleView: TextView? = null
    private var listContainer: LinearLayout? = null
    private var hintView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        buildOverlay()
        items = Catalog.mergedCustomToggles()
        items.forEach { state[it.label] = false }
        startInputLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        inputJob?.cancel()
        runCatching { AdbButtonInput.release() }
        detach()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun shutdown() {
        ui.post {
            overlayOpen = false
            detach()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(channel, "Mods Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, channel)
            .setContentTitle("Mods Overlay")
            .setContentText("Left Home = open / close · stick ↕ select · click stick toggle")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(42, notif)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildOverlay() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                setColor(0xE6111118.toInt())
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1.5f), 0xFF00E5FF.toInt())
            }
        }

        titleView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4f))
        }
        card.addView(titleView)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        card.addView(scroll)

        hintView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF88AABB.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8f), 0, 0)
            text = "stick↕ select  stick↔ page  stick-click toggle  Home close"
        }
        card.addView(hintView)

        root = card
        root?.visibility = android.view.View.GONE
        render()
    }

    private fun params(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(320f),
            dp(380f),
            2038,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            title = "ModMenuOverlay"
        }
    }

    fun show() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Overlay permission required")
            return
        }
        if (root == null) return

        if (!added) {
            try {
                wm?.addView(root, params())
                added = true
            } catch (e: Exception) {
                toast("addView failed: ${e.message}")
                return
            }
        }
        overlayOpen = true
        lastStickClick = true // ignore held click when opening
        lastTrigger = true
        root?.visibility = android.view.View.VISIBLE
        try {
            wm?.updateViewLayout(root, params())
        } catch (_: Exception) {}
        render()
    }

    fun hide() {
        overlayOpen = false
        root?.visibility = android.view.View.GONE
    }

    private fun detach() {
        if (added && root != null) {
            try { wm?.removeView(root) } catch (_: Exception) {}
            added = false
        }
    }

    private fun render() {
        ui.post {
            val totalPages = pageCount()
            titleView?.text = "Mods  ${page + 1}/$totalPages"

            listContainer?.removeAllViews()
            val start = page * PageSize
            val end = (start + PageSize).coerceAtMost(items.size)

            for (i in start until end) {
                val local = i - start
                val item = items[i]
                val selected = local == cursor
                val on = state[item.label] == true

                val row = TextView(this).apply {
                    textSize = 13.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
                    setTextColor(if (selected) 0xFF00E5FF.toInt() else Color.WHITE)
                    background = if (selected) {
                        GradientDrawable().apply {
                            setColor(0x3300E5FF)
                            cornerRadius = dp(8f).toFloat()
                        }
                    } else null

                    val arrow = if (selected) "→ " else "  "
                    val mark = if (on) "  <>" else ""
                    text = "$arrow${item.label}$mark"
                }
                listContainer?.addView(row)
            }
        }
    }

    private fun pageCount() =
        if (items.isEmpty()) 1 else (items.size + PageSize - 1) / PageSize

    private fun pageItemCount(): Int {
        val start = page * PageSize
        return (items.size - start).coerceIn(0, PageSize)
    }

    private fun absoluteIndex() = page * PageSize + cursor

    private fun startInputLoop() {
        AdbButtonInput.acquire()

        inputJob = scope.launch {
            while (isActive) {
                // Left Home (or Menu) — open when closed, close when open
                val home = runCatching {
                    Input.leftHome() || Input.leftMenu() || AdbButtonInput.Home || AdbButtonInput.Menu
                }.getOrDefault(false)
                if (home && !lastHome) {
                    ui.post {
                        if (overlayOpen) hide() else show()
                    }
                }
                lastHome = home

                if (overlayOpen) {
                    handleStick()
                    handleToggle()
                }
                delay(40L)
            }
        }
    }

    /** Prefer left stick; fall back to right stick axes. */
    private fun readStickXY(): Pair<Float, Float> {
        val lx = Input.leftThumbstickX()
        val ly = Input.leftThumbstickY()
        val rx = Input.rightThumbstickX()
        val ry = Input.rightThumbstickY()
        // Use left if it has meaningful deflection, else right
        return if (abs(lx) > 0.12f || abs(ly) > 0.12f) lx to ly else rx to ry
    }

    private fun handleStick() {
        val now = System.nanoTime()
        if (now - lastStickNs < stickms * 1_000_000L) return

        val (lx, lyRaw) = readStickXY()
        // Quest / getevent Y is often inverted vs classic gamepad; try physical "up" as both signs.
        // Primary: negative Y = up (standard). If user pushes the other way, still works via positive branch.
        val ly = lyRaw

        // Vertical first (select) when |Y| dominates |X|
        if (abs(ly) >= abs(lx) && abs(ly) > deadzone) {
            lastStickNs = now
            if (ly < -deadzone) {
                // stick up → previous item
                moveCursor(-1)
            } else if (ly > deadzone) {
                // stick down → next item
                moveCursor(+1)
            }
            return
        }

        // Horizontal = page
        if (abs(lx) > deadzone) {
            lastStickNs = now
            if (lx < -deadzone) {
                if (page > 0) {
                    page--
                    cursor = cursor.coerceAtMost((pageItemCount() - 1).coerceAtLeast(0))
                    render()
                }
            } else if (lx > deadzone) {
                if (page < pageCount() - 1) {
                    page++
                    cursor = cursor.coerceAtMost((pageItemCount() - 1).coerceAtLeast(0))
                    render()
                }
            }
        }
    }

    private fun moveCursor(delta: Int) {
        if (delta < 0) {
            if (cursor > 0) {
                cursor--
                render()
            } else if (page > 0) {
                page--
                cursor = (pageItemCount() - 1).coerceAtLeast(0)
                render()
            }
        } else {
            if (cursor < pageItemCount() - 1) {
                cursor++
                render()
            } else if (page < pageCount() - 1) {
                page++
                cursor = 0
                render()
            }
        }
    }

    private fun handleToggle() {
        // Stick click (L or R) is primary; RT still works as backup
        val stickClick = runCatching {
            Input.leftThumbstickClick() || Input.rightThumbstickClick()
        }.getOrDefault(false)
        val trigger = runCatching { Input.rightTrigger() }.getOrDefault(false)

        val pressed = stickClick || trigger
        val wasPressed = lastStickClick || lastTrigger

        if (pressed && !wasPressed) {
            fireToggle()
        }
        lastStickClick = stickClick
        lastTrigger = trigger
    }

    private fun fireToggle() {
        val idx = absoluteIndex()
        if (idx !in items.indices) return
        val item = items[idx]
        val nowOn = !(state[item.label] ?: false)
        state[item.label] = nowOn

        val ctx = object : Utils.ActionContext {
            override fun run(command: String): String {
                return try {
                    val mgr = AppAdbConnectionManager.getInstance(applicationContext)
                    val stream = mgr.openStream("shell:$command")
                    val text = stream.openInputStream().bufferedReader().use { it.readText() }
                    stream.close()
                    text
                } catch (e: Exception) {
                    "[error] ${e.message}"
                }
            }
            override fun log(message: String) {}
            override fun toast(message: String) {
                ui.post { Toast.makeText(this@ModMenu, message, Toast.LENGTH_SHORT).show() }
            }
        }

        try {
            if (nowOn) item.onEnabled.run(ctx)
            else item.onDisabled.run(ctx)
        } catch (_: Throwable) {}
        render()
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}