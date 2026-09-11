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
import kotlin.math.cos
import kotlin.math.sin

// contact blaku64th on discord if you have any issues ^^
object LongArms {

    var distance: Float = 5f
    var followHead: Boolean = false
    var syncRotation: Boolean = true

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false

    fun applyOnce(ctx: Utils.ActionContext) {
        val tracker = OrientationTracker.getInstance()
        tracker.start()
        Thread.sleep(40)
        HeadlockSensor.captureBaselineFromTracker(tracker)

        val (off, rot) = orbitOffsetAndRotation(tracker)
        runCatching {
            HeadlockHelper.armOffsetRamped(ctx, off[0], off[1], off[2])
            if (syncRotation) {
                HeadlockHelper.writePose(ctx, off[0], off[1], off[2], rot[0], rot[1], rot[2])
            }
            armed = true
            enabled = true
            ctx.log(
                "[LongArms] applied (${"%.2f".format(off[0])}, ${"%.2f".format(off[1])}, ${"%.2f".format(off[2])}) " +
                        "rotSync=$syncRotation ${HeadlockSensor.offsetStatus()}"
            )
        }.onFailure {
            ctx.log("[LongArms] apply failed: ${it.message}")
        }
    }

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        applyOnce(ctx)
        followHead = true
        val handler = CoroutineExceptionHandler { _, e ->
            ctx.log("[LongArms error] ${e.message}")
        }
        job = scope.launch(handler) {
            val tracker = OrientationTracker.getInstance()
            while (isActive) {
                try {
                    if (followHead && armed) {
                        val (off, rot) = orbitOffsetAndRotation(tracker)
                        withContext(Dispatchers.IO) {
                            if (syncRotation) {
                                HeadlockHelper.writePose(
                                    ctx, off[0], off[1], off[2], rot[0], rot[1], rot[2]
                                )
                            } else {
                                HeadlockHelper.writeOffset(ctx, off[0], off[1], off[2])
                            }
                        }
                    }
                } catch (e: Throwable) {
                    ctx.log("[LongArms error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        followHead = false
        enabled = false
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    private fun orbitOffsetAndRotation(tracker: OrientationTracker): Pair<FloatArray, FloatArray> {
        val rel = HeadlockSensor.relativeEulerDegrees(tracker)
        val pitchRel = rel[0]
        val yawRel = rel[1]
        val rollRel = rel[2]

        val yawRad = Math.toRadians(yawRel.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitchRel.toDouble()).toFloat()

        val fx = sin(yawRad) * cos(pitchRad)
        val fy = sin(pitchRad)
        val fz = -cos(yawRad) * cos(pitchRad)

        val offset = floatArrayOf(fx * distance, fy * distance, fz * distance)

        val faceYaw = yawRel + 180f
        val facePitch = -pitchRel
        val faceRoll = -rollRel

        val rotation = HeadlockSensor.applyScaleAndOffset(facePitch, faceYaw, faceRoll)
        return Pair(offset, rotation)
    }
}