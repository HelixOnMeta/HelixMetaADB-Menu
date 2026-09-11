package com.helix

// contact blaku64th on discord if you have any issues ^^
object IPDADB {

    private const val neutral = 0.064f

    private val arm = floatArrayOf(
        neutral,
        0.08f,
        0.098f,
        0.12f,
        0.15f,
        0.19f
    )

    private val height = floatArrayOf(0.25f, 0.5f, 1.0f, 2.0f)

    @Volatile var longArms = false
    @Volatile var tiny = false
    @Volatile var giant = false
    @Volatile var tall = false
    @Volatile var short = false
    @Volatile var sideShift = false

    @Volatile private var armIndex = 2
    @Volatile private var heightIndex = 1

    val isActive: Boolean
        get() = longArms || tiny || giant || tall || short || sideShift

    fun setArmIndex(i: Int) {
        armIndex = i.coerceIn(0, arm.lastIndex)
    }

    fun setHeightIndex(i: Int) {
        heightIndex = i.coerceIn(0, height.lastIndex)
    }

    fun apply(ctx: Utils.ActionContext) {
        var f = if (!longArms) {
            neutral
        } else {
            var i = 2
            if (armIndex in arm.indices) {
                i = armIndex
            }
            arm[i]
        }

        if (tiny) {
            f = 0.03f
        }
        if (giant) {
            f = 0.13f
        }

        var i2 = 1
        if (heightIndex in height.indices) {
            i2 = heightIndex
        }

        var f2 = if (tall) height[i2] else 0f
        if (short) {
            f2 -= height[i2]
        }
        if (longArms) {
            f2 += (f - neutral) * 4f
        }

        val f3 = if (sideShift) 0.5f else 0f

        ctx.run(
            "setprop debug.oculus.ipd ${fmt(f)}; " +
                    "setprop debug.oculus.vertOffsetMeters ${fmt(f2)}; " +
                    "setprop debug.oculus.horizOffsetMeters ${fmt(f3)}"
        )
    }

    fun reset(ctx: Utils.ActionContext) {
        longArms = false
        tiny = false
        giant = false
        tall = false
        short = false
        sideShift = false
        ctx.run(
            "setprop debug.oculus.ipd ${fmt(neutral)}; " +
                    "setprop debug.oculus.vertOffsetMeters 0; " +
                    "setprop debug.oculus.horizOffsetMeters 0"
        )
    }

    fun scaleStatus(): String {
        val ipd = when {
            tiny -> 0.03f
            giant -> 0.13f
            longArms -> arm[armIndex]
            else -> neutral
        }
        val world = (neutral / ipd) * 100f
        return "ipd ${"%.3f".format(ipd)}  world ${"%.0f".format(world)}%"
    }

    fun runHold(ctx: Utils.ActionContext) {
        if (!isActive) return
        try {
            while (isActive) {
                val raw = ctx.run("getprop debug.mod.longArms").trim().toFloatOrNull() ?: 2f
                armIndex = when {
                    raw <= 0f -> 0
                    raw < 1.5f -> 1
                    raw < 3f -> 2
                    raw < 5f -> 3
                    raw < 7.5f -> 4
                    else -> 5
                }
                val hRaw = ctx.run("getprop debug.mod.heightAmount").trim().toFloatOrNull() ?: 1f
                heightIndex = hRaw.toInt().coerceIn(0, height.lastIndex)
                apply(ctx)
                Thread.sleep(250)
            }
        } finally {
        }
    }

    private fun fmt(v: Float): String =
        "%.4f".format(java.util.Locale.US, v)
}