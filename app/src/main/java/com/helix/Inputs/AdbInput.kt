package com.helix

import android.annotation.SuppressLint
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

// contact blaku64th on discord if you have any issues ^^
object GamepadInput {

    interface Listener {
        fun onButton(name: String, down: Boolean) {}
        fun onStick(lx: Float, ly: Float, rx: Float, ry: Float) {}
        fun onTriggers(lt: Float, rt: Float) {}
        fun onConnectionChanged(deviceName: String?, connected: Boolean) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var deviceListener: InputManager.InputDeviceListener? = null

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        val name = KeyEvent.keyCodeToString(keyCode)
        listeners.forEach { it.onButton(name, true) }
        return true
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        val name = KeyEvent.keyCodeToString(keyCode)
        listeners.forEach { it.onButton(name, false) }
        return true
    }

    fun handleMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        listeners.forEach {
            it.onStick(lx, ly, rx, ry)
            it.onTriggers(lt, rt)
        }
        return true
    }

    fun startWatching(inputManager: InputManager) {
        if (deviceListener != null) return
        deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId)
                listeners.forEach { it.onConnectionChanged(device?.name, true) }
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                listeners.forEach { it.onConnectionChanged(null, false) }
            }
            override fun onInputDeviceChanged(deviceId: Int) {}
        }
        inputManager.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
    }

    fun stopWatching(inputManager: InputManager) {
        deviceListener?.let { inputManager.unregisterInputDeviceListener(it) }
        deviceListener = null
    }
}
object AdbButtonInput {

    interface Listener {
        fun onButton(name: String, down: Boolean) {}
        fun onStick(lx: Float, ly: Float, rx: Float, ry: Float) {}
    }

    const val RIGHT_TRIGGER = "right_trigger"
    const val LEFT_TRIGGER = "left_trigger"
    const val RIGHT_GRIP = "right_grip"
    const val LEFT_GRIP = "left_grip"
    const val A_BUTTON = "a_button"
    const val B_BUTTON = "b_button"
    const val X_BUTTON = "x_button"
    const val Y_BUTTON = "y_button"
    const val MENU_BUTTON = "menu_button"
    const val OCULUS_BUTTON = "oculus_button"

    @Volatile var rightDeviceNode: String = "event4"
    @Volatile var leftDeviceNode: String = "event5"

    @Volatile var rightA = false
    @Volatile var rightB = false
    @Volatile var rightTrigger = false
    @Volatile var rightSqueeze = false
    @Volatile var rightThumbstickClick = false
    @Volatile var rightThumbstickX = 0f
    @Volatile var rightThumbstickY = 0f
    @Volatile var rightTriggerValue = 0f
    @Volatile var rightSqueezeValue = 0f

    @Volatile var Home = false
    @Volatile var leftX = false
    @Volatile var leftY = false
    @Volatile var Menu = false
    @Volatile var leftTrigger = false
    @Volatile var leftSqueeze = false
    @Volatile var leftThumbstickClick = false
    @Volatile var leftThumbstickX = 0f
    @Volatile var leftThumbstickY = 0f
    @Volatile var leftTriggerValue = 0f
    @Volatile var leftSqueezeValue = 0f

    @Volatile var connected = false
        private set

    @Volatile var lastEvent: String = "none"
        private set

    @Volatile var lastDevice: String = ""
        private set

    @Volatile var faceButtonsSeen = false
        private set

    @Volatile var sticksSeen = false
        private set

    private var job: Job? = null
    @Volatile var refCount = 0
        private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val eventLineRegex =
        Regex("""/dev/input/(event\d+):\s+(EV_\w+)\s+(\w+)\s+(\S+)""", RegexOption.IGNORE_CASE)

    private val addDeviceRegex =
        Regex("""add device \d+:\s*/dev/input/(event\d+)""", RegexOption.IGNORE_CASE)
    private val deviceNameRegex = Regex("""^\s*name:\s*"(.*)"\s*$""")
    @Volatile private var pendingListingNode: String? = null

    private const val DEADZONE = 0.16f
    private const val TRIG_ON = 0.55f
    private const val TRIG_OFF = 0.35f
    private const val STICK_MAX = 65533f
    private const val STICK_MID = STICK_MAX / 2f
    private const val TRIG_MAX_DEFAULT = 1023f

    @Volatile private var axMax: Long = 255
    @Volatile private var rangeLearned = false

    @Volatile private var debugCaptureEnabled = false
    private val debugLines = ArrayDeque<String>()
    private val debugLock = Any()

    @Volatile private var learnedRight = false
    @Volatile private var learnedLeft = false

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun setNodes(right: String, left: String) {
        rightDeviceNode = normalizeNode(right)
        leftDeviceNode = normalizeNode(left)
        learnedRight = true
        learnedLeft = true
    }

    fun swapHands() {
        val t = rightDeviceNode
        rightDeviceNode = leftDeviceNode
        leftDeviceNode = t
    }

    fun relearnNodes() {
        rightDeviceNode = "event4"
        leftDeviceNode = "event5"
        learnedRight = false
        learnedLeft = false
        faceButtonsSeen = false
        sticksSeen = false
        rangeLearned = false
        axMax = 255
    }

    fun nodesSummary(): String =
        "R=$rightDeviceNode L=$leftDeviceNode last=$lastDevice " +
                "face=${if (faceButtonsSeen) "yes" else "NO"} " +
                "sticks=${if (sticksSeen) "yes" else "NO"}"

    fun capabilityHint(): String = buildString {
        appendLine("Live getevent on this Quest typically delivers:")
        appendLine("  • Trigger  BTN_TR (right node) / BTN_TL (left node)")
        appendLine("  • Grip     BTN_TR2 / BTN_TL2")
        appendLine("  • Menu     BTN_START")
        appendLine("  • Oculus   KEY_FORWARD")
        appendLine("(the driver just never emits these to /dev/input for a")
        appendLine("plain 2D app — use PanelGamepadInput instead, see README):")
        appendLine("  • A/B/X/Y face buttons")
        appendLine("  • Thumbstick axes ABS_X/Y")
        appendLine("nodes: ${nodesSummary()}  (auto-learned from device names via getevent -i)")
        appendLine("last: $lastEvent")
    }

    fun notePanelInputSeen(face: Boolean, sticks: Boolean) {
        if (face) faceButtonsSeen = true
        if (sticks) sticksSeen = true
    }

    fun debugCapture(enabled: Boolean) {
        debugCaptureEnabled = enabled
        if (enabled) synchronized(debugLock) { debugLines.clear() }
    }

    fun debugLog(): String = synchronized(debugLock) { debugLines.joinToString("\n") }

    fun acquire() {
        synchronized(lock) {
            refCount++
            if (job?.isActive != true) startInternal()
        }
    }

    fun release() {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount == 0) stopInternal()
        }
    }

    private fun startInternal() {
        connected = false
        job = scope.launch {
            while (isActive) {
                try {
                    readOnce()
                } catch (_: Throwable) {
                    connected = false
                }
                if (isActive) delay(1000)
            }
        }
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        connected = false
    }

    private suspend fun readOnce() {
        val manager = AppAdbConnectionManager.getInstance(AppContext.app)
        val stream = manager.openStream("shell:getevent -l")
        try {
            connected = true
            val reader = BufferedReader(InputStreamReader(stream.openInputStream()))
            while (job?.isActive == true) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break
                parseLine(line)
            }
        } finally {
            connected = false
            runCatching { stream.close() }
        }
    }

    fun parseLine(line: String) {
        if (debugCaptureEnabled) {
            synchronized(debugLock) {
                debugLines.addLast(line)
                if (debugLines.size > 400) debugLines.removeFirst()
            }
        }

        val match = eventLineRegex.find(line) ?: return
        val (deviceRaw, evType, code, value) = match.destructured
        val device = normalizeNode(deviceRaw)
        lastDevice = device
        val codeU = code.uppercase(Locale.US)

        when (evType.uppercase(Locale.US)) {
            "EV_KEY" -> {
                val down = when {
                    value.equals("DOWN", true) -> true
                    value.equals("UP", true) -> false
                    else -> return
                }
                applyKey(device, codeU, down)
            }
            "EV_ABS" -> {
                val raw = parseAbsValue(value) ?: return
                applyAxis(device, codeU, raw)
            }
        }
    }
    private fun parseDeviceListingLine(line: String): Boolean {
        addDeviceRegex.find(line)?.let {
            pendingListingNode = normalizeNode(it.groupValues[1])
            return true
        }
        deviceNameRegex.find(line)?.let { m ->
            val node = pendingListingNode ?: return false
            pendingListingNode = null
            val name = m.groupValues[1].lowercase(Locale.US)
            when {
                "right" in name -> {
                    rightDeviceNode = node
                    learnedRight = true
                    if (node == normalizeNode(leftDeviceNode)) leftDeviceNode = otherDefaultNode(node)
                }
                "left" in name -> {
                    leftDeviceNode = node
                    learnedLeft = true
                    if (node == normalizeNode(rightDeviceNode)) rightDeviceNode = otherDefaultNode(node)
                }
            }
            lastEvent = "discovered $node = \"${m.groupValues[1]}\""
            return true
        }
        return false
    }

    private fun otherDefaultNode(node: String) = if (node == "event4") "event5" else "event4"

    private fun normalizeNode(s: String): String {
        val t = s.trim().lowercase(Locale.US)
        return Regex("""event\d+""").find(t)?.value ?: t
    }
    private fun isRightDevice(device: String): Boolean? {
        val d = normalizeNode(device)
        return when (d) {
            normalizeNode(rightDeviceNode) -> true
            normalizeNode(leftDeviceNode) -> false
            else -> null
        }
    }
    private fun isRightDeviceOrInfer(device: String): Boolean? {
        isRightDevice(device)?.let { return it }
        val d = normalizeNode(device)
        return when {
            learnedRight && !learnedLeft && d != normalizeNode(rightDeviceNode) -> false
            learnedLeft && !learnedRight && d != normalizeNode(leftDeviceNode) -> true
            else -> null
        }
    }

    private fun applyKey(device: String, code: String, down: Boolean) {
        lastEvent = "$device $code ${if (down) "DOWN" else "UP"}"

        val right = isRightDevice(device)

        val asRight = right ?: when (code) {
            "BTN_TR", "BTN_TR2", "BTN_THUMBR" -> true
            "BTN_TL", "BTN_TL2", "BTN_THUMBL" -> false
            else -> return
        }

        when (code) {
            "BTN_GAMEPAD", "BTN_SOUTH", "BTN_A" -> {
                faceButtonsSeen = true
                rightA = down
                emit(A_BUTTON, down)
            }
            "BTN_EAST", "BTN_B" -> {
                faceButtonsSeen = true
                rightB = down
                emit(B_BUTTON, down)
            }
            "BTN_WEST", "BTN_X" -> {
                faceButtonsSeen = true
                leftX = down
                emit(X_BUTTON, down)
            }
            "BTN_NORTH", "BTN_Y" -> {
                faceButtonsSeen = true
                leftY = down
                emit(Y_BUTTON, down)
            }

            "KEY_FORWARD" -> {
                Home = down
                emit(OCULUS_BUTTON, down)
            }
            "BTN_START", "BTN_SELECT" -> {
                Menu = down
                emit(MENU_BUTTON, down)
            }
            "BTN_TR2" -> {
                rightSqueeze = down
                emit(RIGHT_GRIP, down)
            }
            "BTN_TL2" -> {
                leftSqueeze = down
                emit(LEFT_GRIP, down)
            }
            "KEY_RIGHTCTRL" -> {
                rightSqueeze = false
                emit(RIGHT_GRIP, false)
            }
            "KEY_LEFTCTRL  " -> {
                leftSqueeze = false
                emit(LEFT_GRIP, down)
            }

            "BTN_TRIGGER_HAPPY1" -> {
                rightTrigger = down
                emit(RIGHT_TRIGGER, down)
            }
            "BTN_TRIGGER_HAPPY2" -> {
                leftTrigger = down
                emit(LEFT_TRIGGER, down)
            }

            "BTN_THUMBR" -> {
                rightThumbstickClick = down
            }
            "BTN_THUMBL" -> {
                leftThumbstickClick = down
            }
        }
    }

    private fun applyAxis(device: String, code: String, raw: Int) {
        val j2 = raw.toLong() and 0xFFFFFFFFL
        if (!rangeLearned) {
            axMax = when {
                j2 > 4096 -> 65535
                j2 > 255 -> 4095
                else -> 255
            }
            rangeLearned = true
        }
        if (j2 > axMax) axMax = j2

        val right = isRightDeviceOrInfer(device) ?: return
        val maxF = axMax.toFloat().coerceAtLeast(1f)

        when (code) {
            "ABS_X" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                leftThumbstickX = n
                emitSticks()
                lastEvent = "$device ABS_X ${"%.2f".format(n)}"
            }
            "ABS_Y" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                leftThumbstickY = n
                emitSticks()
                lastEvent = "$device ABS_Y ${"%.2f".format(n)}"
            }
            "ABS_RX" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                if (right) rightThumbstickX = n else leftThumbstickX = n
                emitSticks()
            }
            "ABS_RZ", "ABS_GAS" -> {
                val rawVal = raw.toLong() and 0xFFFFFFFFL
                val f = (rawVal / 1023f).coerceIn(0f, 1f)

                rightTriggerValue = f

                val pressed = f > 0.10f

                if (pressed != rightTrigger) {
                    rightTrigger = pressed
                    emit(RIGHT_TRIGGER, pressed)
                }

                lastEvent = "$device $code ${"%.2f".format(f)}"
            }

            "ABS_Z", "ABS_BRAKE" -> {
                val rawVal = raw.toLong() and 0xFFFFFFFFL
                val f = (rawVal / 1023f).coerceIn(0f, 1f)

                leftTriggerValue = f

                val pressed = f > 0.10f

                if (pressed != leftTrigger) {
                    leftTrigger = pressed
                    emit(LEFT_TRIGGER, pressed)
                }

                lastEvent = "$device $code ${"%.2f".format(f)}"
            }
            "ABS_RY" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                if (right) rightThumbstickY = n else leftThumbstickY = n
                emitSticks()
            }
            "ABS_HAT0X" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                leftThumbstickX = n
                emitSticks()
            }
            "ABS_HAT0Y" -> {
                sticksSeen = true
                val n = dz(normStick(raw))
                leftThumbstickY = n
                emitSticks()
            }
        }
    }

    private fun edge(v: Float, was: Boolean, apply: (Boolean) -> Unit) {
        val now = if (was) v > TRIG_OFF else v > TRIG_ON
        if (now != was) apply(now)
    }

    private fun parseAbsValue(value: String): Int? {
        val v = value.trim()
        return runCatching {
            if (v.startsWith("0x", true)) v.substring(2).toLong(16).toInt()
            else v.toInt()
        }.getOrNull()
    }

    private fun normStick(raw: Int): Float =
        ((raw - STICK_MID) / STICK_MID).coerceIn(-1f, 1f)

    private fun dz(v: Float): Float = if (kotlin.math.abs(v) < DEADZONE) 0f else v

    private fun emit(name: String, down: Boolean) {
        for (l in listeners) runCatching { l.onButton(name, down) }
    }

    private fun emitSticks() {
        for (l in listeners) {
            runCatching {
                l.onStick(leftThumbstickX, leftThumbstickY, rightThumbstickX, rightThumbstickY)
            }
        }
    }
}

object AdbGamepadKeyEvents {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val EV_KEY = 1
    private const val EV_SYN = 0

    private const val BTN_SOUTH = 0x130
    private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133
    private const val BTN_WEST = 0x134
    private const val BTN_TL2 = 0x138
    private const val BTN_TR2 = 0x139
    private const val BTN_START = 0x13b
    private const val BTN_THUMBL = 0x13d
    private const val BTN_THUMBR = 0x13e
    private const val KEY_FORWARD = 0x159
    private const val BTN_TRIGGER_HAPPY1 = 0x2c0
    private const val BTN_TRIGGER_HAPPY2 = 0x2c1

    class GamepadButton internal constructor(
        private val node: () -> String,
        private val code: Int
    ) {
        var hold: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                sendHold(node(), code, value)
            }

        var click: Boolean = false
            set(value) {
                if (!value) return
                field = false
                sendClick(node(), code)
            }
    }

    val A = GamepadButton({ AdbButtonInput.rightDeviceNode }, BTN_SOUTH)
    val B = GamepadButton({ AdbButtonInput.rightDeviceNode }, BTN_EAST)
    val X = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_WEST)
    val Y = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_NORTH)
    val RightTrigger = GamepadButton({ AdbButtonInput.rightDeviceNode }, BTN_TRIGGER_HAPPY1)
    val LeftTrigger = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_TRIGGER_HAPPY2)
    val RightGrip = GamepadButton({ AdbButtonInput.rightDeviceNode }, BTN_TR2)
    val LeftGrip = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_TL2)
    val Menu = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_START)
    val Oculus = GamepadButton({ AdbButtonInput.leftDeviceNode }, KEY_FORWARD)
    val RightThumbClick = GamepadButton({ AdbButtonInput.rightDeviceNode }, BTN_THUMBR)
    val LeftThumbClick = GamepadButton({ AdbButtonInput.leftDeviceNode }, BTN_THUMBL)

    fun releaseAll() {
        listOf(
            A, B, X, Y, RightTrigger, LeftTrigger,
            RightGrip, LeftGrip, Menu, Oculus, RightThumbClick, LeftThumbClick
        ).forEach { if (it.hold) it.hold = false }
    }

    private fun devicePath(node: String) = "/dev/input/$node"

    private suspend fun sendRaw(node: String, type: Int, code: Int, value: Int) {
        runCatching {
            val manager = AppAdbConnectionManager.getInstance(AppContext.app)
            val stream = withContext(Dispatchers.IO) {
                manager.openStream("shell:sendevent ${devicePath(node)} $type $code $value")
            }
            runCatching { stream.close() }
        }
    }

    private suspend fun pressDown(node: String, code: Int) {
        sendRaw(node, EV_KEY, code, 1)
        sendRaw(node, EV_SYN, 0, 0)
    }

    private suspend fun pressUp(node: String, code: Int) {
        sendRaw(node, EV_KEY, code, 0)
        sendRaw(node, EV_SYN, 0, 0)
    }

    private fun sendHold(node: String, code: Int, down: Boolean) {
        scope.launch {
            if (down) pressDown(node, code) else pressUp(node, code)
        }
    }

    private fun sendClick(node: String, code: Int) {
        scope.launch {
            pressDown(node, code)
            delay(40)
            pressUp(node, code)
        }
    }
}

object InputMods {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile var holdBothGrips = false
    @Volatile var holdBothTriggers = false
    @Volatile var holdA = false
    @Volatile var holdX = false
    @Volatile var holdAX = false
    @Volatile var holdB = false
    @Volatile var holdY = false
    @Volatile var fingerSpaz = false
    @Volatile var mashFace = false
    @Volatile var blockKeyEvents = false
    @Volatile var gripSpaz = false

    private const val REASSERT_MS = 80L
    private const val SPAZ_MS = 70L

    fun anyActive(): Boolean =
        holdBothGrips || holdBothTriggers || holdA || holdX || holdAX ||
                holdB || holdY || fingerSpaz || mashFace || blockKeyEvents || gripSpaz

    fun ensureRunning() {
        if (job?.isActive == true) return
        AdbButtonInput.acquire()
        job = scope.launch {
            var tick = 0
            while (isActive && anyActive()) {
                try {
                    tick++
                    if (blockKeyEvents) {
                        forceReleaseAll()
                    } else {
                        if (holdBothGrips) {
                            AdbGamepadKeyEvents.LeftGrip.hold = true
                            AdbGamepadKeyEvents.RightGrip.hold = true
                        }
                        if (holdBothTriggers) {
                            AdbGamepadKeyEvents.LeftTrigger.hold = true
                            AdbGamepadKeyEvents.RightTrigger.hold = true
                        }
                        if (holdA || holdAX) AdbGamepadKeyEvents.A.hold = true
                        if (holdX || holdAX) AdbGamepadKeyEvents.X.hold = true
                        if (holdB) AdbGamepadKeyEvents.B.hold = true
                        if (holdY) AdbGamepadKeyEvents.Y.hold = true

                        if (fingerSpaz) {
                            val on = (tick % 2 == 0)
                            AdbGamepadKeyEvents.LeftGrip.hold = on
                            AdbGamepadKeyEvents.RightGrip.hold = !on
                            AdbGamepadKeyEvents.LeftTrigger.hold = !on
                            AdbGamepadKeyEvents.RightTrigger.hold = on
                        }
                        if (gripSpaz) {
                            val on = (tick % 2 == 0)
                            AdbGamepadKeyEvents.LeftGrip.hold = on
                            AdbGamepadKeyEvents.RightGrip.hold = !on
                        }
                        if (mashFace) {
                            when (tick % 4) {
                                0 -> AdbGamepadKeyEvents.A.click = true
                                1 -> AdbGamepadKeyEvents.B.click = true
                                2 -> AdbGamepadKeyEvents.X.click = true
                                3 -> AdbGamepadKeyEvents.Y.click = true
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(if (fingerSpaz || gripSpaz || mashFace) SPAZ_MS else REASSERT_MS)
            }
            forceReleaseAll()
            job = null
        }
    }

    private fun forceReleaseAll() {
        AdbGamepadKeyEvents.releaseAll()
        listOf(
            AdbGamepadKeyEvents.A, AdbGamepadKeyEvents.B,
            AdbGamepadKeyEvents.X, AdbGamepadKeyEvents.Y,
            AdbGamepadKeyEvents.LeftTrigger, AdbGamepadKeyEvents.RightTrigger,
            AdbGamepadKeyEvents.LeftGrip, AdbGamepadKeyEvents.RightGrip
        ).forEach { b ->
            if (b.hold) b.hold = false
        }
    }

    fun stopAll() {
        holdBothGrips = false
        holdBothTriggers = false
        holdA = false
        holdX = false
        holdAX = false
        holdB = false
        holdY = false
        fingerSpaz = false
        mashFace = false
        blockKeyEvents = false
        gripSpaz = false
        job?.cancel()
        job = null
        forceReleaseAll()
    }

    fun setHoldGrips(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdBothGrips = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold grips ON") }
        else { AdbGamepadKeyEvents.LeftGrip.hold = false; AdbGamepadKeyEvents.RightGrip.hold = false; ctx?.log("Hold grips OFF") }
        if (!anyActive()) stopAll()
    }

    fun setHoldTriggers(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdBothTriggers = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold triggers ON") }
        else { AdbGamepadKeyEvents.LeftTrigger.hold = false; AdbGamepadKeyEvents.RightTrigger.hold = false; ctx?.log("Hold triggers OFF") }
        if (!anyActive()) stopAll()
    }

    fun setHoldA(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdA = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold A ON") }
        else { AdbGamepadKeyEvents.A.hold = false; ctx?.log("Hold A OFF") }
        if (!anyActive()) stopAll()
    }

    fun setHoldX(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdX = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold X ON") }
        else { AdbGamepadKeyEvents.X.hold = false; ctx?.log("Hold X OFF") }
        if (!anyActive()) stopAll()
    }

    fun setHoldAX(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdAX = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold A+X ON") }
        else {
            if (!holdA) AdbGamepadKeyEvents.A.hold = false
            if (!holdX) AdbGamepadKeyEvents.X.hold = false
            ctx?.log("Hold A+X OFF")
        }
        if (!anyActive()) stopAll()
    }

    fun setHoldB(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdB = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold B ON") }
        else { AdbGamepadKeyEvents.B.hold = false; ctx?.log("Hold B OFF") }
        if (!anyActive()) stopAll()
    }

    fun setHoldY(on: Boolean, ctx: Utils.ActionContext? = null) {
        holdY = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Hold Y ON") }
        else { AdbGamepadKeyEvents.Y.hold = false; ctx?.log("Hold Y OFF") }
        if (!anyActive()) stopAll()
    }

    fun setFingerSpaz(on: Boolean, ctx: Utils.ActionContext? = null) {
        fingerSpaz = on
        if (on) {
            blockKeyEvents = false
            gripSpaz = false
            ensureRunning()
            ctx?.log("Finger spaz ON (grips+triggers alternate)")
        } else {
            AdbGamepadKeyEvents.LeftGrip.hold = false
            AdbGamepadKeyEvents.RightGrip.hold = false
            AdbGamepadKeyEvents.LeftTrigger.hold = false
            AdbGamepadKeyEvents.RightTrigger.hold = false
            ctx?.log("Finger spaz OFF")
        }
        if (!anyActive()) stopAll()
    }

    fun setGripSpaz(on: Boolean, ctx: Utils.ActionContext? = null) {
        gripSpaz = on
        if (on) {
            blockKeyEvents = false
            fingerSpaz = false
            ensureRunning()
            ctx?.log("Grip spaz ON")
        } else {
            AdbGamepadKeyEvents.LeftGrip.hold = false
            AdbGamepadKeyEvents.RightGrip.hold = false
            ctx?.log("Grip spaz OFF")
        }
        if (!anyActive()) stopAll()
    }

    fun setMashFace(on: Boolean, ctx: Utils.ActionContext? = null) {
        mashFace = on
        if (on) { blockKeyEvents = false; ensureRunning(); ctx?.log("Mash face buttons ON") }
        else ctx?.log("Mash face buttons OFF")
        if (!anyActive()) stopAll()
    }

    fun setBlockKeyEvents(on: Boolean, ctx: Utils.ActionContext? = null) {
        blockKeyEvents = on
        if (on) {
            holdBothGrips = false
            holdBothTriggers = false
            holdA = false
            holdX = false
            holdAX = false
            holdB = false
            holdY = false
            fingerSpaz = false
            gripSpaz = false
            mashFace = false
            ensureRunning()
            ctx?.log("Block key events ON (force-release loop)")
            ctx?.toast("Input block ON")
        } else {
            ctx?.log("Block key events OFF")
            ctx?.toast("Input block OFF")
        }
        if (!anyActive()) stopAll()
    }
}

object PanelGamepadInput {


    @Volatile var active = false
        private set

    private const val DEADZONE = 0.15f

    private var rightDeviceId: Int? = null
    private var leftDeviceId: Int? = null

    fun onGenericMotion(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false

        active = true
        assignHand(device)
        val right = isRightDevice(device.id) ?: return false

        val lx = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = deadzone(event.getAxisValue(MotionEvent.AXIS_Y))

        val rx = deadzone(
            event.getAxisValue(MotionEvent.AXIS_Z).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_RX)
        )
        val ry = deadzone(
            event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_RY)
        )

        if (right) {
            AdbButtonInput.rightThumbstickX = rx
            AdbButtonInput.rightThumbstickY = ry
        } else {
            AdbButtonInput.leftThumbstickX = lx
            AdbButtonInput.leftThumbstickY = ly
        }

        AdbButtonInput.notePanelInputSeen(face = false, sticks = true)
        return true
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = onKey(keyCode, event, true)
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = onKey(keyCode, event, false)

    private fun onKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val device = event.device ?: return false
        val src = device.sources
        if (src and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            src and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) return false

        active = true
        assignHand(device)
        val right = isRightDevice(device.id) ?: return false
        var handled = true

        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> if (right) AdbButtonInput.rightA = down else handled = false
            KeyEvent.KEYCODE_BUTTON_B -> if (right) AdbButtonInput.rightB = down else handled = false
            KeyEvent.KEYCODE_BUTTON_X -> if (!right) AdbButtonInput.leftX = down else handled = false
            KeyEvent.KEYCODE_BUTTON_Y -> if (!right) AdbButtonInput.leftY = down else handled = false
            KeyEvent.KEYCODE_BUTTON_MODE -> if (!right) AdbButtonInput.Menu = down else handled = false
            KeyEvent.KEYCODE_BUTTON_THUMBR ->
                if (right) AdbButtonInput.rightThumbstickClick = down else AdbButtonInput.leftThumbstickClick = down
            KeyEvent.KEYCODE_BUTTON_THUMBL ->
                if (!right) AdbButtonInput.leftThumbstickClick = down else AdbButtonInput.rightThumbstickClick = down
            else -> handled = false
        }

        if (handled) {
            AdbButtonInput.notePanelInputSeen(face = true, sticks = false)
            Log.d("PanelGamepadInput", "${device.name} key=${KeyEvent.keyCodeToString(keyCode)} down=$down right=$right")
        }
        return handled
    }

    private fun assignHand(device: InputDevice) {
        val id = device.id
        if (id == rightDeviceId || id == leftDeviceId) return

        val name = device.name.lowercase()
        when {
            "right" in name -> rightDeviceId = id
            "left" in name -> leftDeviceId = id
            rightDeviceId == null -> rightDeviceId = id
            leftDeviceId == null -> leftDeviceId = id
        }
    }

    private fun isRightDevice(id: Int): Boolean? = when (id) {
        rightDeviceId -> true
        leftDeviceId -> false
        else -> null
    }

    private fun deadzone(v: Float): Float = if (kotlin.math.abs(v) < DEADZONE) 0f else v

    fun logDevices() {
        for (devId in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(devId) ?: continue
            if (d.sources and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) continue
            Log.d("PanelGamepadInput", "Device ${d.id}: ${d.name}  sources=${d.sources}")
            for (range in d.motionRanges) {
                Log.d("PanelGamepadInput", "  axis=${MotionEvent.axisToString(range.axis)} min=${range.min} max=${range.max}")
            }
        }
    }

    fun reset() {
        active = false
        rightDeviceId = null
        leftDeviceId = null
    }
}

object ControllerInput {
    @Volatile
    var leftX: Boolean = false

    @Volatile
    var leftY: Boolean = false

    @Volatile
    var leftMenu: Boolean = false

    @Volatile
    var leftHome: Boolean = false

    @Volatile
    var leftThumbstickClick: Boolean = false

    @Volatile
    var leftTrigger: Boolean = false

    @Volatile
    var leftSqueeze: Boolean = false

    @Volatile
    var leftTriggerValue: Float = 0f

    @Volatile
    var leftSqueezeValue: Float = 0f

    @Volatile
    var leftThumbstickX: Float = 0f

    @Volatile
    var leftThumbstickY: Float = 0f

    @Volatile
    var rightA: Boolean = false

    @Volatile
    var rightB: Boolean = false

    @Volatile
    var rightThumbstickClick: Boolean = false

    @Volatile
    var rightTrigger: Boolean = false

    @Volatile
    var rightSqueeze: Boolean = false

    @Volatile
    var rightTriggerValue: Float = 0f

    @Volatile
    var rightSqueezeValue: Float = 0f

    @Volatile
    var rightThumbstickX: Float = 0f

    @Volatile
    var rightThumbstickY: Float = 0f

    @Volatile
    var leftPosX: Float = 0f

    @Volatile
    var leftPosY: Float = 0f

    @Volatile
    var leftPosZ: Float = 0f

    @Volatile
    var leftRotX: Float = 0f

    @Volatile
    var leftRotY: Float = 0f

    @Volatile
    var leftRotZ: Float = 0f

    @Volatile
    var leftRotW: Float = 0f

    @Volatile
    var rightPosX: Float = 0f

    @Volatile
    var rightPosY: Float = 0f

    @Volatile
    var rightPosZ: Float = 0f

    @Volatile
    var rightRotX: Float = 0f

    @Volatile
    var rightRotY: Float = 0f

    @Volatile
    var rightRotZ: Float = 0f

    @Volatile
    var rightRotW: Float = 0f

    @Volatile
    var headPosX: Float = 0f

    @Volatile
    var headPosY: Float = 0f

    @Volatile
    var headPosZ: Float = 0f

    @Volatile
    var headRotX: Float = 0f

    @Volatile
    var headRotY: Float = 0f

    @Volatile
    var headRotZ: Float = 0f

    @Volatile
    var headRotW: Float = 0f

    @Volatile
    var tracking: Boolean = false
}

class OrientationTracker(context: Context) : SensorEventListener {

    companion object {
        private const val DRAG = 0.9f
        private const val MAX_V = 4.0f
        private const val STILL_ACCEL = 0.06f

        @Volatile
        var instance: OrientationTracker? = null

        fun getInstance(context: Context = AppContext.app): OrientationTracker {
            return instance ?: synchronized(this) {
                instance ?: OrientationTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRot: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotVec: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val geoRot: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val linear: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var yaw: Float = 0f
        private set
    @Volatile var pitch: Float = 0f
        private set
    @Volatile var roll: Float = 0f
        private set

    @Volatile private var px: Float = 0f
    @Volatile private var py: Float = 0f
    @Volatile private var pz: Float = 0f

    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var lastNs = 0L
    private var stillFrames = 0

    @Volatile
    var activeSource: String = "none"
        private set

    @Volatile
    var isRunning: Boolean = false

    fun sensorReport(): String = buildString {
        fun mark(s: Sensor?, label: String) {
            val name = s?.name?.let { if (it.length > 40) it.take(40) + "..." else it }
            append(if (s != null) "✓ $label ($name)" else "✗ $label")
            append('\n')
        }
        mark(gameRot, "GAME_ROTATION_VECTOR")
        mark(rotVec, "ROTATION_VECTOR")
        mark(geoRot, "GEOMAGNETIC_ROTATION_VECTOR")
        mark(linear, "LINEAR_ACCELERATION")
        mark(accel, "ACCELEROMETER")
        mark(mag, "MAGNETIC_FIELD")
        mark(gyro, "GYROSCOPE")
        mark(imuSensor ?: findSensorByNameContains("Syncboss IMU"), "SYNCBOSS_IMU (raw accel+gyro, fused ourselves)")
        append("activeSource=$activeSource  isRunning=$isRunning\n")
        if (mode == Mode.RAW_IMU_FUSION) {
            append(
                "IMU calib: gyroScale=%.3f accelScale=%.3f gyroMap=%s gyroSign=%s accelMap=%s accelSign=%s calibLogging=%s\n".format(
                    Locale.US,
                    imuGyroScale, imuAccelScale,
                    imuGyroAxisMap.contentToString(), imuGyroAxisSign.contentToString(),
                    imuAccelAxisMap.contentToString(), imuAccelAxisSign.contentToString(),
                    imuCalibrationLogging
                )
            )
            append(
                "IMU gyro bias (learned): x=%.5f y=%.5f z=%.5f rad/s\n".format(
                    Locale.US, gyroBiasX, gyroBiasY, gyroBiasZ
                )
            )
        }
    }

    private val matrixLock = Any()
    private var currentMatrix: FloatArray? = null
    private var referenceMatrix: FloatArray? = null
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMag = false
    private var mode: Mode = Mode.NONE

    private enum class Mode {
        NONE,
        ROTATION_VECTOR,
        ACCEL_MAG,
        ACCEL_ONLY,
        RAW_IMU_FUSION
    }

    private var imuSensor: Sensor? = null
    private var imuLastNs: Long = 0L
    private var qw = 1f
    private var qx = 0f
    private var qy = 0f
    private var qz = 0f
    private var imuBiasX = 0f
    private var imuBiasY = 0f
    private var imuBiasZ = 0f
    private val MAHONY_KP = 0.6f
    private val MAHONY_KI = 0.02f

    @Volatile var imuGyroScale: Float = 1f
    @Volatile var imuAccelScale: Float = 1f
    @Volatile var imuGyroAxisMap: IntArray = intArrayOf(0, 1, 2)
    @Volatile var imuGyroAxisSign: FloatArray = floatArrayOf(1f, 1f, 1f)
    @Volatile var imuAccelAxisMap: IntArray = intArrayOf(0, 1, 2)
    @Volatile var imuAccelAxisSign: FloatArray = floatArrayOf(1f, 1f, 1f)

    @Volatile var imuCalibrationLogging: Boolean = false
    private var lastCalibLogNs = 0L

    private var gyroBiasX = 0f
    private var gyroBiasY = 0f
    private var gyroBiasZ = 0f
    private val GYRO_BIAS_LEARN_RATE = 0.01f
    private val STILL_GYRO_THRESHOLD = 0.015f
    private val MAX_PLAUSIBLE_GYRO_RATE = 15f

    private fun resetGyroBias() {
        gyroBiasX = 0f; gyroBiasY = 0f; gyroBiasZ = 0f
    }

    private var lastPropPollNs = 0L
    private val PROP_POLL_INTERVAL_NS = 1_000_000_000L

    @SuppressLint("PrivateApi")
    private fun sysPropGet(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        m.invoke(null, key) as? String ?: ""
    }.getOrDefault("")

    private fun parseIntTriple(raw: String): IntArray? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 3) return null
        return runCatching { IntArray(3) { i -> parts[i].toInt() } }.getOrNull()
    }

    private fun parseFloatTriple(raw: String): FloatArray? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 3) return null
        return runCatching { FloatArray(3) { i -> parts[i].toFloat() } }.getOrNull()
    }

    private fun refreshImuCalibFromProps() {
        sysPropGet("debug.mod.imu.calibLogging").trim().lowercase().let {
            if (it == "true" || it == "1") imuCalibrationLogging = true
            else if (it == "false" || it == "0") imuCalibrationLogging = false
        }
        sysPropGet("debug.mod.imu.gyroScale").toFloatOrNull()?.let { imuGyroScale = it }
        sysPropGet("debug.mod.imu.accelScale").toFloatOrNull()?.let { imuAccelScale = it }
        parseIntTriple(sysPropGet("debug.mod.imu.gyroMap"))?.let {
            if (it.all { i -> i in 0..2 }) imuGyroAxisMap = it
        }
        parseFloatTriple(sysPropGet("debug.mod.imu.gyroSign"))?.let { imuGyroAxisSign = it }
        parseIntTriple(sysPropGet("debug.mod.imu.accelMap"))?.let {
            if (it.all { i -> i in 0..2 }) imuAccelAxisMap = it
        }
        parseFloatTriple(sysPropGet("debug.mod.imu.accelSign"))?.let { imuAccelAxisSign = it }
    }

    private fun findSensorByNameContains(needle: String): Sensor? =
        sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.name.contains(needle, ignoreCase = true) }

    private fun resetImuFusion() {
        qw = 1f; qx = 0f; qy = 0f; qz = 0f
        imuBiasX = 0f; imuBiasY = 0f; imuBiasZ = 0f
        imuLastNs = 0L
        resetGyroBias()
    }

    private fun mahonyUpdate(ax0: Float, ay0: Float, az0: Float, gx0: Float, gy0: Float, gz0: Float, dt: Float) {
        var gx = gx0; var gy = gy0; var gz = gz0
        val aNorm = sqrt(ax0 * ax0 + ay0 * ay0 + az0 * az0)
        if (aNorm > 1e-3f) {
            val ax = ax0 / aNorm; val ay = ay0 / aNorm; val az = az0 / aNorm

            val vx = 2f * (qx * qz - qw * qy)
            val vy = 2f * (qw * qx + qy * qz)
            val vz = qw * qw - qx * qx - qy * qy + qz * qz

            val ex = ay * vz - az * vy
            val ey = az * vx - ax * vz
            val ez = ax * vy - ay * vx

            imuBiasX += ex * MAHONY_KI * dt
            imuBiasY += ey * MAHONY_KI * dt
            imuBiasZ += ez * MAHONY_KI * dt

            gx += MAHONY_KP * ex + imuBiasX
            gy += MAHONY_KP * ey + imuBiasY
            gz += MAHONY_KP * ez + imuBiasZ
        }

        val dq0 = -qx * gx - qy * gy - qz * gz
        val dq1 = qw * gx + qy * gz - qz * gy
        val dq2 = qw * gy - qx * gz + qz * gx
        val dq3 = qw * gz + qx * gy - qy * gx

        qw += 0.5f * dq0 * dt
        qx += 0.5f * dq1 * dt
        qy += 0.5f * dq2 * dt
        qz += 0.5f * dq3 * dt

        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (n > 1e-6f) {
            qw /= n; qx /= n; qy /= n; qz /= n
        }
    }

    private fun imuQuaternionToMatrix(): FloatArray {
        val m = FloatArray(9)
        val ww = qw * qw; val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        m[0] = ww + xx - yy - zz
        m[1] = 2f * (qx * qy - qw * qz)
        m[2] = 2f * (qx * qz + qw * qy)
        m[3] = 2f * (qx * qy + qw * qz)
        m[4] = ww - xx + yy - zz
        m[5] = 2f * (qy * qz - qw * qx)
        m[6] = 2f * (qx * qz - qw * qy)
        m[7] = 2f * (qy * qz + qw * qx)
        m[8] = ww - xx - yy + zz
        return m
    }

    @Volatile private var lastFailedStartMs: Long = 0L
    private val START_RETRY_COOLDOWN_MS = 5000L

    fun start(): Boolean {
        if (isRunning) return true
        val now = System.currentTimeMillis()
        if (lastFailedStartMs != 0L && now - lastFailedStartMs < START_RETRY_COOLDOWN_MS) return false
        stopInternal()

        var registered = false
        val rotation = gameRot ?: rotVec
        if (rotation != null) {
            if (sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)) {
                mode = Mode.ROTATION_VECTOR
                activeSource = when (rotation.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> "GAME_ROTATION_VECTOR"
                    Sensor.TYPE_ROTATION_VECTOR -> "ROTATION_VECTOR"
                    else -> "ROTATION_VECTOR(other)"
                }
                registered = true
            }
        }
        if (linear != null) {
            if (sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_UI)) {
                registered = true
                if (activeSource == "none") activeSource = "LINEAR_ACCELERATION"
            }
        }
        if (!registered) {
            if (accel != null && mag != null) {
                val aOk = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
                val mOk = sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
                if (aOk && mOk) {
                    mode = Mode.ACCEL_MAG
                    activeSource = "ACCELEROMETER+MAGNETOMETER"
                    registered = true
                } else {
                    sensorManager.unregisterListener(this)
                }
            }
            if (!registered && accel != null) {
                if (sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)) {
                    mode = Mode.ACCEL_ONLY
                    activeSource = "ACCELEROMETER_ONLY (no yaw)"
                    registered = true
                }
            }
            if (!registered) {
                val imu = findSensorByNameContains("Syncboss IMU")
                if (imu != null && sensorManager.registerListener(this, imu, SensorManager.SENSOR_DELAY_GAME)) {
                    imuSensor = imu
                    resetImuFusion()
                    mode = Mode.RAW_IMU_FUSION
                    activeSource = "SYNCBOSS_IMU_FUSION (accel+gyro, no mag — yaw drifts)"
                    registered = true
                }
            }
        }

        isRunning = registered
        if (!registered) {
            mode = Mode.NONE
            activeSource = "none — no usable sensors"
            lastFailedStartMs = System.currentTimeMillis()
        } else {
            lastFailedStartMs = 0L
        }
        return registered
    }

    fun stop() {
        stopInternal()
        isRunning = false
        activeSource = "none"
        mode = Mode.NONE
        lastFailedStartMs = 0L
        imuSensor = null
        resetImuFusion()
        synchronized(matrixLock) {
            currentMatrix = null
            referenceMatrix = null
        }
        hasGravity = false
        hasMag = false
    }

    private fun stopInternal() {
        runCatching { sensorManager.unregisterListener(this) }
    }

    fun getX(): Float = px
    fun getY(): Float = py
    fun getZ(): Float = pz

    fun reset() {
        pz = 0f
        py = 0f
        px = 0f
        vz = 0f
        vy = 0f
        vx = 0f
        lastNs = 0L
        stillFrames = 0
    }

    fun headString(): String =
        String.format(Locale.US, "y %.0f  p %.0f  r %.0f", yaw, pitch, roll)

    fun recenter() {
        synchronized(matrixLock) {
            referenceMatrix = currentMatrix?.clone()
        }
    }

    fun getForward(out: FloatArray): Boolean {
        synchronized(matrixLock) {
            val cur = currentMatrix ?: return false
            val f = cur.clone()
            if (referenceMatrix == null) {
                referenceMatrix = cur.clone()
            }
            val r = referenceMatrix!!.clone()
            val f8 = -((r[0] * f[2]) + (r[3] * f[5]) + (r[6] * f[8]))
            val f9 = -((r[1] * f[2]) + (r[4] * f[5]) + (r[7] * f[8]))
            val f10 = -((r[2] * f[2]) + (r[5] * f[5]) + (r[8] * f[8]))
            val len = sqrt((f8 * f8 + f9 * f9 + f10 * f10).toDouble()).toFloat()
            if (len < 1e-4f) return false
            out[0] = f8 / len
            out[1] = f9 / len
            out[2] = f10 / len
            return true
        }
    }

    fun getForwardOrNull(): FloatArray? {
        val out = FloatArray(3)
        return if (getForward(out)) out else null
    }

    fun getOrientationRadians(out: FloatArray = FloatArray(3)): FloatArray? {
        synchronized(matrixLock) {
            val cur = currentMatrix ?: return null
            SensorManager.getOrientation(cur, out)
            return out
        }
    }

    fun getOrientationDegrees(): FloatArray? {
        val rad = getOrientationRadians() ?: return null
        return floatArrayOf(
            Math.toDegrees(rad[0].toDouble()).toFloat(),
            Math.toDegrees(rad[1].toDouble()).toFloat(),
            Math.toDegrees(rad[2].toDouble()).toFloat()
        )
    }

    data class HeadRotation(val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float)

    fun getHeadRotation(): HeadRotation? {
        val d = getOrientationDegrees() ?: return null
        return HeadRotation(yawDeg = d[0], pitchDeg = d[1], rollDeg = d[2])
    }

    fun getQuaternion(out: FloatArray = FloatArray(4)): FloatArray? {
        synchronized(matrixLock) {
            val m = currentMatrix ?: return null
            val m00 = m[0]; val m01 = m[1]; val m02 = m[2]
            val m10 = m[3]; val m11 = m[4]; val m12 = m[5]
            val m20 = m[6]; val m21 = m[7]; val m22 = m[8]
            val trace = m00 + m11 + m22
            if (trace > 0f) {
                val s = sqrt(trace + 1.0f) * 2f
                out[3] = 0.25f * s
                out[0] = (m21 - m12) / s
                out[1] = (m02 - m20) / s
                out[2] = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0f + m00 - m11 - m22) * 2f
                out[3] = (m21 - m12) / s
                out[0] = 0.25f * s
                out[1] = (m01 + m10) / s
                out[2] = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1.0f + m11 - m00 - m22) * 2f
                out[3] = (m02 - m20) / s
                out[0] = (m01 + m10) / s
                out[1] = 0.25f * s
                out[2] = (m12 + m21) / s
            } else {
                val s = sqrt(1.0f + m22 - m00 - m11) * 2f
                out[3] = (m10 - m01) / s
                out[0] = (m02 + m20) / s
                out[1] = (m12 + m21) / s
                out[2] = 0.25f * s
            }
            return out
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type

        if (mode == Mode.RAW_IMU_FUSION && event.sensor === imuSensor) {
            if (event.values.size < 7) return

            val pollNow = event.timestamp
            if (pollNow - lastPropPollNs > PROP_POLL_INTERVAL_NS) {
                lastPropPollNs = pollNow
                runCatching { refreshImuCalibFromProps() }
            }

            val rawAccel = floatArrayOf(event.values[1], event.values[2], event.values[3])
            val rawGyro = floatArrayOf(event.values[4], event.values[5], event.values[6])

            if (imuCalibrationLogging) {
                val nowNs = event.timestamp
                if (nowNs - lastCalibLogNs > 200_000_000L) {
                    lastCalibLogNs = nowNs
                    Log.d(
                        "SyncbossIMU",
                        "raw accel=(%.4f, %.4f, %.4f)  raw gyro=(%.4f, %.4f, %.4f)".format(
                            Locale.US,
                            rawAccel[0], rawAccel[1], rawAccel[2],
                            rawGyro[0], rawGyro[1], rawGyro[2]
                        )
                    )
                }
            }

            val ax = rawAccel[imuAccelAxisMap[0]] * imuAccelAxisSign[0] * imuAccelScale
            val ay = rawAccel[imuAccelAxisMap[1]] * imuAccelAxisSign[1] * imuAccelScale
            val az = rawAccel[imuAccelAxisMap[2]] * imuAccelAxisSign[2] * imuAccelScale
            val gx = rawGyro[imuGyroAxisMap[0]] * imuGyroAxisSign[0] * imuGyroScale
            val gy = rawGyro[imuGyroAxisMap[1]] * imuGyroAxisSign[1] * imuGyroScale
            val gz = rawGyro[imuGyroAxisMap[2]] * imuGyroAxisSign[2] * imuGyroScale

            val rawGyroMagSq = gx * gx + gy * gy + gz * gz
            if (rawGyroMagSq > MAX_PLAUSIBLE_GYRO_RATE * MAX_PLAUSIBLE_GYRO_RATE) {
                imuLastNs = event.timestamp
                if (imuCalibrationLogging) {
                    Log.d("SyncbossIMU", "dropped implausible sample: gx=%.3f gy=%.3f gz=%.3f (mag=%.3f rad/s)".format(
                        Locale.US, gx, gy, gz, sqrt(rawGyroMagSq.toDouble()).toFloat()
                    ))
                }
                return
            }
            val gMagSq = rawGyroMagSq
            if (gMagSq < STILL_GYRO_THRESHOLD * STILL_GYRO_THRESHOLD) {
                gyroBiasX += (gx - gyroBiasX) * GYRO_BIAS_LEARN_RATE
                gyroBiasY += (gy - gyroBiasY) * GYRO_BIAS_LEARN_RATE
                gyroBiasZ += (gz - gyroBiasZ) * GYRO_BIAS_LEARN_RATE
            }
            val gxC = gx - gyroBiasX
            val gyC = gy - gyroBiasY
            val gzC = gz - gyroBiasZ

            val now = event.timestamp
            val prev = imuLastNs
            imuLastNs = now
            if (prev == 0L) return
            val dt = (now - prev) / 1.0e9f
            if (dt <= 0f || dt > 0.2f) return
            try {
                mahonyUpdate(ax, ay, az, gxC, gyC, gzC, dt)
                val mat = imuQuaternionToMatrix()
                SensorManager.getOrientation(mat, orientation)
                yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                publishMatrix(mat)
            } catch (_: Exception) {
            }
            return
        }
        if (type == Sensor.TYPE_ROTATION_VECTOR || type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            try {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientation)
                yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                publishMatrix(rotMatrix.clone())
            } catch (_: Exception) {
            }
            return
        }
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val j = event.timestamp
            val j2 = lastNs
            if (j2 == 0L) {
                lastNs = j
                return
            }
            val f = (j - j2) / 1.0E9f
            lastNs = j
            if (f <= 0f || f > 0.2f) return

            val f2 = event.values[0]
            val f3 = event.values[1]
            val f4 = event.values[2]
            val mag = sqrt((f2 * f2 + f3 * f3 + f4 * f4).toDouble()).toFloat()
            if (mag < STILL_ACCEL) {
                stillFrames++
                if (stillFrames > 6) {
                    vz = 0f
                    vy = 0f
                    vx = 0f
                }
            } else {
                stillFrames = 0
                vx += f2 * f
                vy += f3 * f
                vz += f4 * f
            }
            vx *= DRAG
            vy *= DRAG
            vz *= DRAG
            px += vx * f
            py += vy * f
            pz += vz * f
            return
        }

        when (mode) {
            Mode.ACCEL_MAG -> {
                when (type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravity[0] = event.values[0]
                        gravity[1] = event.values[1]
                        gravity[2] = event.values[2]
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        geomagnetic[0] = event.values[0]
                        geomagnetic[1] = event.values[1]
                        geomagnetic[2] = event.values[2]
                        hasMag = true
                    }
                }
                if (hasGravity && hasMag) {
                    val mat = FloatArray(9)
                    val incl = FloatArray(9)
                    if (SensorManager.getRotationMatrix(mat, incl, gravity, geomagnetic)) {
                        SensorManager.getOrientation(mat, orientation)
                        yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                        publishMatrix(mat)
                    }
                }
            }
            Mode.ACCEL_ONLY -> {
                if (type != Sensor.TYPE_ACCELEROMETER) return
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val norm = sqrt(ax * ax + ay * ay + az * az)
                if (norm < 1e-3f) return
                val gx = ax / norm
                val gy = ay / norm
                val gz = az / norm
                val mat = FloatArray(9)
                mat[2] = -gx
                mat[5] = -gy
                mat[8] = -gz
                var rx = 0f
                var ry = -gz
                var rz = gy
                var rLen = sqrt(rx * rx + ry * ry + rz * rz)
                if (rLen < 1e-3f) {
                    rx = gz; ry = 0f; rz = -gx
                    rLen = sqrt(rx * rx + ry * ry + rz * rz)
                }
                if (rLen < 1e-3f) return
                rx /= rLen; ry /= rLen; rz /= rLen
                mat[0] = rx; mat[3] = ry; mat[6] = rz
                mat[1] = mat[5] * rz - mat[8] * ry
                mat[4] = mat[8] * rx - mat[2] * rz
                mat[7] = mat[2] * ry - mat[5] * rx
                SensorManager.getOrientation(mat, orientation)
                yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                publishMatrix(mat)
            }
            else -> {}
        }
    }

    private fun publishMatrix(mat: FloatArray) {
        synchronized(matrixLock) {
            currentMatrix = mat
            if (referenceMatrix == null) {
                referenceMatrix = mat.clone()
            }
        }
    }

    private fun clampV(v: Float): Float =
        when {
            v > MAX_V -> MAX_V
            v < -MAX_V -> -MAX_V
            else -> v
        }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}