package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// contact blaku64th on discord if you have any issues ^^
object Platforms {

    var pushSpeed: Float = 4f
    var upStep: Float = 2.5f

    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var wasActive = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        enabled = true
        wasActive = false
        ctx.log("[Platforms] ON — L grip=push left, R grip=push right, +trigger=up")
        ctx.toast("Platforms: use grips")

        val handler = CoroutineExceptionHandler { _, e ->
            ctx.log("[Platforms error] ${e.message}")
        }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive && enabled) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now
                    tick(ctx, dt)
                } catch (e: Throwable) {
                    ctx.log("[Platforms error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        wasActive = false
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
            ctx?.log("[Platforms] OFF")
            ctx?.toast("Platforms OFF")
        }
    }

    fun resetPosition() {
        posX = 0f; posY = 0f; posZ = 0f
    }

    private fun tick(ctx: Utils.ActionContext, dt: Float) {
        val leftGrip = Input.leftSqueeze()
        val rightGrip = Input.rightSqueeze()
        val anyGrip = leftGrip || rightGrip

        if (!anyGrip) {
            if (wasActive) {
                HeadlockHelper.headlockOff(ctx)
                wasActive = false
            }
            return
        }

        if (!armed) {
            runCatching {
                HeadlockHelper.armOffsetUnmanaged(ctx)
                armed = true
            }
        }
        wasActive = true

        val (forward, right) = basis()
        val s = pushSpeed * dt

        if (leftGrip) {
            posX -= right[0] * s
            posY -= right[1] * s
            posZ -= right[2] * s
            if (Input.leftTrigger()) {
                posY += upStep * dt
            }
        }
        if (rightGrip) {
            posX += right[0] * s
            posY += right[1] * s
            posZ += right[2] * s
            if (Input.rightTrigger()) {
                posY += upStep * dt
            }
        }

        if (leftGrip && rightGrip) {
            posX += forward[0] * s * 0.35f
            posZ += forward[2] * s * 0.35f
        }

        writeNow(ctx)
    }

    private fun basis(): Pair<FloatArray, FloatArray> {
        val head = resolveForward()
        val fl = sqrt(head[0] * head[0] + head[1] * head[1] + head[2] * head[2]).coerceAtLeast(1e-4f)
        head[0] /= fl; head[1] /= fl; head[2] /= fl
        val right = floatArrayOf(-head[2], 0f, head[0])
        val rl = sqrt(right[0] * right[0] + right[2] * right[2]).coerceAtLeast(1e-4f)
        right[0] /= rl; right[2] /= rl
        return Pair(head, right)
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
            val h = Input.forward()
            if (h != null) {
                val len = sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2])
                if (len > 1e-4f) return floatArrayOf(h[0] / len, h[1] / len, h[2] / len)
            }
        }
        return floatArrayOf(0f, 0f, -1f)
    }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false)
            }
        }
    }
}
