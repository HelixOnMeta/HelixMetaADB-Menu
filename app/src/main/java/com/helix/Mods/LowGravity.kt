package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object LowGravity {

    @Volatile
    var lift: Float = 2f

    @Volatile
    var highGravity: Boolean = false

    val effectiveLift: Float
        get() = if (highGravity) -kotlin.math.abs(lift) else kotlin.math.abs(lift)

    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var propAge = 0

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) {
            refreshLift(ctx)
            ctx.log("[Gravity] already on → ${modeLabel()} rate=${effectiveLift}/s")
            ctx.toast(modeLabel())
            return
        }
        refreshLift(ctx)
        runCatching {
            HeadlockHelper.armOffsetUnmanaged(ctx)
            armed = true
            enabled = true
            writeNow(ctx)
            ctx.log("[Gravity] ON ${modeLabel()} rate=${effectiveLift}/s")
            ctx.toast("${modeLabel()} ON")
        }.onFailure {
            ctx.log("[Gravity] arm failed: ${it.message}")
            enabled = true
        }

        val handler = CoroutineExceptionHandler { _, e ->
            ctx.log("[Gravity error] ${e.message}")
        }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive && enabled) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now

                    if (++propAge >= 15) {
                        propAge = 0
                        refreshLift(ctx)
                    }

                    if (!armed) {
                        runCatching {
                            HeadlockHelper.armOffsetUnmanaged(ctx)
                            armed = true
                        }
                    }

                    val rate = effectiveLift
                    if (rate != 0f) {
                        posY += rate * dt
                        writeNow(ctx)
                    }
                } catch (e: Throwable) {
                    ctx.log("[Gravity error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
            ctx?.log("[Gravity] OFF")
            ctx?.toast("Gravity OFF")
        }
    }

    fun setHighGravity(on: Boolean, ctx: Utils.ActionContext? = null) {
        highGravity = on
        ctx?.log("[Gravity] mode → ${modeLabel()} rate=${effectiveLift}/s")
        ctx?.toast(modeLabel())
    }

    fun toggleHighGravity(ctx: Utils.ActionContext? = null) {
        setHighGravity(!highGravity, ctx)
    }

    fun resetPosition() {
        posX = 0f; posY = 0f; posZ = 0f
    }

    private fun modeLabel(): String =
        if (highGravity) "High Gravity" else "Low Gravity"

    private fun refreshLift(ctx: Utils.ActionContext) {
        val raw = runCatching {
            ctx.run("getprop debug.mod.lowGravity").trim().toFloatOrNull()
        }.getOrNull()
        if (raw != null) lift = kotlin.math.abs(raw).coerceAtLeast(0.1f)
    }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false)
            }
        }
    }
}
