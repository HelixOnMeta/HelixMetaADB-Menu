package com.helix

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.concurrent.thread

// contact blaku64th on discord if you have any issues ^^
object MovementMods {@Volatile private var longArmsThread: Thread? = null
    @Volatile private var wallWalkThread: Thread? = null
    @Volatile private var grappleThread: Thread? = null

    fun makeCtx(): Utils.ActionContext {
        val app = AppContext.app
        val mgr = AppAdbConnectionManager.getInstance(app)

        return object : Utils.ActionContext {
            override fun run(command: String): String {
                return runCatching {
                    val stream = mgr.openStream("shell:$command")
                    val text = stream.openInputStream().bufferedReader().use { it.readText() }
                    stream.close()
                    text
                }.getOrDefault("")
            }

            override fun log(message: String) {
                android.util.Log.i("ModEngine", message)
            }

            override fun toast(message: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val longArmsOn: Boolean get() = LongArms.enabled

    fun setLongArms(on: Boolean) {
        if (on == LongArms.enabled) return
        if (!on) {
            LongArms.stop()
            longArmsThread = null
            return
        }
        val ctx = makeCtx()
        longArmsThread = thread(name = "LongArmsEngine", isDaemon = true) {
            try {
                LongArms.start(ctx)
            } catch (t: Throwable) {
                android.util.Log.e("ModEngine", "LongArms crashed", t)
            }
        }
    }

    fun toggleLongArms() = setLongArms(!longArmsOn)

    val wallWalkOn: Boolean get() = WallWalk.enabled

    fun setWallWalk(on: Boolean) {
        if (on == WallWalk.enabled) return
        if (!on) {
            WallWalk.stop()
            wallWalkThread = null
            return
        }
        val ctx = makeCtx()
        wallWalkThread = thread(name = "WallWalkEngine", isDaemon = true) {
            try {
                WallWalk.start(ctx)
            } catch (t: Throwable) {
                android.util.Log.e("ModEngine", "WallWalk crashed", t)
            }
        }
    }

    fun toggleWallWalk() = setWallWalk(!wallWalkOn)

    val grappleOn: Boolean get() = Grapple.isActive

    fun setGrapple(on: Boolean) {
        if (on == Grapple.isActive) return
        if (!on) {
            Grapple.stop()
            grappleThread = null
            return
        }
        val ctx = makeCtx()
        grappleThread = thread(name = "GrappleEngine", isDaemon = true) {
            try {
                Grapple.run(ctx)
            } catch (t: Throwable) {
                android.util.Log.e("ModEngine", "Grapple crashed", t)
            }
        }
    }

    fun toggleGrapple() = setGrapple(!grappleOn)

    fun stopAll() {
        setLongArms(false)
        setWallWalk(false)
        setGrapple(false)
    }

    @Volatile var fly = false
    @Volatile var velFly = false
    @Volatile var hover = false
    @Volatile var glide = false
    @Volatile var stickFly = false
    @Volatile var strafe = false
    @Volatile var autoFwd = false
    @Volatile var slowFall = false
    @Volatile var airBrake = false
    @Volatile var climbAssist = false
    @Volatile var swim = false
    @Volatile var zigzag = false
    @Volatile var orbit = false
    @Volatile var bunnyHop = false
    @Volatile var surf = false

    val anyArmed: Boolean
        get() = fly || velFly || hover || glide || stickFly || strafe || autoFwd ||
                slowFall || airBrake || climbAssist || swim || zigzag || orbit ||
                bunnyHop || surf

    var speedMul = 1f
    var accelMul = 1f
    var climbMul = 1f
    var smooth = 0.3f
    var dashDist = 20f
    var blinkDist = 6f

    var posX = 0f
    var posY = 0f
    var posZ = 0f
    var tgtX = 0f
    var tgtY = 0f
    var tgtZ = 0f
    var velX = 0f
    var velY = 0f
    var velZ = 0f
    var yaw = 0f
    var pitch = 0f

    private var phase = 0f
    private var swimPhase = 0f
    private var orbitCX = 0f
    private var orbitCZ = 0f
    private var orbitPhase = 0f
    private var orbitSet = false
    private var hasRecall = false
    private var recallX = 0f
    private var recallY = 0f
    private var recallZ = 0f

    private const val TICK_MS = 14L

    @Volatile private var loopRunning = false
    @Volatile private var active = false
    private var armedOnce = false
    private var lastMoveCmd = ""

    private val SPEED = floatArrayOf(1.5f, 3.5f, 6f, 10f, 16f, 26f)
    private val ACCEL = floatArrayOf(4f, 9f, 16f, 28f)
    private val CLIMB = floatArrayOf(0.5f, 1f, 2f, 3.5f)
    private val SMOOTH = floatArrayOf(1f, 0.55f, 0.3f, 0.16f)
    private val DASH = floatArrayOf(5f, 10f, 20f, 40f, 75f, 150f)
    private val BLINK = floatArrayOf(3f, 6f, 12f, 25f, 50f)

    fun refreshTuning(ctx: Utils.ActionContext) {
        fun prop(name: String, def: Float) =
            ctx.run("getprop debug.mod.$name").trim().toFloatOrNull() ?: def

        val si = prop("flySpeed", 2f).toInt().coerceIn(0, SPEED.lastIndex)
        val ai = prop("flyAccel", 1f).toInt().coerceIn(0, ACCEL.lastIndex)
        val ci = prop("climbRate", 1f).toInt().coerceIn(0, CLIMB.lastIndex)
        val sm = prop("flySmooth", 2f).toInt().coerceIn(0, SMOOTH.lastIndex)
        val di = prop("dashDist", 2f).toInt().coerceIn(0, DASH.lastIndex)
        val bi = prop("blinkDist", 1f).toInt().coerceIn(0, BLINK.lastIndex)

        speedMul = SPEED[si]
        accelMul = ACCEL[ai]
        climbMul = CLIMB[ci]
        smooth = SMOOTH[sm]
        dashDist = DASH[di]
        blinkDist = BLINK[bi]
    }

    fun set(mode: String, on: Boolean, ctx: Utils.ActionContext) {
        when (mode) {
            "fly" -> fly = on
            "velfly" -> velFly = on
            "hover" -> hover = on
            "glide" -> glide = on
            "stickfly" -> stickFly = on
            "strafe" -> strafe = on
            "autofwd" -> autoFwd = on
            "slowfall" -> slowFall = on
            "airbrake" -> airBrake = on
            "climbassist" -> climbAssist = on
            "swim" -> swim = on
            "zigzag" -> zigzag = on
            "orbit" -> {
                orbit = on
                if (on) {
                    orbitCX = posX
                    orbitCZ = posZ
                    orbitPhase = 0f
                    orbitSet = true
                } else orbitSet = false
            }
            "bunnyhop" -> bunnyHop = on
            "surf" -> surf = on
        }
        if (on) start(ctx) else if (!anyArmed) stop(ctx)
    }

    fun isOn(mode: String): Boolean = when (mode) {
        "fly" -> fly
        "velfly" -> velFly
        "hover" -> hover
        "glide" -> glide
        "stickfly" -> stickFly
        "strafe" -> strafe
        "autofwd" -> autoFwd
        "slowfall" -> slowFall
        "airbrake" -> airBrake
        "climbassist" -> climbAssist
        "swim" -> swim
        "zigzag" -> zigzag
        "orbit" -> orbit
        "bunnyhop" -> bunnyHop
        "surf" -> surf
        else -> false
    }

    fun start(ctx: Utils.ActionContext) {
        if (active) return
        active = true
        Input.startfallback()
        seed(ctx)
        refreshTuning(ctx)
        if (!armedOnce) {
            runCatching { HeadlockHelper.armOffsetUnmanaged(ctx) }
            armedOnce = true
        }
        if (!loopRunning) {
            loopRunning = true
            Thread({
                try {
                    var last = System.nanoTime()
                    while (active && anyArmed) {
                        val now = System.nanoTime()
                        val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                        last = now
                        try {
                            tick(ctx, dt)
                        } catch (_: Throwable) {
                        }
                        Thread.sleep(TICK_MS)
                    }
                } finally {
                    loopRunning = false
                    if (!anyArmed) {
                        runCatching { HeadlockHelper.disarm(ctx) }
                        armedOnce = false
                        Input.releasefallback()
                        active = false
                    }
                }
            }, "movement-mods").also { it.isDaemon = true; it.start() }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        fly = false; velFly = false; hover = false; glide = false
        stickFly = false; strafe = false; autoFwd = false
        slowFall = false; airBrake = false; climbAssist = false
        swim = false; zigzag = false; orbit = false
        bunnyHop = false; surf = false
        orbitSet = false
        active = false
        lastMoveCmd = ""
        velX = 0f; velY = 0f; velZ = 0f
        ctx?.let {
            runCatching { HeadlockHelper.disarm(it) }
            armedOnce = false
            Input.releasefallback()
        }
    }

    fun resetPosition(ctx: Utils.ActionContext? = null) {
        posX = 0f; posY = 0f; posZ = 0f
        tgtX = 0f; tgtY = 0f; tgtZ = 0f
        velX = 0f; velY = 0f; velZ = 0f
        yaw = 0f; pitch = 0f
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, 0f, 0f, 0f) } }
    }

    fun dash(ctx: Utils.ActionContext) {
        refreshTuning(ctx)
        ensureArmed(ctx)
        val (fwd, _) = basis()
        tgtX += fwd[0] * dashDist
        tgtY += fwd[1] * dashDist
        tgtZ += fwd[2] * dashDist
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Dash ${dashDist}m")
    }

    fun blink(ctx: Utils.ActionContext) {
        refreshTuning(ctx)
        ensureArmed(ctx)
        val (fwd, _) = basis()
        posX += fwd[0] * blinkDist
        posY += fwd[1] * blinkDist
        posZ += fwd[2] * blinkDist
        tgtX = posX; tgtY = posY; tgtZ = posZ
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Blink ${blinkDist}m")
    }

    fun phaseStep(ctx: Utils.ActionContext) {
        refreshTuning(ctx)
        ensureArmed(ctx)
        val (fwd, _) = basis()
        val d = blinkDist * 2.5f
        posX += fwd[0] * d
        posY += fwd[1] * d
        posZ += fwd[2] * d
        tgtX = posX; tgtY = posY; tgtZ = posZ
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Phase step ${"%.1f".format(d)}m")
    }

    fun rocketLaunch(ctx: Utils.ActionContext) {
        ensureArmed(ctx)
        tgtY += 12f
        velY = 8f
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Rocket launch")
    }

    fun moonJump(ctx: Utils.ActionContext) {
        ensureArmed(ctx)
        velY = 5.5f
        tgtY += 2f
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Moon jump")
    }

    fun ledgeBoost(ctx: Utils.ActionContext) {
        ensureArmed(ctx)
        tgtY += 1.2f
        val (fwd, _) = basis()
        tgtX += fwd[0] * 1.5f
        tgtZ += fwd[2] * 1.5f
        ctx?.let { runCatching { HeadlockHelper.writeTranslation(it, tgtX, tgtY, tgtZ) } }
        flush(ctx)
        ctx.log("Ledge boost")
    }

    fun recallSave(ctx: Utils.ActionContext) {
        seed(ctx)
        recallX = posX; recallY = posY; recallZ = posZ
        hasRecall = true
        ctx.log("Recall point saved (${"%.2f".format(posX)}, ${"%.2f".format(posY)}, ${"%.2f".format(posZ)})")
        ctx.toast("Recall saved")
    }

    fun recallGo(ctx: Utils.ActionContext) {
        if (!hasRecall) {
            ctx.toast("No recall point")
            return
        }
        ensureArmed(ctx)
        val curX = ctx.run("getprop debug.oculus.headlock.translation.x").trim().toFloatOrNull() ?: posX
        val curY = ctx.run("getprop debug.oculus.headlock.translation.y").trim().toFloatOrNull() ?: posY
        val curZ = ctx.run("getprop debug.oculus.headlock.translation.z").trim().toFloatOrNull() ?: posZ

        val dx = curX - recallX
        val dy = curY - recallY
        val dz = curZ - recallZ

        posX = curX - dx
        posY = curY - dy
        posZ = curZ - dz
        tgtX = posX; tgtY = posY; tgtZ = posZ

        runCatching {
            val fwd = Input.forward()
            if (fwd != null) {
                val fl = kotlin.math.sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2])
                if (fl > 1e-4f) {
                    val along = (dx * fwd[0] + dy * fwd[1] + dz * fwd[2]) / fl
                    posX -= (fwd[0] / fl) * along * 0.15f
                    posY -= (fwd[1] / fl) * along * 0.15f
                    posZ -= (fwd[2] / fl) * along * 0.15f
                    tgtX = posX; tgtY = posY; tgtZ = posZ
                }
            }
        }

        flush(ctx)
        ctx.log(
            "Recall opposite of drift Δ=(${"%.2f".format(dx)}, ${"%.2f".format(dy)}, ${"%.2f".format(dz)}) " +
                    "→ (${"%.2f".format(posX)}, ${"%.2f".format(posY)}, ${"%.2f".format(posZ)})"
        )
        ctx.toast("Recall")
    }

    private fun ensureArmed(ctx: Utils.ActionContext) {
        if (!armedOnce) {
            runCatching { HeadlockHelper.armOffsetUnmanaged(ctx) }
            armedOnce = true
            seed(ctx)
        }
        if (!active && anyArmed) start(ctx)
    }

    private fun seed(ctx: Utils.ActionContext) {
        posX = ctx.run("getprop debug.oculus.headlock.translation.x").trim().toFloatOrNull() ?: posX
        posY = ctx.run("getprop debug.oculus.headlock.translation.y").trim().toFloatOrNull() ?: posY
        posZ = ctx.run("getprop debug.oculus.headlock.translation.z").trim().toFloatOrNull() ?: posZ
        tgtX = posX; tgtY = posY; tgtZ = posZ
    }

    private fun tick(ctx: Utils.ActionContext, dt: Float) {
        refreshTuning(ctx)
        phase += dt
        val spd = speedMul
        val (fwd, right) = basis()

        val rsx = Input.rightThumbstickX()
        val rsy = Input.rightThumbstickY()
        if (abs(rsx) > 0.12f || abs(rsy) > 0.12f) {
            yaw += rsx * 90f * dt
            pitch = (pitch + rsy * 90f * dt).coerceIn(-89f, 89f)
        }

        if (fly && (Input.rightTrigger() || Input.leftTrigger())) {
            val climb = if (Input.leftTrigger()) climbMul else 0f
            val dir = if (Input.rightTrigger()) 1f else 0f
            tgtX += fwd[0] * spd * dir * dt
            tgtY += (fwd[1] * spd * dir + climb * spd * 0.6f) * dt
            tgtZ += fwd[2] * spd * dir * dt
        }

        if (velFly && Input.rightTrigger()) {
            velX += fwd[0] * accelMul * dt
            velY += fwd[1] * accelMul * dt
            velZ += fwd[2] * accelMul * dt
            val cap = spd * 2.5f
            velX = velX.coerceIn(-cap, cap)
            velY = velY.coerceIn(-cap, cap)
            velZ = velZ.coerceIn(-cap, cap)
            tgtX += velX * dt
            tgtY += velY * dt
            tgtZ += velZ * dt
        } else if (velFly) {
            velX *= 0.92f; velY *= 0.92f; velZ *= 0.92f
        }

        if (hover && Input.rightTrigger()) {
            val lx = Input.leftThumbstickX()
            val ly = Input.leftThumbstickY()
            tgtX += (fwd[0] * ly + right[0] * lx) * spd * 0.7f * dt
            tgtZ += (fwd[2] * ly + right[2] * lx) * spd * 0.7f * dt
            velY *= 0.5f
        }

        if (glide && Input.rightTrigger()) {
            tgtX += fwd[0] * spd * 0.85f * dt
            tgtZ += fwd[2] * spd * 0.85f * dt
            tgtY -= 0.6f * dt
        }

        if (stickFly) {
            val lx = Input.leftThumbstickX()
            val ly = Input.leftThumbstickY()
            val climb = if (Input.leftSqueeze()) climbMul else if (Input.leftMenu()) -climbMul else 0f
            if (abs(lx) > 0.12f || abs(ly) > 0.12f || abs(climb) > 0.01f) {
                tgtX += (fwd[0] * ly + right[0] * lx) * spd * dt
                tgtY += (fwd[1] * ly + climb * spd * 0.8f) * dt
                tgtZ += (fwd[2] * ly + right[2] * lx) * spd * dt
            }
        }

        if (strafe) {
            val lx = Input.leftThumbstickX()
            if (abs(lx) > 0.08f) {
                tgtX += right[0] * lx * spd * dt
                tgtY += right[1] * lx * spd * dt
                tgtZ += right[2] * lx * spd * dt
            }
        }

        if (autoFwd) {
            val mul = if (Input.rightSqueeze()) 1.0f else 0.55f
            tgtX += fwd[0] * spd * mul * dt
            tgtY += fwd[1] * spd * mul * 0.15f * dt
            tgtZ += fwd[2] * spd * mul * dt
        }

        if (slowFall && Input.leftSqueeze()) {
            if (velY < 0f) velY *= 0.4f
            tgtY += (-0.35f) * dt
        }

        if (airBrake && Input.leftSqueeze()) {
            velX = 0f; velY = 0f; velZ = 0f
            tgtX = posX; tgtY = posY; tgtZ = posZ
        }

        if (climbAssist && Input.rightSqueeze()) {
            tgtY += spd * climbMul * 0.9f * dt
        }

        if (swim && Input.rightTrigger()) {
            swimPhase += dt * 3.2f
            val stroke = abs(sin(swimPhase.toDouble()).toFloat())
            tgtX += fwd[0] * spd * 0.55f * stroke * dt
            tgtY += sin(swimPhase * 2f) * spd * 0.12f * dt
            tgtZ += fwd[2] * spd * 0.55f * stroke * dt
        }

        if (zigzag && Input.rightTrigger()) {
            tgtX += sin(phase * 5.5f) * spd * 0.9f * dt
            tgtX += fwd[0] * spd * dt
            tgtZ += fwd[2] * spd * dt
        }

        if (orbit && orbitSet) {
            orbitPhase += (spd / 3.5f) * 0.035f
            val radius = 2.5f
            tgtX = orbitCX + cos(orbitPhase) * radius
            tgtZ = orbitCZ + sin(orbitPhase) * radius
            tgtY = posY + sin(orbitPhase * 2f) * 0.05f
        }

        if (bunnyHop && Input.rightTrigger()) {
            if (velY <= 0.05f) velY = 3.2f
            velY -= 9.8f * dt * 0.35f
            tgtY += velY * dt
            tgtX += fwd[0] * spd * 0.7f * dt
            tgtZ += fwd[2] * spd * 0.7f * dt
        }

        if (surf) {
            val mul = if (Input.rightTrigger()) 1.35f else 0.9f
            tgtX += fwd[0] * spd * mul * dt
            tgtZ += fwd[2] * spd * mul * dt
            tgtY += sin(phase * 2.4f) * 0.2f * dt
        }

        posX += (tgtX - posX) * smooth
        posY += (tgtY - posY) * smooth
        posZ += (tgtZ - posZ) * smooth
        if (abs(tgtX - posX) < 5e-4f) posX = tgtX
        if (abs(tgtY - posY) < 5e-4f) posY = tgtY
        if (abs(tgtZ - posZ) < 5e-4f) posZ = tgtZ

        flush(ctx)
    }

    private fun flush(ctx: Utils.ActionContext) {
        val r = HeadlockHelper.headRelativeRotation()
        val rp = HeadlockSensor.clampRotation(r[0])
        val ry = HeadlockSensor.clampRotation(r[1])
        val rr = HeadlockSensor.clampRotation(r[2])
        val cmd =
            "setprop debug.oculus.headlock 1; " +
                    "setprop debug.oculus.headlock.translation.x ${HeadlockHelper.fmt(posX)}; " +
                    "setprop debug.oculus.headlock.translation.y ${HeadlockHelper.fmt(posY)}; " +
                    "setprop debug.oculus.headlock.translation.z ${HeadlockHelper.fmt(posZ)}; " +
                    "setprop debug.oculus.headlock.rotation.x ${HeadlockHelper.fmt(rp)}; " +
                    "setprop debug.oculus.headlock.rotation.y ${HeadlockHelper.fmt(ry)}; " +
                    "setprop debug.oculus.headlock.rotation.z ${HeadlockHelper.fmt(rr)}"
        if (cmd == lastMoveCmd) return
        lastMoveCmd = cmd
        ctx.run(cmd)
    }

    private fun basis(): Pair<FloatArray, FloatArray> {
        val head = Input.forward()
        if (head != null) {
            val fl = sqrt(head[0] * head[0] + head[1] * head[1] + head[2] * head[2])
            if (fl > 1e-4f) {
                head[0] /= fl; head[1] /= fl; head[2] /= fl
            }
            val right = floatArrayOf(
                0f * head[2] - 1f * head[1],
                1f * head[0] - 0f * head[2],
                0f * head[1] - 0f * head[0]
            )
            val rl = sqrt(right[0] * right[0] + right[1] * right[1] + right[2] * right[2])
            if (rl > 1e-4f) {
                right[0] /= rl; right[1] /= rl; right[2] /= rl
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

    fun statusLine(): String {
        val on = listOfNotNull(
            if (fly) "Fly" else null,
            if (velFly) "VelFly" else null,
            if (hover) "Hover" else null,
            if (glide) "Glide" else null,
            if (stickFly) "StickFly" else null,
            if (strafe) "Strafe" else null,
            if (autoFwd) "AutoFwd" else null,
            if (slowFall) "SlowFall" else null,
            if (airBrake) "AirBrake" else null,
            if (climbAssist) "Climb" else null,
            if (swim) "Swim" else null,
            if (zigzag) "ZigZag" else null,
            if (orbit) "Orbit" else null,
            if (bunnyHop) "Bunny" else null,
            if (surf) "Surf" else null
        )
        return if (on.isEmpty()) "movement: idle"
        else "movement: ${on.joinToString(", ")}"
    }
}