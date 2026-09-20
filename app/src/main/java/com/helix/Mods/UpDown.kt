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
object UpDown {
    var step: Float = 10f
    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var lastTickNs = 0L
    private var wasGripping = false
    private var lastPropCheckNs = 0L

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        enabled = true
        lastTickNs = System.nanoTime()
        wasGripping = false
        lastPropCheckNs = 0L
        refreshStep(ctx)
        ctx.log("[UpDown] ON — hold grip, RT=up LT=down (speed=$step)")
        ctx.toast("Up/Down: hold grip")

        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[UpDown error] ${e.message}") }
        job = scope.launch(handler) {
            while (isActive && enabled) {
                try {
                    val now = System.nanoTime()
                    val dt = if (lastTickNs == 0L) 0.016f
                    else ((now - lastTickNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    lastTickNs = now

                    // Refresh speed from slider prop every ~500ms
                    if (now - lastPropCheckNs > 500_000_000L) {
                        lastPropCheckNs = now
                        refreshStep(ctx)
                    }

                    val gripping = Input.leftSqueeze() || Input.rightSqueeze()
                    val leftTrig = Input.leftTrigger()
                    val rightTrig = Input.rightTrigger()

                    if (gripping) {
                        if (!armed) {
                            runCatching {
                                HeadlockHelper.armOffsetUnmanaged(ctx)
                                armed = true
                            }
                        }
                        wasGripping = true

                        var moved = false
                        if (rightTrig) {
                            posY += step * dt
                            moved = true
                        }
                        if (leftTrig) {
                            posY -= step * dt
                            moved = true
                        }
                        if (moved || armed) {
                            writeNow(ctx)
                        }
                    } else {
                        if (wasGripping) {
                            HeadlockHelper.headlockOff(ctx)
                            wasGripping = false
                        }
                    }
                } catch (e: Throwable) {
                    ctx.log("[UpDown error] ${e.message}")
                }
                delay(16)
            }
        }
    }

    private fun refreshStep(ctx: Utils.ActionContext) {
        runCatching {
            val out = ctx.run("getprop debug.mod.upDownSpeed").trim()
            if (out.isNotEmpty()) {
                val v = out.toFloatOrNull()
                if (v != null) step = v.coerceIn(0f, 1000f)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        wasGripping = false
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    fun resetPosition() {
        posX = 0f; posY = 0f; posZ = 0f
    }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false)
            }
        }
    }
}
