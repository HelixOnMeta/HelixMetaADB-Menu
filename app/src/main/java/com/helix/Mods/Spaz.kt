package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

// contact blaku64th on discord if you have any issues ^^
object Spaz {

    @Volatile var enabled: Boolean = false
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var wasHolding = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        enabled = true
        wasHolding = false
        ctx.log("[Spaz] ON — hold RT to thrash")
        ctx.toast("Spaz: hold RT")

        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[Spaz error] ${e.message}") }
        job = scope.launch(handler) {
            while (isActive && enabled) {
                try {
                    tick(ctx)
                } catch (e: Throwable) {
                    ctx.log("[Spaz error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        wasHolding = false
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    private fun tick(ctx: Utils.ActionContext) {
        if (Input.rightTrigger()) {
            if (!armed) {
                runCatching {
                    HeadlockHelper.armOffsetUnmanaged(ctx)
                    armed = true
                }
            }
            wasHolding = true
            val pitch = (Random.nextFloat() * 360f) - 180f
            val yaw = (Random.nextFloat() * 360f) - 180f
            val roll = (Random.nextFloat() * 360f) - 180f
            HeadlockHelper.writePose(ctx, 0f, 0f, 0f, pitch, yaw, roll, respectHold = false)
        } else {
            if (wasHolding) {
                HeadlockHelper.headlockOff(ctx)
                wasHolding = false
            }
        }
    }
}
