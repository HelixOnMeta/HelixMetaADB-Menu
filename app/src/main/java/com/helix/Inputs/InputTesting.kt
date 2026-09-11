package com.helix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI

// contact blaku64th on discord if you have any issues ^^
class InputTesting(
    private val context: Context,
    private val container: LinearLayout,
    private val scope: CoroutineScope,
    private val adbCtx: Utils.ActionContext? = null
) {

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private fun color(resId: Int) = ContextCompat.getColor(context, resId)
    private val accent by lazy { runCatching { color(R.color.accent) }.getOrDefault(0xFF00E5FF.toInt()) }
    private val textPrimary by lazy { runCatching { color(R.color.text_primary) }.getOrDefault(Color.WHITE) }
    private val textSecondary by lazy { runCatching { color(R.color.text_secondary) }.getOrDefault(0xFFAAAAAA.toInt()) }
    private val onAccent by lazy { runCatching { color(R.color.on_accent) }.getOrDefault(Color.BLACK) }

    private lateinit var tvStatus: TextView
    private lateinit var tvButtons: TextView
    private lateinit var tvSticks: TextView
    private lateinit var tvHead: TextView
    private lateinit var tvDrift: TextView
    private lateinit var tvScale: TextView
    private lateinit var tvSensors: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvDevices: TextView

    private var pollJob: Job? = null
    private var debugCaptureOn = false
    private var lastSensorMsg = "not started yet"
    private var imuProbeListener: SensorEventListener? = null

    fun build() {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(px(12), px(8), px(12), px(8))
        container.post { Anim.staggerIn(container, perViewDelay = 18L, duration = 200L, maxDelay = 220L) }

        tvStatus = monoText("Status: starting…", 12f, accent)
        container.addView(tvStatus)

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(4))
        }
        row1.addView(makeBtn("Force Start") { forceStartAll() })
        row1.addView(makeBtn("Recenter") {
            forceStartAll()
            Input.recenter()
            HeadlockSensor.captureBaselineFromTracker(OrientationTracker.getInstance())
            Fly.resetPosition()
            tvDebug.text = "Recentered orientation baseline"
        })
        row1.addView(makeBtn("Release") {
            Input.releasefallback()
            OrientationTracker.getInstance().stop()
            lastSensorMsg = "stopped by user"
        })
        container.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2), 0, px(8))
        }
        row2.addView(makeBtn("List Devices") { listInputDevices() })
        row2.addView(makeBtn("List Sensors") { listAllSensors() })
        row2.addView(makeBtn("Debug ON/OFF") {
            debugCaptureOn = !debugCaptureOn
            AdbButtonInput.debugCapture(debugCaptureOn)
            tvDebug.text = if (debugCaptureOn)
                "Debug capture ON — press buttons / move sticks on the Quest, then Show Log"
            else
                "Debug capture OFF"
        })
        row2.addView(makeBtn("Show Log") {
            val log = AdbButtonInput.debugLog()
            tvDebug.text = if (log.isBlank()) "(no lines yet — is ADB connected? is getevent running?)" else log.takeLast(5000)
        })
        container.addView(row2)

        container.addView(sectionHeader("Status / sensors"))

        container.addView(sectionHeader("Buttons / triggers"))
        tvButtons = monoText("…", 12f, textPrimary)
        container.addView(tvButtons)

        container.addView(sectionHeader("Thumbsticks"))
        tvSticks = monoText("…", 12f, textPrimary)
        container.addView(tvSticks)

        container.addView(sectionHeader("Head rotation (Euler °)"))
        tvHead = monoText("…", 12f, textPrimary)
        container.addView(tvHead)

        tvDrift = monoText(driftLabel(), 11f, textSecondary)
        container.addView(tvDrift)
        val driftSeek = SeekBar(context).apply {
            max = 100
            progress = (50 + (HeadlockSensor.pitchDriftCorrectionRate / 0.001f)).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    HeadlockSensor.pitchDriftCorrectionRate = (progress - 50) * 0.001f
                    tvDrift.text = driftLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(driftSeek)
        val driftRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2), 0, px(4))
        }
        driftRow.addView(makeBtn("Reset drift correction") {
            HeadlockSensor.resetDriftCorrection()
            tvDrift.text = driftLabel()
        })
        container.addView(driftRow)

        container.addView(sectionHeader("Rotation scale (prop units per degree)"))
        tvScale = monoText(scaleLabel(), 11f, textSecondary)
        container.addView(tvScale)
        container.addView(scaleSeekBar(
            min = 1,
            max = 100,
            initial = HeadlockSensor.pitchScale,
            onChange = { v -> HeadlockSensor.pitchScale = v; tvScale.text = scaleLabel() }
        ))
        container.addView(scaleSeekBar(
            min = 1,
            max = 1000,
            initial = HeadlockSensor.yawScale,
            onChange = { v -> HeadlockSensor.yawScale = v; tvScale.text = scaleLabel() }
        ))


        container.addView(sectionHeader("All sensors visible to this app"))
        val sensorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(4))
        }
        sensorRow.addView(makeBtn("Copy") { copySensorList() })
        sensorRow.addView(makeBtn("Probe IMU") { toggleImuProbe() })
        container.addView(sensorRow)
        val sensorScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(160)
            )
        }
        tvSensors = monoText("Tap List Sensors", 11f, textSecondary)
        sensorScroll.addView(tvSensors)
        container.addView(sensorScroll)

        container.addView(sectionHeader("/dev/input devices (ADB)"))
        tvDevices = monoText("Tap List Devices (needs ADB connected)", 11f, textSecondary)
        container.addView(tvDevices)

        container.addView(sectionHeader("Raw getevent debug"))
        val debugScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(140)
            )
        }
        tvDebug = monoText("Force Start → connect ADB → Debug ON → press buttons → Show Log", 11f, textSecondary)
        debugScroll.addView(tvDebug)
        container.addView(debugScroll)

        forceStartAll()
        listAllSensors()
        startPolling()
    }

    fun forceStartAll() {
        AdbButtonInput.acquire()

        val tracker = OrientationTracker.getInstance(context)
        if (tracker.isRunning) tracker.stop()
        val ok = tracker.start()
        lastSensorMsg = buildString {
            append(tracker.sensorReport().trim())
            append(if (ok) "\n→ start OK" else "\n→ START FAILED — no usable sensors at all")
        }
    }

    private fun listAllSensors() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.name.lowercase() }
        tvSensors.text = buildString {
            appendLine("Total sensors visible to app: ${all.size}")
            if (all.isEmpty()) {
                appendLine("(NONE — OS is not exposing any sensors to this process)")
            } else {
                all.forEachIndexed { i, s ->
                    appendLine("${i + 1}. \"${s.name}\"")
                    appendLine("   vendor=${s.vendor}  version=${s.version}")
                    appendLine("   type=${s.type} (${s.stringType})")
                }
            }
        }
    }

    private fun copySensorList() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sensor list", tvSensors.text.toString()))
        tvSensors.text = "${tvSensors.text}\n\n(copied to clipboard)"
    }

    private fun findSensorByNameContains(needle: String): Sensor? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.name.contains(needle, ignoreCase = true) }
    }

    private fun toggleImuProbe() {
        val existing = imuProbeListener
        if (existing != null) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(existing)
            imuProbeListener = null
            tvSensors.text = "${tvSensors.text}\n\n(probe stopped)"
            return
        }

        val sensor = findSensorByNameContains("Syncboss IMU")
        if (sensor == null) {
            tvSensors.text = "${tvSensors.text}\n\n(no sensor with \"Syncboss IMU\" in its name — tap List Sensors first)"
            return
        }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var samplesLeft = 8
        val log = StringBuilder("Probing \"${sensor.name.take(40)}...\" (type=${sensor.type})\n")
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (samplesLeft <= 0) return
                samplesLeft--
                log.append("[${8 - samplesLeft}] n=${event.values.size} accuracy=${event.accuracy} values=")
                log.append(event.values.joinToString(", ") { "%.4f".format(it) })
                log.append('\n')
                tvSensors.text = log.toString()
                if (samplesLeft <= 0) {
                    sm.unregisterListener(this)
                    imuProbeListener = null
                    tvSensors.text = "${tvSensors.text}\n(probe finished — tap Copy to grab this)"
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        imuProbeListener = listener
        val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!ok) {
            imuProbeListener = null
            tvSensors.text = "${log}(registerListener returned false — sensor exists but won't register)"
        } else {
            tvSensors.text = log.toString()
        }
    }

    fun startPolling() {
        pollJob?.cancel()
        forceStartAll()
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val snap = buildSnapshot()
                withContext(Dispatchers.Main) {
                    tvStatus.text = snap.status
                    tvButtons.text = snap.buttons
                    tvSticks.text = snap.sticks
                    tvHead.text = snap.head
                    tvDrift.text = driftLabel()
                }
                delay(50L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        imuProbeListener?.let {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(it)
        }
        imuProbeListener = null
    }

    private fun listInputDevices() {
        val ctx = adbCtx
        if (ctx == null) {
            tvDevices.text = "No ADB ActionContext — connect first, or open Console and ensure connected."
            return
        }
        scope.launch(Dispatchers.IO) {
            val out = runCatching {
                ctx.run("getevent -il 2>/dev/null | head -n 120")
            }.getOrElse { "[error] ${it.message}" }
            withContext(Dispatchers.Main) {
                tvDevices.text = if (out.isBlank()) "(empty — is ADB connected?)" else out.take(3000)
            }
        }
    }

    private data class Snapshot(
        val status: String,
        val buttons: String,
        val sticks: String,
        val head: String
    )

    private fun buildSnapshot(): Snapshot {
        val tracking = ControllerInput.tracking
        val adbConnected = AdbButtonInput.connected
        val tracker = OrientationTracker.getInstance(context)
        val orientRunning = tracker.isRunning
        val hasForward = tracker.getForwardOrNull() != null

        val mode = when {
            tracking -> "NATIVE TRACKING (ControllerInput)"
            adbConnected -> "ADB FALLBACK (getevent)"
            else -> "NO LIVE INPUT"
        }

        val status = buildString {
            appendLine("Mode: $mode")
            appendLine("ControllerInput.tracking = $tracking")
            appendLine("AdbButtonInput.connected = $adbConnected  refCount=${AdbButtonInput.refCount}")
            appendLine("  nodes: L=${AdbButtonInput.leftDeviceNode}  R=${AdbButtonInput.rightDeviceNode}")
            appendLine("OrientationTracker.isRunning = $orientRunning  hasSample=$hasForward")
            appendLine("activeSource = ${OrientationTracker.getInstance(context).activeSource}")
            appendLine(lastSensorMsg)
            if (!tracking && !adbConnected) {
                appendLine()
                appendLine("→ Connect wireless ADB, then Force Start.")
                appendLine("→ Buttons come from Quest via getevent (event nodes above).")
            }
            if (!orientRunning) {
                appendLine("→ Orientation uses THIS device's sensors (phone or Quest).")
                appendLine("→ Vector sensors missing is OK — accel+mag fallback should still work.")
            }
        }

        fun on(v: Boolean) = if (v) "ON " else "off"

        val buttons = buildString {
            appendLine("LEFT")
            appendLine("  X=${on(Input.leftX())}  Y=${on(Input.leftY())}  Menu=${on(Input.leftMenu())}  Home=${on(Input.leftHome())}")
            appendLine("  Trigger=${on(Input.leftTrigger())}  Squeeze=${on(Input.leftSqueeze())}  StickClick=${on(Input.leftThumbstickClick())}")
            appendLine("RIGHT")
            appendLine("  A=${on(Input.rightA())}  B=${on(Input.rightB())}")
            appendLine("  Trigger=${on(Input.rightTrigger())}  Squeeze=${on(Input.rightSqueeze())}  StickClick=${on(Input.rightThumbstickClick())}")
            if (tracking) {
                appendLine()
                appendLine("Analog (native)")
                appendLine("  L Trig=${"%.2f".format(ControllerInput.leftTriggerValue)}  L Sq=${"%.2f".format(ControllerInput.leftSqueezeValue)}")
                appendLine("  R Trig=${"%.2f".format(ControllerInput.rightTriggerValue)}  R Sq=${"%.2f".format(ControllerInput.rightSqueezeValue)}")
            }
        }

        val sticks = buildString {
            appendLine("Left  X=${fmtAxis(Input.leftThumbstickX())}  Y=${fmtAxis(Input.leftThumbstickY())}")
            appendLine("Right X=${fmtAxis(Input.rightThumbstickX())}  Y=${fmtAxis(Input.rightThumbstickY())}")
            if (!adbConnected && !tracking) {
                appendLine("(zeros expected until ADB getevent is connected)")
            }
        }

        return Snapshot(status.trimEnd(), buttons.trimEnd(), sticks.trimEnd(), buildHeadEuler())
    }

    private fun fmtAxis(v: Float): String = "%+.3f".format(v)

    private fun driftLabel(): String = "X drift correction — rate=${
        "%+.3f".format(HeadlockSensor.pitchDriftCorrectionRate)
    }/tick (0.5s)   accumulated=${"%+.3f".format(HeadlockSensor.pitchDriftCorrection)}"

    private fun scaleLabel(): String = "pitch=${"%.0f".format(HeadlockSensor.pitchScale)}  " +
            "yaw=${"%.0f".format(HeadlockSensor.yawScale)}   " +
            "(watch Head rotation above while turning your head — find the value where it tracks 1:1, doesn't sit at zero, and doesn't pin at the clamp)"

    private fun scaleSeekBar(min: Int, max: Int, initial: Float, onChange: (Float) -> Unit): SeekBar =
        SeekBar(context).apply {
            this.max = max - min
            progress = (initial.toInt() - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    onChange((progress + min).toFloat())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

    private fun buildHeadEuler(): String {
        if (ControllerInput.tracking) {
            val qx = ControllerInput.headRotX
            val qy = ControllerInput.headRotY
            val qz = ControllerInput.headRotZ
            val qw = ControllerInput.headRotW
            val (pitch, yaw, roll) = quaternionToEuler(qx, qy, qz, qw)
            val correctedPitch = pitch + HeadlockSensor.pitchDriftCorrection
            return buildString {
                appendLine("Source: ControllerInput quaternion")
                appendLine("  Pitch (X): ${"%.2f".format(correctedPitch)}°  (raw=${"%.2f".format(pitch)}°)")
                appendLine("  Yaw   (Y): ${"%.2f".format(yaw)}°")
                appendLine("  Roll  (Z): ${"%.2f".format(roll)}°")
                appendLine("  Quat: ${"%.3f".format(qx)}, ${"%.3f".format(qy)}, ${"%.3f".format(qz)}, ${"%.3f".format(qw)}")
                appendLine("  Pos:  ${"%.3f".format(ControllerInput.headPosX)}, ${"%.3f".format(ControllerInput.headPosY)}, ${"%.3f".format(ControllerInput.headPosZ)}")
            }
        }

        val tracker = OrientationTracker.getInstance(context)
        if (!tracker.isRunning) {
            return "OrientationTracker NOT running.\nTap Force Start.\nSensors: $lastSensorMsg"
        }

        val forward = tracker.getForwardOrNull()
            ?: return "Tracker running but no sample yet.\nMove/tilt this device, then tap Recenter.\nSensors: $lastSensorMsg"

        val pitch = Math.toDegrees(asin(forward[1].toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val yaw = Math.toDegrees(atan2(forward[0].toDouble(), -forward[2].toDouble())).toFloat()
        val correctedPitch = pitch + HeadlockSensor.pitchDriftCorrection
        return buildString {
            appendLine("Source: OrientationTracker (this device sensors)")
            appendLine("  Pitch (X): ${"%.2f".format(correctedPitch)}°")
            appendLine("  Yaw   (Y): ${"%.2f".format(yaw)}°")
            appendLine("  Roll  (Z): (n/a from forward only)")
            appendLine("  Forward: ${"%.3f".format(forward[0])}, ${"%.3f".format(forward[1])}, ${"%.3f".format(forward[2])}")
            appendLine("Tip: Recenter, then tilt — numbers should change.")
        }
    }

    private fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): Triple<Float, Float, Float> {
        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = atan2(sinrCosp, cosrCosp)

        val sinp = 2f * (w * y - z * x)
        val pitch = if (kotlin.math.abs(sinp) >= 1f) {
            (PI.toFloat() / 2f) * kotlin.math.sign(sinp)
        } else {
            asin(sinp)
        }

        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = atan2(sinyCosp, cosyCosp)

        val rad2deg = 180f / PI.toFloat()
        return Triple(pitch * rad2deg, yaw * rad2deg, roll * rad2deg)
    }


    private fun sectionHeader(text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            textSize = 12f
            letterSpacing = 0.05f
            isAllCaps = true
            setPadding(0, px(12), 0, px(4))
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun monoText(initial: String, size: Float, color: Int): TextView =
        TextView(context).apply {
            text = initial
            setTextColor(color)
            textSize = size
            typeface = Typeface.MONOSPACE
            setPadding(0, px(2), 0, px(2))
        }

    private fun makeBtn(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(onAccent)
            setPadding(px(10), px(6), px(10), px(6))
            setOnClickListener { onClick() }
            runCatching { setBackgroundResource(R.drawable.bg_button_accent) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = px(6)
            layoutParams = lp
            Anim.bindPressFeedback(this)
        }
}