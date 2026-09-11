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

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        enabled = true
        lastTickNs = System.nanoTime()
        wasGripping = false
        ctx.log("[UpDown] ON — hold grip, RT=up LT=down")
        ctx.toast("Up/Down: hold grip")

        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[UpDown error] ${e.message}") }
        job = scope.launch(handler) {
            while (isActive && enabled) {
                try {
                    val now = System.nanoTime()
                    val dt = if (lastTickNs == 0L) 0.016f
                    else ((now - lastTickNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    lastTickNs = now

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
