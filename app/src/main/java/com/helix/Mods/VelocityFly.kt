package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

object VelocityFly {

    var minSpeed: Float = 1f
    var maxSpeed: Float = 25f
    var accel: Float = 4f
    var gravityCounter: Float = 0f

    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var currentSpeed: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var holding = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()

        runCatching {
            HeadlockHelper.armOffsetUnmanaged(ctx)
            armed = true; enabled = true
            currentSpeed = 0f; holding = false
            ctx.log("[VelFly] armed min=$minSpeed max=$maxSpeed accel=$accel")
        }.onFailure { ctx.log("[VelFly] arm failed: ${it.message}") }

        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[VelFly error] ${e.message}") }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now
                    tick(ctx, dt)
                } catch (e: Throwable) {
                    ctx.log("[VelFly error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel(); job = null; enabled = false
        currentSpeed = 0f; holding = false
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    fun resetPosition() { posX = 0f; posY = 0f; posZ = 0f; currentSpeed = 0f }

    private fun tick(ctx: Utils.ActionContext, dt: Float) {
        val held = Input.rightB()
        var moved = false
        if (held) {
            if (!holding) { currentSpeed = minSpeed; holding = true }
            else currentSpeed = min(maxSpeed, currentSpeed + accel * dt)
            val f = resolveForward()
            val s = currentSpeed * dt
            posX += f[0] * s; posY += f[1] * s; posZ += f[2] * s
            moved = true
        } else if (holding) {
            holding = false; currentSpeed = 0f
            HeadlockHelper.headlockOff(ctx)
        }
        if (gravityCounter != 0f) {
            posY += gravityCounter * dt
            moved = true
        }
        if (moved) writeNow(ctx)
        else if (!held) HeadlockHelper.headlockOff(ctx)
    }

    private fun resolveForward(): FloatArray {
        runCatching {
            val f = OrientationTracker.getInstance().getForwardOrNull()
            if (f != null) {
                val len = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
                if (len > 1e-4f) return floatArrayOf(f[0] / len, f[1] / len, f[2] / len)
            }
        }
        runCatching {
            val head = Input.forward()
            if (head != null) {
                val len = sqrt(head[0] * head[0] + head[1] * head[1] + head[2] * head[2])
                if (len > 1e-4f) return floatArrayOf(head[0] / len, head[1] / len, head[2] / len)
            }
        }
        return floatArrayOf(0f, 0f, -1f)
    }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching { HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false) }
        }
    }
}