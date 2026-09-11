package com.helix

// contact blaku64th on discord if you have any issues ^^
object CameraMods {

    @Volatile var third = false
    @Volatile var shoulder = false
    @Volatile var topDown = false
    @Volatile var lowAngle = false
    @Volatile var spectate = false
    @Volatile var freezeBody = false
    @Volatile var ghost = false
    @Volatile var anchor = false

    @Volatile private var camDistIndex = 1
    private val dist = floatArrayOf(1f, 2f, 3f, 5f)

    private var freezeX = 0f
    private var freezeY = 0f
    private var freezeZ = 0f
    private var frozen = false

    @Volatile private var active = false
    @Volatile private var armed = false

    val anyArmed: Boolean
        get() = third || shoulder || topDown || lowAngle || spectate ||
                freezeBody || ghost || anchor

    fun setCamDist(i: Int) {
        camDistIndex = i.coerceIn(0, dist.lastIndex)
    }

    fun set(mode: String, on: Boolean, ctx: Utils.ActionContext) {
        when (mode) {
            "third" -> third = on
            "shoulder" -> shoulder = on
            "topdown" -> topDown = on
            "lowangle" -> lowAngle = on
            "spectate" -> spectate = on
            "freezebody" -> {
                freezeBody = on
                if (on) captureFreeze(ctx) else frozen = false
            }
            "ghost" -> {
                ghost = on
                if (on) captureFreeze(ctx) else frozen = false
            }
            "anchor" -> {
                anchor = on
                if (on) captureFreeze(ctx) else frozen = false
            }
        }
        if (on) start(ctx) else if (!anyArmed) stop(ctx)
    }

    fun isOn(mode: String): Boolean = when (mode) {
        "third" -> third
        "shoulder" -> shoulder
        "topdown" -> topDown
        "lowangle" -> lowAngle
        "spectate" -> spectate
        "freezebody" -> freezeBody
        "ghost" -> ghost
        "anchor" -> anchor
        else -> false
    }

    private fun captureFreeze(ctx: Utils.ActionContext) {
        freezeX = ctx.run("getprop debug.oculus.headlock.translation.x").trim().toFloatOrNull() ?: 0f
        freezeY = ctx.run("getprop debug.oculus.headlock.translation.y").trim().toFloatOrNull() ?: 0f
        freezeZ = ctx.run("getprop debug.oculus.headlock.translation.z").trim().toFloatOrNull() ?: 0f
        frozen = true
    }

    fun start(ctx: Utils.ActionContext) {
        if (active) return
        active = true
        if (!armed) {
            HeadlockHelper.armOffset(ctx)
            armed = true
        }
        Thread({
            try {
                while (active && anyArmed) {
                    val d = dist[camDistIndex]
                    var x = 0f
                    var y = 0f
                    var z = 0f
                    var pitchOff = 0f
                    val yawOff = 0f
                    val rollOff = 0f

                    if (frozen && (freezeBody || ghost || anchor)) {
                        x = freezeX; y = freezeY; z = freezeZ
                        HeadlockHelper.writePose(ctx, x, y, z, 0f, 0f, 0f)
                    } else {
                        if (third) z += d
                        if (shoulder) {
                            z += d * 0.8f
                            x += 0.35f
                        }
                        if (topDown) {
                            y += d * 1.6f
                            pitchOff = 70f
                        }
                        if (lowAngle) {
                            y -= 0.7f
                            pitchOff = -25f
                        }
                        if (spectate) {
                            y += 6f
                            z += 6f
                        }
                        val r = HeadlockHelper.headRelativeRotation()
                        HeadlockHelper.writePose(
                            ctx, x, y, z,
                            r[0] + pitchOff,
                            r[1] + yawOff,
                            r[2] + rollOff
                        )
                    }

                    Thread.sleep(50)
                }
            } finally {
                if (!anyArmed) {
                    runCatching { HeadlockHelper.disarm(ctx) }
                    armed = false
                    active = false
                    frozen = false
                }
            }
        }, "camera-mods").also { it.isDaemon = true; it.start() }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        third = false; shoulder = false; topDown = false
        lowAngle = false; spectate = false
        freezeBody = false; ghost = false; anchor = false
        frozen = false
        active = false
        ctx?.let {
            runCatching { HeadlockHelper.disarm(it) }
            armed = false
        }
    }
}