package com.helix

// contact blaku64th on discord if you have any issues ^^
object Grapple {

    @Volatile var active = false
    val isActive: Boolean get() = active

    var posX = 0f
    var posY = 0f
    var posZ = 0f

    var firing = false
    var lockedDirX = 0f
    var lockedDirY = 0f
    var lockedDirZ = 0f

    const val pollms = 50L
    const val pullspeed = 3.0f

    fun run(ctx: Utils.ActionContext) {
        if (active) return
        Thread({ runBlocking(ctx) }, "GrappleEngine").apply { isDaemon = true; start() }
    }

    private fun runBlocking(ctx: Utils.ActionContext) {
        active = true
        Input.startfallback()
        try {
            seedStartingPosition(ctx)
            runCatching { HeadlockHelper.armOffsetUnmanaged(ctx) }
            ctx.log("[Grapple] armed — hold grip + trigger to fire")
            ctx.toast("Grapple: grip+trigger")

            while (active) {
                val leftGrip = Input.leftSqueeze()
                val rightGrip = Input.rightSqueeze()
                val useRight = rightGrip || !leftGrip
                val gripping = rightGrip || leftGrip
                val triggering = if (useRight) Input.rightTrigger() else Input.leftTrigger()

                val shouldFire = gripping && triggering

                if (shouldFire && !firing) {
                    val dir = if (useRight) {
                        Input.rightAimForward() ?: Input.forward()
                    } else {
                        Input.leftAimForward() ?: Input.forward()
                    }

                    if (dir != null) {
                        lockedDirX = dir[0]
                        lockedDirY = dir[1]
                        lockedDirZ = dir[2]
                        firing = true
                        ctx.log(
                            "Grapple fired -> dir(" +
                                    "${"%.2f".format(dir[0])}, " +
                                    "${"%.2f".format(dir[1])}, " +
                                    "${"%.2f".format(dir[2])})"
                        )
                    } else {
                        lockedDirX = 0f
                        lockedDirY = 0f
                        lockedDirZ = -1f
                        firing = true
                        ctx.log("Grapple fired (fallback forward)")
                    }
                } else if (!shouldFire && firing) {
                    firing = false
                    ctx.log("Grapple released")
                    HeadlockHelper.headlockOff(ctx)
                }

                if (firing) {
                    val speed = readGrappleSpeed(ctx)
                    val dt = pollms / 1000f
                    posX += lockedDirX * speed * dt
                    posY += lockedDirY * speed * dt
                    posZ += lockedDirZ * speed * dt
                    HeadlockHelper.writeOffset(ctx, posX, posY, posZ, respectHold = false)
                }

                Thread.sleep(pollms)
            }
        } finally {
            firing = false
            Input.releasefallback()
            runCatching { HeadlockHelper.disarm(ctx) }
            active = false
        }
    }

    fun stop() {
        active = false
    }

    fun readGrappleSpeed(ctx: Utils.ActionContext): Float {
        val raw = ctx.run("getprop debug.mod.grappleSpeed").trim()
        val sliderVal = raw.toFloatOrNull() ?: 5f
        return pullspeed * (sliderVal / 5f).coerceIn(0.1f, 4f)
    }

    fun seedStartingPosition(ctx: Utils.ActionContext) {
        posX = ctx.run("getprop debug.oculus.headlock.translation.x").trim().toFloatOrNull() ?: 0f
        posY = ctx.run("getprop debug.oculus.headlock.translation.y").trim().toFloatOrNull() ?: 0f
        posZ = ctx.run("getprop debug.oculus.headlock.translation.z").trim().toFloatOrNull() ?: 0f
    }
}
