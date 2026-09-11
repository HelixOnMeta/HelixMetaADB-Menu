package com.helix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object MiniMenu {

    private const val deadzone = 0.45f
    private const val channel = "mini_menu"
    private const val notificationID = 9901

    @Volatile
    var enabled = false
        private set

    private var index = 0
    private var lastTrigger = false
    private var lastMenu = false
    private var lastDown = false
    private var lastClick = false
    private var lastX = false
    private var lastY = false

    private var job: Job? = null
    private var ToastJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var items: List<Utils.CustomToggleAction> = emptyList()
    private val state = mutableMapOf<String, Boolean>()

    fun start(ctx: Utils.ActionContext) {
        if (enabled) return
        enabled = true

        items = Catalog.mergedCustomToggles()
        state.clear()
        items.forEach { state[it.label] = false }

        index = 0
        lastTrigger = false
        lastMenu = false
        lastDown = false
        lastClick = false
        lastX = false
        lastY = false

        ensureChannel()
        showCurrent(ctx)
        job = scope.launch { loop(ctx) }

        ctx.log("MiniMenu ON X=prev  Y=next  StickClick=toggle  Menu=close")
        ctx.toast("MiniMenu ON")
    }

    fun stop(ctx: Utils.ActionContext) {
        if (!enabled) return
        enabled = false
        job?.cancel()
        job = null
        ToastJob?.cancel()
        ToastJob = null
        cancelNotification()
        ctx.log("MiniMenu OFF")
        ctx.toast("MiniMenu OFF")
    }

    fun toggle(ctx: Utils.ActionContext) {
        if (enabled) stop(ctx) else start(ctx)
    }

    private suspend fun loop(ctx: Utils.ActionContext) {
        while (enabled) {
            handleButtons(ctx)
            handleToggle(ctx)
            handleClose(ctx)
            delay(40L)
        }
    }

    private fun handleButtons(ctx: Utils.ActionContext) {
        // X = previous mod
        val xPressed = Input.leftX()
        if (xPressed && !lastX) {
            if (index > 0) {
                index--
                showCurrent(ctx)
            }
        }
        lastX = xPressed

        // Y = next mod
        val yPressed = Input.leftY()
        if (yPressed && !lastY) {
            if (index < items.lastIndex) {
                index++
                showCurrent(ctx)
            }
        }
        lastY = yPressed

        // Stick down still toggles
        val ly = Input.leftThumbstickY()
        val downPressed = ly > deadzone
        if (downPressed && !lastDown) {
            doToggle(ctx)
        }
        lastDown = downPressed

        // Thumbstick click still toggles
        val clickPressed = Input.leftThumbstickClick()
        if (clickPressed && !lastClick) {
            doToggle(ctx)
        }
        lastClick = clickPressed
    }

    private fun handleToggle(ctx: Utils.ActionContext) {
        val pressed = Input.leftThumbstickClick()
        if (pressed) {
            doToggle(ctx)
        }
        lastTrigger = pressed
    }

    private fun doToggle(ctx: Utils.ActionContext) {
        if (index !in items.indices) return
        val item = items[index]
        val nowOn = !(state[item.label] ?: false)
        state[item.label] = nowOn
        try {
            if (nowOn) item.onEnabled.run(ctx)
            else item.onDisabled.run(ctx)
        } catch (t: Throwable) {
            ctx.log("MiniMenu toggle error: ${t.message}")
        }
        showCurrent(ctx)

        if (nowOn) {
            ToastJob?.cancel()
            ToastJob = scope.launch {
                delay(2000L)
                if (enabled && state[item.label] == true) {
                    val text = buildLine()
                    ctx.toast(text)
                }
            }
        }
    }

    private fun handleClose(ctx: Utils.ActionContext) {
        val pressed = Input.leftMenu()
        if (pressed && !lastMenu) {
            stop(ctx)
        }
        lastMenu = pressed
    }

    private fun showCurrent(ctx: Utils.ActionContext) {
        val text = buildLine()
        ctx.log(text)
        ctx.toast(text)
        postNotification(text)
    }

    private fun buildLine(): String {
        if (items.isEmpty()) return "[0] (no mods)"
        val item = items[index]
        val on = state[item.label] == true
        return if (on) {
            "[${index + 1}] <${item.label}>"
        } else {
            "[${index + 1}] ${item.label}"
        }
    }

    private fun ensureChannel() {
        val ctx = AppContext.app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channel,
                "Mini Menu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Current mod selection"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postNotification(text: String) {
        val ctx = AppContext.app
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notif = Notification.Builder(ctx, channel)
            .setContentTitle("Mini Menu")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        nm.notify(notificationID, notif)
    }

    private fun cancelNotification() {
        val ctx = AppContext.app
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationID)
    }
}