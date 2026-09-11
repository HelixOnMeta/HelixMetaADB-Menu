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
object RotationMods {

    @Volatile var upsideDown = false
        private set
    @Volatile var backwards = false
        private set
    @Volatile var spin = false
        private set

    @Volatile var spinSpeedDegPerSec: Float = 90f

    @Volatile var requireRightTrigger: Boolean = true

    private var spinYawAccum = 0f

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armedByUs = false
    private var propAge = 0
    private var wasActive = false

    val anyOn: Boolean
        get() = upsideDown || backwards || spin

    fun setUpsideDown(on: Boolean, ctx: Utils.ActionContext) {
        upsideDown = on
        applyFixedOffsets()
        if (on) ensureRunning(ctx) else onMaybeStop(ctx)
        ctx.log("[RotMod] UpsideDown ${if (on) "ON (hold RT)" else "OFF"}  ${HeadlockSensor.offsetStatus()}")
        ctx.toast(if (on) "Upside-down: hold RT" else "Upside-down OFF")
    }

    fun setBackwards(on: Boolean, ctx: Utils.ActionContext) {
        backwards = on
        applyFixedOffsets()
        if (on) ensureRunning(ctx) else onMaybeStop(ctx)
        ctx.log("[RotMod] Backwards ${if (on) "ON (hold RT)" else "OFF"}  ${HeadlockSensor.offsetStatus()}")
        ctx.toast(if (on) "Backwards: hold RT" else "Backwards OFF")
    }

    fun setSpin(on: Boolean, ctx: Utils.ActionContext) {
        spin = on
        if (on) {
            spinYawAccum = 0f
            ensureRunning(ctx)
        } else {
            applyFixedOffsets()
            onMaybeStop(ctx)
        }
        ctx.log("[RotMod] Spin ${if (on) "ON @ ${spinSpeedDegPerSec}°/s (hold RT)" else "OFF"}")
        ctx.toast(if (on) "Head spin: hold RT" else "Head spin OFF")
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        upsideDown = false
        backwards = false
        spin = false
        spinYawAccum = 0f
        wasActive = false
        HeadlockSensor.clearOffset()
        if (armedByUs || HeadlockHelper.isArmed) {
            ctx?.let { runCatching { HeadlockHelper.forceDisarm(it) } }
            armedByUs = false
        }
        ctx?.log("[RotMod] all OFF — headlock reset")
    }

    private fun onMaybeStop(ctx: Utils.ActionContext) {
        if (!anyOn) stop(ctx)
    }

    private fun applyFixedOffsets() {
        val baseYaw = if (backwards) 180f else 0f
        val baseRoll = if (upsideDown) 180f else 0f
        val yaw = if (spin) baseYaw + spinYawAccum else baseYaw
        HeadlockSensor.setOffset(pitch = 0f, yaw = yaw, roll = baseRoll)
        if (!spin) spinYawAccum = 0f
    }

    private fun isRightTriggerPressed(): Boolean {
        return runCatching { Input.rightTrigger() }.getOrDefault(false)
    }

    private fun ensureRunning(ctx: Utils.ActionContext) {
        applyFixedOffsets()
        if (job?.isActive == true) return

        val handler = CoroutineExceptionHandler { _, e ->
            ctx.log("[RotMod error] ${e.message}")
        }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive && anyOn) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now

                    if (++propAge >= 20) {
                        propAge = 0
                        refreshSpinSpeed(ctx)
                    }

                    val triggerHeld = !requireRightTrigger || isRightTriggerPressed()
                    val shouldApply = anyOn && triggerHeld

                    if (shouldApply) {
                        if (spin) {
                            spinYawAccum += spinSpeedDegPerSec * dt
                            if (spinYawAccum > 3600f || spinYawAccum < -3600f) {
                                spinYawAccum %= 360f
                            }
                        }
                        applyFixedOffsets()

                        if (!armedByUs) {
                            runCatching {
                                HeadlockHelper.armOffsetUnmanaged(ctx)
                                armedByUs = true
                            }.onFailure {
                                ctx.log("[RotMod] arm failed: ${it.message}")
                            }
                        }
                        if (armedByUs) {
                            HeadlockHelper.writeOffset(ctx, 0f, 0f, 0f, respectHold = false)
                        }
                        wasActive = true
                    } else {
                        if (wasActive || armedByUs) {
                            HeadlockSensor.clearOffset()
                            runCatching { HeadlockHelper.forceDisarm(ctx) }
                            armedByUs = false
                            wasActive = false
                        }
                    }
                } catch (e: Throwable) {
                    ctx.log("[RotMod error] ${e.message}")
                }
                delay(14)
            }
            if (armedByUs || wasActive) {
                HeadlockSensor.clearOffset()
                runCatching { HeadlockHelper.forceDisarm(ctx) }
                armedByUs = false
                wasActive = false
            }
        }
    }

    private fun refreshSpinSpeed(ctx: Utils.ActionContext) {
        val raw = runCatching {
            ctx.run("getprop debug.mod.headSpinSpeed").trim().toFloatOrNull()
        }.getOrNull()
        if (raw != null && raw != 0f) {
            spinSpeedDegPerSec = raw.coerceIn(-720f, 720f)
        }
    }
}
