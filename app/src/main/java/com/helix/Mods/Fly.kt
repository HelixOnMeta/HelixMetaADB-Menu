package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

// contact blaku64th on discord if you have any issues ^^
object Fly {

    var moveSpeed: Float = 3.5f
    var fastMoveSpeed: Float = 10f
    var aButtonSpeed: Float = 6f
    var maxPitch: Float = 89f
    var turnSpeedDegPerSec: Float = 90f
    var gravityCounter: Float = 0f

    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f
    var yaw: Float = 0f
    var pitch: Float = 0f
    var rotZ: Float = 0f

    @Volatile var joystickFlyEnabled: Boolean = false
    @Volatile var aButtonFlyEnabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        Input.startfallback()
        runCatching {
            HeadlockHelper.armOffset(ctx)
            armed = true
        }
        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[Fly error] ${e.message}") }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now
                    handleLookTurn(dt)
                    yaw = ((yaw % 360f) + 360f) % 360f
                    pitch = pitch.coerceIn(-maxPitch, maxPitch)
                    if (gravityCounter != 0f) posY += gravityCounter * dt
                    handleJoystickFly(dt)
                    handleAButtonFly(dt)
                    syncToDevice(ctx)
                } catch (e: Throwable) {
                    ctx.log("[Fly error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel(); job = null
        Input.releasefallback()
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    fun resetPosition() {
        posX = 0f; posY = 0f; posZ = 0f
        yaw = 0f; pitch = 0f; rotZ = 0f
    }

    private fun handleLookTurn(dt: Float) {
        val sx = Input.rightThumbstickX()
        val sy = Input.rightThumbstickY()
        if (abs(sx) < 0.12f && abs(sy) < 0.12f) return
        yaw += sx * turnSpeedDegPerSec * dt
        pitch += sy * turnSpeedDegPerSec * dt
    }

    private fun handleJoystickFly(dt: Float) {
        if (!joystickFlyEnabled) return
        val stickX = Input.leftThumbstickX()
        val stickY = Input.leftThumbstickY()
        if (abs(stickX) < 0.12f && abs(stickY) < 0.12f &&
            !Input.leftSqueeze() && !Input.leftMenu()
        ) return
        val speed = if (Input.rightSqueeze()) fastMoveSpeed else moveSpeed
        val (forward, right) = orientationBasis()
        val mx = forward[0] * stickY + right[0] * stickX
        var my = forward[1] * stickY + right[1] * stickX
        val mz = forward[2] * stickY + right[2] * stickX
        if (Input.leftSqueeze()) my += 1f
        if (Input.leftMenu()) my -= 1f
        val len = sqrt(mx * mx + my * my + mz * mz)
        if (len > 1e-4f) {
            val s = (speed * dt) / len
            posX += mx * s; posY += my * s; posZ += mz * s
        }
    }

    private fun handleAButtonFly(dt: Float) {
        if (!aButtonFlyEnabled || !Input.rightA()) return
        val (forward, _) = orientationBasis()
        val s = aButtonSpeed * dt
        posX += forward[0] * s; posY += forward[1] * s; posZ += forward[2] * s
    }

    private fun orientationBasis(): Pair<FloatArray, FloatArray> {
        val head = resolveHeadForward()
        if (head != null) {
            val right = floatArrayOf(-head[2], 0f, head[0])
            val rl = sqrt(right[0] * right[0] + right[2] * right[2])
            if (rl > 1e-4f) { right[0] /= rl; right[2] /= rl }
            else {
                val yawRad = yaw * PI.toFloat() / 180f
                right[0] = cos(yawRad); right[1] = 0f; right[2] = sin(yawRad)
            }
            return Pair(head, right)
        }
        val yawRad = yaw * PI.toFloat() / 180f
        val pitchRad = pitch * PI.toFloat() / 180f
        val forward = floatArrayOf(
            sin(yawRad) * cos(pitchRad),
            sin(pitchRad),
            -cos(yawRad) * cos(pitchRad)
        )
        val right = floatArrayOf(cos(yawRad), 0f, sin(yawRad))
        return Pair(forward, right)
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

    private suspend fun syncToDevice(ctx: Utils.ActionContext) {
        withContext(Dispatchers.IO) {
            runCatching {
                HeadlockHelper.writeOffset(ctx, posX, posY, posZ)
            }
        }
    }
}