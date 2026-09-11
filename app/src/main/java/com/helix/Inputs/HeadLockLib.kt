package com.helix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object HeadlockHelper {

    @Volatile private var armRefCount = 0
    private val armLock = Any()

    val isArmed: Boolean get() = armRefCount > 0

    @Volatile var requireHoldToKeep: Boolean = true

    @Volatile var holdButton: HoldButton = HoldButton.EITHER_GRIP

    enum class HoldButton {
        LEFT_GRIP,
        RIGHT_GRIP,
        EITHER_GRIP,
        LEFT_TRIGGER,
        RIGHT_TRIGGER,
        EITHER_TRIGGER,
        A_BUTTON,
        B_BUTTON,
        X_BUTTON,
        Y_BUTTON,
        LEFT_STICK_CLICK,
        RIGHT_STICK_CLICK,
        LEFT_MENU,
        NONE
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null
    private var watchdogCtx: Utils.ActionContext? = null

    fun isHoldPressed(): Boolean = when (holdButton) {
        HoldButton.LEFT_GRIP -> Input.leftSqueeze()
        HoldButton.RIGHT_GRIP -> Input.rightSqueeze()
        HoldButton.EITHER_GRIP -> Input.leftSqueeze() || Input.rightSqueeze()
        HoldButton.LEFT_TRIGGER -> Input.leftTrigger()
        HoldButton.RIGHT_TRIGGER -> Input.rightTrigger()
        HoldButton.EITHER_TRIGGER -> Input.leftTrigger() || Input.rightTrigger()
        HoldButton.A_BUTTON -> Input.rightA()
        HoldButton.B_BUTTON -> Input.rightB()
        HoldButton.X_BUTTON -> Input.leftX()
        HoldButton.Y_BUTTON -> Input.leftY()
        HoldButton.LEFT_STICK_CLICK -> Input.leftThumbstickClick()
        HoldButton.RIGHT_STICK_CLICK -> Input.rightThumbstickClick()
        HoldButton.LEFT_MENU -> Input.leftMenu()
        HoldButton.NONE -> true
    }

    fun holdButtonLabel(): String = holdButton.name.lowercase().replace('_', ' ')


    fun refreshHoldButtonFromProp(ctx: Utils.ActionContext? = watchdogCtx) {
        val c = ctx ?: return
        val raw = runCatching {
            c.run("getprop debug.mod.holdButton").trim().lowercase().replace(' ', '_').replace('-', '_')
        }.getOrNull() ?: return
        if (raw.isBlank()) return
        val matched = HoldButton.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        if (matched != null && matched != holdButton) {
            holdButton = matched
        }
    }

    fun ensureWatchdog(ctx: Utils.ActionContext) {
        watchdogCtx = ctx
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(2000L)
                refreshHoldButtonFromProp()
                if (requireHoldToKeep && holdButton != HoldButton.NONE && !isHoldPressed()) {
                    val c = watchdogCtx
                    if (c != null) runCatching { forceDisarm(c) }
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        watchdogCtx = null
    }
    fun breakarms(ctx: Utils.ActionContext) {
        ctx.run(
            "setprop debug.oculus.headlock -1; " +
                    "i=1; while [ \$i -lt 125 ]; do " +
                    "setprop debug.oculus.headlock.translation.z -$(awk -v i=\$i 'BEGIN {print i * 0.5}'); " +
                    "i=$((i + 1)); " +
                    "done; " +
                    "setprop debug.oculus.headlock.translation.z 1; " +
                    "setprop debug.oculus.headlock 3"
        )
    }

    fun fixarms(ctx: Utils.ActionContext) {
        ctx.run("setprop debug.oculus.headlock 0; setprop debug.oculus.headlock.translation.z 0")
    }

    fun armOffset(ctx: Utils.ActionContext) {
        synchronized(armLock) {
            if (armRefCount == 0) {
                captureHeadBaseline()
                ensureWatchdog(ctx)
                if (!requireHoldToKeep || isHoldPressed()) {
                    val r = headRelativeRotation()
                    ctx.run(
                        "setprop debug.oculus.headlock.rotation.x ${fmt(r[0])}; " +
                                "setprop debug.oculus.headlock.rotation.y ${fmt(r[1])}; " +
                                "setprop debug.oculus.headlock.rotation.z ${fmt(r[2])}; " +
                                "setprop debug.oculus.headlock 1"
                    )
                }
            }
            armRefCount++
        }
    }

    fun armOffsetUnmanaged(ctx: Utils.ActionContext) {
        synchronized(armLock) {
            if (armRefCount == 0) {
                captureHeadBaseline()
                val r = headRelativeRotation()
                ctx.run(
                    "setprop debug.oculus.headlock.rotation.x ${fmt(r[0])}; " +
                            "setprop debug.oculus.headlock.rotation.y ${fmt(r[1])}; " +
                            "setprop debug.oculus.headlock.rotation.z ${fmt(r[2])}; " +
                            "setprop debug.oculus.headlock 1"
                )
            }
            armRefCount++
        }
    }

    fun captureHeadBaseline() {
        if (ControllerInput.tracking) {
            HeadlockSensor.captureBaselineFromTracker(null)
            return
        }
        val tracker = OrientationTracker.getInstance()
        if (!tracker.isRunning) tracker.start()
        try { Thread.sleep(40) } catch (_: InterruptedException) {}
        HeadlockSensor.captureBaselineFromTracker(tracker)
    }

    fun headRelativeRotation(): FloatArray {
        val tracker = OrientationTracker.getInstance()
        if (!ControllerInput.tracking && !tracker.isRunning) tracker.start()
        return HeadlockSensor.composedRotation(tracker)
    }

    fun writeTranslation(
        ctx: Utils.ActionContext,
        x: Float, y: Float, z: Float,
        respectHold: Boolean = true
    ) {
        if (respectHold && requireHoldToKeep && !isHoldPressed()) {
            ctx.run("setprop debug.oculus.headlock 0")
            return
        }
        val r = headRelativeRotation()
        writePose(ctx, x, y, z, r[0], r[1], r[2], respectHold = false)
    }

    fun armOffsetRamped(
        ctx: Utils.ActionContext,
        targetX: Float, targetY: Float, targetZ: Float,
        steps: Int = 125
    ) {
        synchronized(armLock) {
            if (armRefCount == 0) {
                captureHeadBaseline()
                ensureWatchdog(ctx)
                if (!requireHoldToKeep || isHoldPressed()) {
                    val r = headRelativeRotation()
                    val sb = StringBuilder()
                    sb.append("setprop debug.oculus.headlock.rotation.x ${fmt(r[0])}; ")
                    sb.append("setprop debug.oculus.headlock.rotation.y ${fmt(r[1])}; ")
                    sb.append("setprop debug.oculus.headlock.rotation.z ${fmt(r[2])}; ")
                    for (i in 1..steps) {
                        val t = i / steps.toFloat()
                        sb.append("setprop debug.oculus.headlock.translation.x ${fmt(targetX * t)}; ")
                        sb.append("setprop debug.oculus.headlock.translation.y ${fmt(targetY * t)}; ")
                        sb.append("setprop debug.oculus.headlock.translation.z ${fmt(targetZ * t)}; ")
                    }
                    sb.append("setprop debug.oculus.headlock 1")
                    ctx.run(sb.toString())
                }
            } else {
                writeTranslation(ctx, targetX, targetY, targetZ)
            }
            armRefCount++
        }
    }

    fun writeOffset(
        ctx: Utils.ActionContext,
        x: Float, y: Float, z: Float,
        respectHold: Boolean = true
    ) {
        if (respectHold && requireHoldToKeep && !isHoldPressed()) {
            ctx.run("setprop debug.oculus.headlock 0")
            return
        }
        val r = headRelativeRotation()
        writePose(ctx, x, y, z, r[0], r[1], r[2], respectHold = false)
    }

    fun writeTranslationOnly(
        ctx: Utils.ActionContext,
        x: Float, y: Float, z: Float,
        respectHold: Boolean = true
    ) {
        if (respectHold && requireHoldToKeep && !isHoldPressed()) {
            ctx.run("setprop debug.oculus.headlock 0")
            return
        }
        val r = headRelativeRotation()
        ctx.run(
            "setprop debug.oculus.headlock 1; " +
                    "setprop debug.oculus.headlock.translation.x ${fmt(x)}; " +
                    "setprop debug.oculus.headlock.translation.y ${fmt(y)}; " +
                    "setprop debug.oculus.headlock.translation.z ${fmt(z)}; " +
                    "setprop debug.oculus.headlock.rotation.x ${fmt(r[0])}; " +
                    "setprop debug.oculus.headlock.rotation.y ${fmt(r[1])}; " +
                    "setprop debug.oculus.headlock.rotation.z ${fmt(r[2])}"
        )
    }

    fun writePose(
        ctx: Utils.ActionContext,
        x: Float, y: Float, z: Float,
        pitch: Float, yaw: Float, roll: Float,
        respectHold: Boolean = true
    ) {
        if (respectHold && requireHoldToKeep && !isHoldPressed()) {
            ctx.run("setprop debug.oculus.headlock 0")
            return
        }
        val rp = HeadlockSensor.clampRotation(pitch)
        val ry = HeadlockSensor.clampRotation(yaw)
        val rr = HeadlockSensor.clampRotation(roll)
        ctx.run(
            "setprop debug.oculus.headlock 1; " +
                    "setprop debug.oculus.headlock.translation.x ${fmt(x)}; " +
                    "setprop debug.oculus.headlock.translation.y ${fmt(y)}; " +
                    "setprop debug.oculus.headlock.translation.z ${fmt(z)}; " +
                    "setprop debug.oculus.headlock.rotation.x ${fmt(rp)}; " +
                    "setprop debug.oculus.headlock.rotation.y ${fmt(ry)}; " +
                    "setprop debug.oculus.headlock.rotation.z ${fmt(rr)}"
        )
    }

    fun writePoseHeadRelative(
        ctx: Utils.ActionContext,
        x: Float, y: Float, z: Float,
        respectHold: Boolean = true
    ) {
        writeOffset(ctx, x, y, z, respectHold)
    }

    fun headlockOff(ctx: Utils.ActionContext) {
        ctx.run(
            "setprop debug.oculus.headlock 0; " +
                    "setprop debug.oculus.headlock.translation.x 0; " +
                    "setprop debug.oculus.headlock.translation.y 0; " +
                    "setprop debug.oculus.headlock.translation.z 0"
        )
    }

    fun disarm(ctx: Utils.ActionContext) {
        synchronized(armLock) {
            if (armRefCount > 0) armRefCount--
            if (armRefCount > 0) return
            ctx.run(
                "setprop debug.oculus.headlock 0; " +
                        "setprop debug.oculus.headlock.translation.x 0; " +
                        "setprop debug.oculus.headlock.translation.y 0; " +
                        "setprop debug.oculus.headlock.translation.z 0; " +
                        "setprop debug.oculus.headlock.rotation.x \"\"; " +
                        "setprop debug.oculus.headlock.rotation.y \"\"; " +
                        "setprop debug.oculus.headlock.rotation.z \"\"; " +
                        "setprop debug.oculus.horizOffsetMeters 0.0000; " +
                        "setprop debug.oculus.vertOffsetMeters 0.0000; " +
                        "setprop debug.oculus.ipd 0.0640"
            )
            HeadlockSensor.clearBaseline()
            stopWatchdog()
        }
    }

    fun forceDisarm(ctx: Utils.ActionContext) {
        synchronized(armLock) {
            armRefCount = 0
            ctx.run(
                "setprop debug.oculus.headlock 0; " +
                        "setprop debug.oculus.headlock.translation.x 0; " +
                        "setprop debug.oculus.headlock.translation.y 0; " +
                        "setprop debug.oculus.headlock.translation.z 0; " +
                        "setprop debug.oculus.headlock.rotation.x \"\"; " +
                        "setprop debug.oculus.headlock.rotation.y \"\"; " +
                        "setprop debug.oculus.headlock.rotation.z \"\""
            )
            HeadlockSensor.clearBaseline()
            stopWatchdog()
        }
    }

    fun fmt(v: Float): String = "%.5f".format(java.util.Locale.US, v)
}