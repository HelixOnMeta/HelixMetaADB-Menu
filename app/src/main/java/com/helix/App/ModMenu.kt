package com.helix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Floating Mods overlay with tappable switches and section filters. */
class ModMenu : Service() {

    companion object {
        const val channel = "mods_overlay"
        var instance: ModMenu? = null
            private set

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, ModMenu::class.java)
            try { ctx.startForegroundService(i) } catch (_: Exception) { ctx.startService(i) }
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
    private var lastHome = false
    private var allItems: List<Utils.CustomToggleAction> = emptyList()
    private val state = mutableMapOf<String, Boolean>()
    private var titleView: TextView? = null
    private var sectionBar: LinearLayout? = null
    private var listContainer: LinearLayout? = null

    private data class Section(val id: String, val title: String, val keys: List<String>)
    private val sections = listOf(
        Section("all", "All", emptyList()),
        Section("fly", "Fly", listOf("fly", "hover", "glide", "velocity", "orbit", "surf", "rocket")),
        Section("arms", "Arms", listOf("long arms", "break hands", "tiny", "giant", "taller", "shorter", "side shift", "ipd")),
        Section("cam", "Camera", listOf("cam", "camera", "third", "shoulder", "top down", "low angle", "spectate", "freeze", "ghost", "anchor")),
        Section("move", "Move", listOf("wall", "up/down", "platform", "gravity", "grapple", "spaz", "swim", "strafe", "forward", "fall", "brake", "climb", "zig", "bunny", "dash")),
        Section("rot", "Spin", listOf("spin", "upside", "backwards", "rotation", "head")),
        Section("input", "Input", listOf("hold ", "finger", "grip spaz", "mash", "no finger", "stop all input")),
        Section("util", "Util", listOf("overlay", "psa", "disarm", "checkpoint", "recall", "pose", "scale", "find inputs"))
    )
    private var activeSectionId = "all"
    private val sectionChipViews = mutableMapOf<String, TextView>()
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        allItems = Catalog.mergedCustomToggles()
            .filter { it.category == Utils.Category.MODS }
            .filter { !it.label.equals("Mods Overlay", ignoreCase = true) }
        allItems.forEach { state[it.label] = false }
        buildOverlay()
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
        nm?.createNotificationChannel(NotificationChannel(channel, "Mods Overlay", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, channel)
            .setContentTitle("Mods Overlay")
            .setContentText("Left Home = open/close · tap switches to toggle")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(42, notif)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            if (strokeDp > 0f) setStroke((strokeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }
    }

    private fun buildOverlay() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = rounded(0xF2111118.toInt(), 18f, 0xFF00E5FF.toInt(), 1.5f)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        titleView = TextView(this).apply {
            text = "Mods"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)
        titleRow.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF88AABB.toInt())
            setPadding(dp(12f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { hide() }
        })
        card.addView(titleRow)
        titleRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragOffsetX = event.rawX.toInt()
                    dragOffsetY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging || root == null || !added) return@setOnTouchListener false
                    val lp = root!!.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                    val dx = event.rawX.toInt() - dragOffsetX
                    val dy = event.rawY.toInt() - dragOffsetY
                    dragOffsetX = event.rawX.toInt()
                    dragOffsetY = event.rawY.toInt()
                    lp.x += dx; lp.y += dy
                    try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8f))
        }
        sectionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chipScroll.addView(sectionBar)
        card.addView(chipScroll)
        rebuildSectionChips()
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        card.addView(scroll)
        card.addView(TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF88AABB.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8f), 0, 0)
            text = "Home = close · drag title · tap switch"
        })
        root = card
        root?.visibility = View.GONE
        renderList()
    }

    private fun rebuildSectionChips() {
        val bar = sectionBar ?: return
        bar.removeAllViews()
        sectionChipViews.clear()
        for (sec in sections) {
            val chip = TextView(this).apply {
                text = sec.title
                textSize = 12f
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(6f)
                layoutParams = lp
                setOnClickListener {
                    activeSectionId = sec.id
                    styleChips()
                    renderList()
                }
            }
            sectionChipViews[sec.id] = chip
            bar.addView(chip)
        }
        styleChips()
    }

    private fun styleChips() {
        for ((id, chip) in sectionChipViews) {
            val selected = id == activeSectionId
            chip.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            chip.background = rounded(
                if (selected) 0xFF00E5FF.toInt() else 0x332A2A35.toInt(),
                20f,
                if (selected) 0 else 0x44FFFFFF,
                if (selected) 0f else 1f
            )
        }
    }

    private fun filteredItems(): List<Utils.CustomToggleAction> {
        val sec = sections.firstOrNull { it.id == activeSectionId } ?: sections.first()
        if (sec.id == "all" || sec.keys.isEmpty()) return allItems
        return allItems.filter { item ->
            val l = item.label.lowercase()
            sec.keys.any { k -> l.contains(k) }
        }
    }

    private fun renderList() {
        ui.post {
            val container = listContainer ?: return@post
            container.removeAllViews()
            val items = filteredItems()
            titleView?.text = "Mods · ${items.size}"
            if (items.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "No mods in this section"
                    setTextColor(0xFF88AABB.toInt())
                    textSize = 13f
                    setPadding(dp(8f), dp(16f), dp(8f), dp(16f))
                })
                return@post
            }
            if (activeSectionId == "all") {
                for (sec in sections.filter { it.id != "all" }) {
                    val group = items.filter { item ->
                        val l = item.label.lowercase()
                        sec.keys.any { k -> l.contains(k) }
                    }
                    if (group.isEmpty()) continue
                    addGroupHeader(container, sec.title)
                    group.forEach { addToggleRow(container, it) }
                }
                val used = sections.filter { it.id != "all" }.flatMap { sec ->
                    items.filter { item ->
                        val l = item.label.lowercase()
                        sec.keys.any { k -> l.contains(k) }
                    }.map { it.label }
                }.toSet()
                val rest = items.filter { it.label !in used }
                if (rest.isNotEmpty()) {
                    addGroupHeader(container, "Other")
                    rest.forEach { addToggleRow(container, it) }
                }
            } else {
                items.forEach { addToggleRow(container, it) }
            }
        }
    }

    private fun addGroupHeader(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            setTextColor(0xFF00E5FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(4f), dp(12f), dp(4f), dp(4f))
        })
    }

    private fun addToggleRow(parent: LinearLayout, action: Utils.CustomToggleAction) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(8f), dp(8f))
            background = rounded(0xCC1A1A22.toInt(), 12f, 0x33FFFFFF, 1f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4f)
            lp.bottomMargin = dp(4f)
            layoutParams = lp
        }
        row.addView(TextView(this).apply {
            text = action.label
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(8f), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = state[action.label] == true
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(0xFF00E5FF.toInt(), 0xFF666677.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(0xFF00E5FF.toInt(), 0xFF2A2A35.toInt())
            )
            setOnCheckedChangeListener { _, checked ->
                val was = state[action.label] == true
                if (checked == was) return@setOnCheckedChangeListener
                state[action.label] = checked
                fireToggle(action, checked)
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        parent.addView(row)
    }

    private fun actionContext(): Utils.ActionContext = object : Utils.ActionContext {
        override fun run(command: String): String {
            return try {
                val mgr = AppAdbConnectionManager.getInstance(applicationContext)
                val stream = mgr.openStream("shell:$command")
                try { stream.openInputStream().bufferedReader().use { it.readText() } }
                finally { runCatching { stream.close() } }
            } catch (e: Exception) {
                ShellExecutor.run(command, preferRoot = true, adbManager = null)
            }
        }
        override fun log(message: String) {}
        override fun toast(message: String) {
            ui.post { Toast.makeText(this@ModMenu, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun fireToggle(action: Utils.CustomToggleAction, on: Boolean) {
        val ctx = actionContext()
        scope.launch(Dispatchers.IO) {
            try {
                if (on) action.onEnabled.run(ctx) else action.onDisabled.run(ctx)
            } catch (t: Throwable) {
                ui.post { toast("Error: ${t.message}") }
            }
        }
    }

    private fun params(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(340f), dp(460f), 2038,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; title = "ModMenuOverlay" }
    }

    fun show() {
        if (!Settings.canDrawOverlays(this)) { toast("Overlay permission required"); return }
        if (root == null) return
        if (!added) {
            try { wm?.addView(root, params()); added = true }
            catch (e: Exception) { toast("addView failed: ${e.message}"); return }
        }
        overlayOpen = true
        root?.visibility = View.VISIBLE
        try { wm?.updateViewLayout(root, params()) } catch (_: Exception) {}
        renderList()
    }

    fun hide() {
        overlayOpen = false
        root?.visibility = View.GONE
    }

    private fun detach() {
        if (added && root != null) {
            try { wm?.removeView(root) } catch (_: Exception) {}
            added = false
        }
        root?.visibility = View.GONE
    }

    private fun startInputLoop() {
        AdbButtonInput.acquire()
        inputJob = scope.launch {
            while (isActive) {
                val home = runCatching {
                    Input.leftHome() || Input.leftMenu() || AdbButtonInput.Home || AdbButtonInput.Menu
                }.getOrDefault(false)
                if (home && !lastHome) {
                    ui.post { if (overlayOpen) hide() else show() }
                }
                lastHome = home
                delay(40L)
            }
        }
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}