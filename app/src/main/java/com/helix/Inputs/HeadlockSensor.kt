package com.helix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sqrt

// contact blaku64th on discord if you have any issues ^^
object HeadlockSensor {

    @Volatile var offsetPitch: Float = 0f
        private set
    @Volatile var offsetYaw: Float = 0f
        private set
    @Volatile var offsetRoll: Float = 0f
        private set

    @Volatile private var basePitchRaw: Float = 0f
    @Volatile private var baseYawRaw: Float = 0f
    @Volatile private var baseRollRaw: Float = 0f

    private const val CLAMP_DEG = 179f

    @Volatile var pitchScale: Float = 100f
    @Volatile var yawScale: Float = 1000f
    @Volatile var rollScale: Float = 100f

    @Volatile var pitchDriftCorrectionRate: Float = 0.02f
    @Volatile var pitchDriftCorrection: Float = 0f
        private set

    private val driftScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var driftJob: Job? = null

    init {
        driftJob = driftScope.launch {
            while (isActive) {
                delay(500L)
                if (pitchDriftCorrectionRate != 0f) {
                    pitchDriftCorrection -= pitchDriftCorrectionRate
                }
            }
        }
    }

    fun resetDriftCorrection() {
        pitchDriftCorrection = 0f
    }

    fun captureBaselineFromTracker(tracker: OrientationTracker?) {
        val (p, y, r) = rawEuler(tracker)
        basePitchRaw = p
        baseYawRaw = y
        baseRollRaw = r
        resetDriftCorrection()
    }

    fun clearBaseline() {
        offsetPitch = 0f; offsetYaw = 0f; offsetRoll = 0f
        basePitchRaw = 0f; baseYawRaw = 0f; baseRollRaw = 0f
    }

    fun composedRotation(tracker: OrientationTracker): FloatArray {
        val rel = relativeEulerDegrees(tracker)
        return applyScaleAndOffset(rel[0], rel[1], rel[2])
    }

    fun relativeEulerDegrees(tracker: OrientationTracker): FloatArray {
        val (rawPitch, rawYaw, rawRoll) = rawEuler(tracker)
        return floatArrayOf(
            rawPitch - basePitchRaw + pitchDriftCorrection,
            rawYaw - baseYawRaw,
            rawRoll - baseRollRaw
        )
    }

    fun applyScaleAndOffset(pitchDeg: Float, yawDeg: Float, rollDeg: Float): FloatArray = floatArrayOf(
        (pitchDeg * pitchScale) + offsetPitch,
        (yawDeg * yawScale) + offsetYaw,
        (rollDeg * rollScale) + offsetRoll
    )


    private fun rawEuler(tracker: OrientationTracker?): Triple<Float, Float, Float> {
        if (ControllerInput.tracking) return headsetEuler()
        val t = tracker ?: return Triple(0f, 0f, 0f)
        val f = forwardOf(t) ?: return Triple(0f, 0f, 0f)
        return Triple(pitchOf(f), yawOf(f), 0f)
    }

    private fun headsetEuler(): Triple<Float, Float, Float> {
        val qx = ControllerInput.headRotX
        val qy = ControllerInput.headRotY
        val qz = ControllerInput.headRotZ
        val qw = ControllerInput.headRotW

        val sinrCosp = 2f * (qw * qx + qy * qz)
        val cosrCosp = 1f - 2f * (qx * qx + qy * qy)
        val roll = atan2(sinrCosp, cosrCosp)

        val sinp = 2f * (qw * qy - qz * qx)
        val pitch = if (kotlin.math.abs(sinp) >= 1f) {
            (PI.toFloat() / 2f) * sign(sinp)
        } else {
            asin(sinp)
        }

        val sinyCosp = 2f * (qw * qz + qx * qy)
        val cosyCosp = 1f - 2f * (qy * qy + qz * qz)
        val yaw = atan2(sinyCosp, cosyCosp)

        val rad2deg = 180f / PI.toFloat()
        return Triple(pitch * rad2deg, yaw * rad2deg, roll * rad2deg)
    }

    fun clampRotation(v: Float): Float = v.coerceIn(-CLAMP_DEG, CLAMP_DEG)


    fun setOffset(pitch: Float = offsetPitch, yaw: Float = offsetYaw, roll: Float = offsetRoll) {
        offsetPitch = pitch; offsetYaw = yaw; offsetRoll = roll
    }

    fun clearOffset() {
        offsetPitch = 0f; offsetYaw = 0f; offsetRoll = 0f
    }

    fun faceBackwards() = setOffset(pitch = offsetPitch, yaw = 180f, roll = offsetRoll)

    fun faceUpsideDown() = setOffset(pitch = offsetPitch, yaw = offsetYaw, roll = 180f)

    fun offsetStatus(): String =
        if (offsetPitch == 0f && offsetYaw == 0f && offsetRoll == 0f) "offset=none"
        else "offset=(p=${"%.1f".format(offsetPitch)}, y=${"%.1f".format(offsetYaw)}, r=${"%.1f".format(offsetRoll)})"

    private fun forwardOf(tracker: OrientationTracker): FloatArray? {
        val f = runCatching { tracker.getForwardOrNull() }.getOrNull() ?: return null
        val len = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
        if (len < 1e-4f) return null
        return floatArrayOf(f[0] / len, f[1] / len, f[2] / len)
    }

    private fun pitchOf(f: FloatArray): Float =
        Math.toDegrees(asin(f[1].toDouble().coerceIn(-1.0, 1.0))).toFloat()

    private fun yawOf(f: FloatArray): Float =
        Math.toDegrees(atan2(f[0].toDouble(), -f[2].toDouble())).toFloat()
}