package com.helix

// contact blaku64th on discord if you have any issues ^^
object UpdateBlocker {

    @Volatile public var active = false
    val isActive: Boolean get() = active

    public const val interval = 6000L

    fun run(ctx: Utils.ActionContext) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("Root required")
            ctx.toast("Root required")
            return
        }

        active = true
        ctx.log("Blocker started")

        try {
            while (active) {
                RootHelper.runRoot(ctx, "update_engine_client --cancel 2>/dev/null || true")
                RootHelper.runRoot(ctx, "update_engine_client --reset_status 2>/dev/null || true")
                Thread.sleep(interval)
            }
        } finally {
            active = false
            ctx.log("OTA Blocker stopped")
        }
    }

    fun stop() {
        active = false
    }
}

object Input {

    val usefallback: Boolean get() = !ControllerInput.tracking

    fun startfallback() {
        AdbButtonInput.acquire()
        OrientationTracker.getInstance().let {
            if (!it.isRunning) it.start()
        }
    }

    fun releasefallback() {
        AdbButtonInput.release()
    }

    fun recenter() {
        OrientationTracker.getInstance().recenter()
    }

    fun forward(): FloatArray? {
        if (ControllerInput.tracking) {
            val q = floatArrayOf(
                ControllerInput.headRotX, ControllerInput.headRotY,
                ControllerInput.headRotZ, ControllerInput.headRotW
            )
            return rotateVector(q, floatArrayOf(0f, 0f, -1f))
        }
        return OrientationTracker.getInstance().getForwardOrNull()
    }

    fun rightA(): Boolean = if (ControllerInput.tracking) ControllerInput.rightA else AdbButtonInput.rightA
    fun rightB(): Boolean = if (ControllerInput.tracking) ControllerInput.rightB else AdbButtonInput.rightB
    fun rightTrigger(): Boolean = if (ControllerInput.tracking) ControllerInput.rightTrigger else AdbButtonInput.rightTrigger
    fun rightSqueeze(): Boolean = if (ControllerInput.tracking) ControllerInput.rightSqueeze else AdbButtonInput.rightSqueeze
    fun rightThumbstickClick(): Boolean =
        if (ControllerInput.tracking) ControllerInput.rightThumbstickClick else AdbButtonInput.rightThumbstickClick
    fun rightThumbstickX(): Float = if (ControllerInput.tracking) ControllerInput.rightThumbstickX else AdbButtonInput.rightThumbstickX
    fun rightThumbstickY(): Float = if (ControllerInput.tracking) ControllerInput.rightThumbstickY else AdbButtonInput.rightThumbstickY

    fun leftX(): Boolean = if (ControllerInput.tracking) ControllerInput.leftX else AdbButtonInput.leftX
    fun leftY(): Boolean = if (ControllerInput.tracking) ControllerInput.leftY else AdbButtonInput.leftY
    fun leftMenu(): Boolean = if (ControllerInput.tracking) ControllerInput.leftMenu else AdbButtonInput.Menu
    fun leftHome(): Boolean = if (ControllerInput.tracking) ControllerInput.leftHome else AdbButtonInput.Home
    fun leftTrigger(): Boolean = if (ControllerInput.tracking) ControllerInput.leftTrigger else AdbButtonInput.leftTrigger
    fun leftSqueeze(): Boolean = if (ControllerInput.tracking) ControllerInput.leftSqueeze else AdbButtonInput.leftSqueeze
    fun leftThumbstickClick(): Boolean =
        if (ControllerInput.tracking) ControllerInput.leftThumbstickClick else AdbButtonInput.leftThumbstickClick
    fun leftThumbstickX(): Float = if (ControllerInput.tracking) ControllerInput.leftThumbstickX else AdbButtonInput.leftThumbstickX
    fun leftThumbstickY(): Float = if (ControllerInput.tracking) ControllerInput.leftThumbstickY else AdbButtonInput.leftThumbstickY

    fun rightAimForward(): FloatArray? {
        if (!ControllerInput.tracking) return null
        val q = floatArrayOf(
            ControllerInput.rightRotX, ControllerInput.rightRotY,
            ControllerInput.rightRotZ, ControllerInput.rightRotW
        )
        return rotateVector(q, floatArrayOf(0f, 0f, -1f))
    }

    fun leftAimForward(): FloatArray? {
        if (!ControllerInput.tracking) return null
        val q = floatArrayOf(
            ControllerInput.leftRotX, ControllerInput.leftRotY,
            ControllerInput.leftRotZ, ControllerInput.leftRotW
        )
        return rotateVector(q, floatArrayOf(0f, 0f, -1f))
    }

    public fun rotateVector(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val uvx = qy * v[2] - qz * v[1]
        val uvy = qz * v[0] - qx * v[2]
        val uvz = qx * v[1] - qy * v[0]
        val uuvx = qy * uvz - qz * uvy
        val uuvy = qz * uvx - qx * uvz
        val uuvz = qx * uvy - qy * uvx
        return floatArrayOf(
            v[0] + 2f * (qw * uvx + uuvx),
            v[1] + 2f * (qw * uvy + uuvy),
            v[2] + 2f * (qw * uvz + uuvz)
        )
    }
}