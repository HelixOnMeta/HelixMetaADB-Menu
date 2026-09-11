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
object WallWalk {

    var pullSpeed: Float = 8f
    var upBoost: Float = 14f
    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false
    private var pulling = false
    private var dirX = 0f; private var dirY = 0f; private var dirZ = -1f
    private var usingRight = true
    private var prevLeftGrip = false
    private var prevRightGrip = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        runCatching {
            HeadlockHelper.armOffsetUnmanaged(ctx)
            armed = true; enabled = true
            writeNow(ctx)
            ctx.log("[WallWalk] armed pull=$pullSpeed upBoost=$upBoost")
        }.onFailure { ctx.log("[WallWalk] arm failed: ${it.message}") }

        prevLeftGrip = false; prevRightGrip = false; pulling = false
        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[WallWalk error] ${e.message}") }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now
                    tick(ctx, dt)
                } catch (e: Throwable) {
                    ctx.log("[WallWalk error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel(); job = null; enabled = false; pulling = false
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    fun resetPosition() { posX = 0f; posY = 0f; posZ = 0f }

    private fun tick(ctx: Utils.ActionContext, dt: Float) {
        val leftGrip = Input.leftSqueeze()
        val rightGrip = Input.rightSqueeze()
        if (leftGrip && !prevLeftGrip && !pulling) {
            lockAim(false); pulling = true; usingRight = false
            ctx.log("[WallWalk] lock L")
        } else if (rightGrip && !prevRightGrip && !pulling) {
            lockAim(true); pulling = true; usingRight = true
            ctx.log("[WallWalk] lock R")
        }
        val gripHeld = if (usingRight) rightGrip else leftGrip
        if (pulling && !gripHeld) {
            pulling = false
            HeadlockHelper.headlockOff(ctx)
            ctx.log("[WallWalk] release — headlock 0")
        }
        prevLeftGrip = leftGrip; prevRightGrip = rightGrip
        if (!pulling) return
        val s = pullSpeed * dt
        posX += dirX * s; posY += dirY * s; posZ += dirZ * s
        val triggerHeld = if (usingRight) Input.rightTrigger() else Input.leftTrigger()
        if (triggerHeld) posY += upBoost * dt
        writeNow(ctx)
    }

    private fun lockAim(rightHand: Boolean) {
        val forward = resolveControllerForward(rightHand) ?: resolveHeadForward() ?: floatArrayOf(0f, 0f, -1f)
        dirX = forward[0]; dirY = forward[1]; dirZ = forward[2]
    }

    private fun resolveControllerForward(rightHand: Boolean): FloatArray? {
        if (!ControllerInput.tracking) return null
        val qx: Float; val qy: Float; val qz: Float; val qw: Float
        if (rightHand) {
            qx = ControllerInput.rightRotX; qy = ControllerInput.rightRotY
            qz = ControllerInput.rightRotZ; qw = ControllerInput.rightRotW
        } else {
            qx = ControllerInput.leftRotX; qy = ControllerInput.leftRotY
            qz = ControllerInput.leftRotZ; qw = ControllerInput.leftRotW
        }
        val vx = 0f; val vy = 0f; val vz = -1f
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        val fx = vx + qw * tx + (qy * tz - qz * ty)
        val fy = vy + qw * ty + (qz * tx - qx * tz)
        val fz = vz + qw * tz + (qx * ty - qy * tx)
        val len = sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-4f) return null
        return floatArrayOf(fx / len, fy / len, fz / len)
    }

    private fun resolveHeadForward(): FloatArray? {
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
        return null
    }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching { HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false) }
        }
    }
}
