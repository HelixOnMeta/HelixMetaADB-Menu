package com.helix

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object RgbPickerDialog {

    fun show(
        context: Context,
        initialR: Int = 255,
        initialG: Int = 0,
        initialB: Int = 0,
        title: String = "Custom LED Color",
        onColorChosen: (r: Int, g: Int, b: Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        var r = initialR.coerceIn(0, 255)
        var g = initialG.coerceIn(0, 255)
        var b = initialB.coerceIn(0, 255)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(16), px(24), px(8))
        }

        val preview = TextView(context).apply {
            text = " "
            setBackgroundColor(Color.rgb(r, g, b))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(80)
            ).apply { bottomMargin = px(16) }
        }
        root.addView(preview)

        fun makeSlider(label: String, initial: Int, color: Int, onChange: (Int) -> Unit): LinearLayout {
            val block = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(4), 0, px(8))
            }
            val tv = TextView(context).apply {
                text = "$label: $initial"
                textSize = 14f
            }
            val seek = SeekBar(context).apply {
                max = 255
                progress = initial
                progressTintList = android.content.res.ColorStateList.valueOf(color)
                thumbTintList = android.content.res.ColorStateList.valueOf(color)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        tv.text = "$label: $progress"
                        onChange(progress)
                        preview.setBackgroundColor(Color.rgb(r, g, b))
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            block.addView(tv)
            block.addView(seek)
            return block
        }

        root.addView(makeSlider("Red", r, Color.RED) { r = it })
        root.addView(makeSlider("Green", g, Color.GREEN) { g = it })
        root.addView(makeSlider("Blue", b, Color.BLUE) { b = it })

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Apply") { _, _ -> onColorChosen(r, g, b) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear Custom") { _, _ ->
                RgbLedEngine.clearCustomColor()
                Toast.makeText(context, "Custom color cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Two-color picker for gradient effects.
     * Color A → Color B (smooth lerp back and forth).
     */
    fun showGradient(
        context: Context,
        initialA: Triple<Int, Int, Int> = Triple(
            RgbLedEngine.gradientR1, RgbLedEngine.gradientG1, RgbLedEngine.gradientB1
        ),
        initialB: Triple<Int, Int, Int> = Triple(
            RgbLedEngine.gradientR2, RgbLedEngine.gradientG2, RgbLedEngine.gradientB2
        ),
        onColorsChosen: (r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        var r1 = initialA.first.coerceIn(0, 255)
        var g1 = initialA.second.coerceIn(0, 255)
        var b1 = initialA.third.coerceIn(0, 255)
        var r2 = initialB.first.coerceIn(0, 255)
        var g2 = initialB.second.coerceIn(0, 255)
        var b2 = initialB.third.coerceIn(0, 255)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(16), px(24), px(8))
        }

        val preview = TextView(context).apply {
            text = " "
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(80)
            ).apply { bottomMargin = px(12) }
        }
        fun updatePreview() {
            preview.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(r1, g1, b1), Color.rgb(r2, g2, b2))
            )
        }
        updatePreview()
        root.addView(preview)

        fun section(title: String): TextView = TextView(context).apply {
            text = title
            textSize = 13f
            setPadding(0, px(8), 0, px(4))
        }

        fun makeSlider(
            label: String,
            initial: Int,
            tint: Int,
            onChange: (Int) -> Unit
        ): LinearLayout {
            val block = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(2), 0, px(4))
            }
            val tv = TextView(context).apply {
                text = "$label: $initial"
                textSize = 13f
            }
            val seek = SeekBar(context).apply {
                max = 255
                progress = initial
                progressTintList = android.content.res.ColorStateList.valueOf(tint)
                thumbTintList = android.content.res.ColorStateList.valueOf(tint)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        tv.text = "$label: $progress"
                        onChange(progress)
                        updatePreview()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            block.addView(tv)
            block.addView(seek)
            return block
        }

        root.addView(section("Color A (start)"))
        root.addView(makeSlider("R", r1, Color.RED) { r1 = it })
        root.addView(makeSlider("G", g1, Color.GREEN) { g1 = it })
        root.addView(makeSlider("B", b1, Color.BLUE) { b1 = it })

        root.addView(section("Color B (end)"))
        root.addView(makeSlider("R", r2, Color.RED) { r2 = it })
        root.addView(makeSlider("G", g2, Color.GREEN) { g2 = it })
        root.addView(makeSlider("B", b2, Color.BLUE) { b2 = it })

        AlertDialog.Builder(context)
            .setTitle("2-Color Gradient")
            .setView(root)
            .setPositiveButton("Apply") { _, _ ->
                onColorsChosen(r1, g1, b1, r2, g2, b2)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

object RgbLedEngine {

    @Volatile var active = false
    val isActive: Boolean get() = active

    @Volatile var batteryMode = false
    val isBatteryMode: Boolean get() = batteryMode

    @Volatile var customRed: Int? = null
    @Volatile var customGreen: Int? = null
    @Volatile var customBlue: Int? = null

    @Volatile var rainbow: Boolean = true

    // 2-color gradient endpoints (defaults: purple → pink)
    @Volatile var gradientR1: Int = 170
    @Volatile var gradientG1: Int = 0
    @Volatile var gradientB1: Int = 255
    @Volatile var gradientR2: Int = 255
    @Volatile var gradientG2: Int = 0
    @Volatile var gradientB2: Int = 170

    enum class Effect {
        RAINBOW,
        DISCRETE,
        CUSTOM,
        PURPLE_TO_PINK,
        PINK_TO_PURPLE,
        BREATHING,
        PURPLE_PINK_BREATHING,
        GRADIENT_2COLOR
    }

    @Volatile var effect: Effect = Effect.RAINBOW

    /** 0.0 = off, 1.0 = full */
    @Volatile var brightness: Float = 1.0f

    /** Effect step delay. Smaller = faster. */
    @Volatile var effectIntervalMs: Long = 20L

    fun applyBrightnessLevel(value: Float) {
        brightness = value.coerceIn(0f, 1f)
    }

    fun applyEffect(newEffect: Effect) {
        effect = newEffect
        rainbow = newEffect == Effect.RAINBOW
    }

    fun setGradientColors(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int) {
        gradientR1 = r1.coerceIn(0, 255)
        gradientG1 = g1.coerceIn(0, 255)
        gradientB1 = b1.coerceIn(0, 255)
        gradientR2 = r2.coerceIn(0, 255)
        gradientG2 = g2.coerceIn(0, 255)
        gradientB2 = b2.coerceIn(0, 255)
    }

    private const val step = 5
    private const val sleepms = 5L
    private const val discretems = 400L
    private const val batteryIntervalMs = 60_000L

    private const val Red = "/sys/class/leds/red/brightness"
    private const val Green = "/sys/class/leds/green/brightness"
    private const val Blue = "/sys/class/leds/blue/brightness"

    private val colours = listOf(
        Triple(255, 0, 0),
        Triple(0, 255, 0),
        Triple(0, 0, 255),
        Triple(255, 255, 0),
        Triple(0, 255, 255),
        Triple(255, 0, 255)
    )

    fun setCustomColor(r: Int, g: Int, b: Int) {
        customRed = r.coerceIn(0, 255)
        customGreen = g.coerceIn(0, 255)
        customBlue = b.coerceIn(0, 255)
    }

    fun clearCustomColor() {
        customRed = null
        customGreen = null
        customBlue = null
    }

    fun applyColor(ctx: Utils.ActionContext, r: Int, g: Int, b: Int): Boolean {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("RGB LED requires root")
            ctx.toast("Root required")
            return false
        }
        val rr = clamp(r)
        val gg = clamp(g)
        val bb = clamp(b)
        setCustomColor(rr, gg, bb)
        applyEffect(Effect.CUSTOM)
        RootHelper.runRoot(
            ctx,
            "setprop sys.rgb.led.red $rr; " +
                    "setprop sys.rgb.led.green $gg; " +
                    "setprop sys.rgb.led.blue $bb"
        )
        setRgb(ctx, rr, gg, bb)
        ctx.log("RGB LED color → R$rr G$gg B$bb")
        return true
    }

    fun off(ctx: Utils.ActionContext) {
        clearCustomColor()
        if (RootHelper.hasRoot(ctx)) {
            setRgb(ctx, 0, 0, 0)
            RootHelper.runRoot(
                ctx,
                "setprop sys.rgb.led.red 0; setprop sys.rgb.led.green 0; setprop sys.rgb.led.blue 0"
            )
        }
        ctx.log("RGB LED off")
    }

    fun applyColorAndHold(ctx: Utils.ActionContext, r: Int, g: Int, b: Int) {
        if (!applyColor(ctx, r, g, b)) return
        batteryMode = false
        if (!active) {
            Thread({ run(ctx) }, "RgbLedEngine-hold").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Breathing of an explicit RGB color (sine fade 0↔100%). */
    fun startBreathing(ctx: Utils.ActionContext, r: Int, g: Int, b: Int) {
        setCustomColor(r, g, b)
        applyEffect(Effect.BREATHING)
        if (active) {
            // switch effect on next loop; force restart for clean handoff
            stop()
            Thread.sleep(40)
        }
        Thread({ run(ctx) }, "RgbLedEngine-breathe").apply {
            isDaemon = true
            start()
        }
    }

    fun startBreathing(ctx: Utils.ActionContext) {
        startBreathing(
            ctx,
            customRed ?: 255,
            customGreen ?: 0,
            customBlue ?: 0
        )
    }

    /** Smooth gradient between two user-chosen colors (A ↔ B). */
    fun startGradient(
        ctx: Utils.ActionContext,
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int
    ) {
        setGradientColors(r1, g1, b1, r2, g2, b2)
        applyEffect(Effect.GRADIENT_2COLOR)
        if (active) {
            stop()
            Thread.sleep(40)
        }
        Thread({ run(ctx) }, "RgbLedEngine-gradient").apply {
            isDaemon = true
            start()
        }
    }

    fun startGradient(ctx: Utils.ActionContext) {
        startGradient(
            ctx,
            gradientR1, gradientG1, gradientB1,
            gradientR2, gradientG2, gradientB2
        )
    }

    fun startPurplePinkBreathing(ctx: Utils.ActionContext) {
        applyEffect(Effect.PURPLE_PINK_BREATHING)
        if (active) {
            stop()
            Thread.sleep(40)
        }
        Thread({ run(ctx) }, "RgbLedEngine-ppb").apply {
            isDaemon = true
            start()
        }
    }

    fun startPurpleToPink(ctx: Utils.ActionContext) {
        applyEffect(Effect.PURPLE_TO_PINK)
        if (active) {
            stop()
            Thread.sleep(40)
        }
        Thread({ run(ctx) }, "RgbLedEngine-ptp").apply {
            isDaemon = true
            start()
        }
    }

    fun startBatteryLed(ctx: Utils.ActionContext) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("Battery LED requires root")
            ctx.toast("Root required")
            return
        }
        stop()
        Thread.sleep(50)
        batteryMode = true
        active = true
        ctx.log("Battery LED started (check every 60s: green→yellow→red)")
        Thread({
            try {
                while (active && batteryMode) {
                    val level = readBatteryPercent(ctx)
                    val (r, g, b) = colorForBattery(level)
                    setRgb(ctx, r, g, b)
                    ctx.log("Battery LED: $level% → RGB($r,$g,$b)")
                    var waited = 0L
                    while (active && batteryMode && waited < batteryIntervalMs) {
                        Thread.sleep(500)
                        waited += 500
                    }
                }
            } finally {
                if (batteryMode) {
                    batteryMode = false
                    active = false
                    setRgb(ctx, 0, 0, 0)
                    ctx.log("Battery LED stopped")
                }
            }
        }, "RgbLedEngine-battery").apply {
            isDaemon = true
            start()
        }
    }

    fun stopBatteryLed() {
        batteryMode = false
        active = false
    }

    fun readBatteryPercent(ctx: Utils.ActionContext): Int {
        val dumpsys = runCatching { ctx.run("dumpsys battery") }.getOrDefault("")
        Regex("""level:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(dumpsys)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?.let { return it }
        val paths = listOf(
            "/sys/class/power_supply/battery/capacity",
            "/sys/class/power_supply/bq27z561-0/capacity",
            "/sys/class/power_supply/bms/capacity"
        )
        for (p in paths) {
            val out = runCatching {
                if (RootHelper.hasRoot(ctx)) RootHelper.runRoot(ctx, "cat $p 2>/dev/null")
                else ctx.run("cat $p 2>/dev/null")
            }.getOrDefault("").trim()
            out.toIntOrNull()?.coerceIn(0, 100)?.let { return it }
        }
        return -1
    }

    fun colorForBattery(percent: Int): Triple<Int, Int, Int> {
        if (percent < 0) return Triple(0, 0, 0)
        val p = percent.coerceIn(0, 100)
        return when {
            p <= 15 -> Triple(255, 0, 0)
            p <= 50 -> {
                val t = (p - 15).toFloat() / (50 - 15)
                Triple(255, lerp(0, 255, t), 0)
            }
            else -> {
                val t = (p - 50).toFloat() / (100 - 50)
                Triple(lerp(255, 0, t), 255, 0)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val x = a + (b - a) * t.coerceIn(0f, 1f)
        return x.toInt().coerceIn(0, 255)
    }

    private fun lerpColor(
        from: Triple<Int, Int, Int>,
        to: Triple<Int, Int, Int>,
        t: Float
    ): Triple<Int, Int, Int> {
        val x = t.coerceIn(0f, 1f)
        return Triple(
            lerp(from.first, to.first, x),
            lerp(from.second, to.second, x),
            lerp(from.third, to.third, x)
        )
    }

    private fun applyBrightness(
        color: Triple<Int, Int, Int>,
        multiplier: Float = brightness
    ): Triple<Int, Int, Int> {
        val m = multiplier.coerceIn(0f, 1f)
        return Triple(
            (color.first * m).toInt().coerceIn(0, 255),
            (color.second * m).toInt().coerceIn(0, 255),
            (color.third * m).toInt().coerceIn(0, 255)
        )
    }

    private fun setRgbBright(
        ctx: Utils.ActionContext,
        r: Int, g: Int, b: Int,
        multiplier: Float = brightness
    ) {
        val (rr, gg, bb) = applyBrightness(Triple(r, g, b), multiplier)
        setRgb(ctx, rr, gg, bb)
    }

    fun run(ctx: Utils.ActionContext) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("RGB LED requires root")
            ctx.toast("Root required")
            return
        }
        if (active) return
        batteryMode = false
        active = true
        ctx.log("RGB LED started (effect=$effect, brightness=${(brightness * 100).toInt()}%)")
        try {
            when (effect) {
                Effect.CUSTOM -> {
                    while (active && !batteryMode) {
                        setRgbBright(
                            ctx,
                            customRed ?: 0,
                            customGreen ?: 0,
                            customBlue ?: 0
                        )
                        Thread.sleep(discretems)
                    }
                }

                Effect.PURPLE_TO_PINK -> {
                    val purple = Triple(170, 0, 255)
                    val pink = Triple(255, 0, 170)
                    while (active && !batteryMode) {
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(purple, pink, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(pink, purple, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                    }
                }

                Effect.PINK_TO_PURPLE -> {
                    val pink = Triple(255, 0, 170)
                    val purple = Triple(170, 0, 255)
                    while (active && !batteryMode) {
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(pink, purple, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(purple, pink, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                    }
                }

                Effect.GRADIENT_2COLOR -> {
                    while (active && !batteryMode) {
                        val a = Triple(gradientR1, gradientG1, gradientB1)
                        val b = Triple(gradientR2, gradientG2, gradientB2)
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(a, b, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val c = lerpColor(b, a, i / 255f)
                            setRgbBright(ctx, c.first, c.second, c.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                    }
                }

                Effect.BREATHING -> {
                    val base = Triple(
                        customRed ?: 255,
                        customGreen ?: 0,
                        customBlue ?: 0
                    )
                    while (active && !batteryMode) {
                        for (i in 0..360 step 3) {
                            if (!active || batteryMode) break
                            val phase = Math.toRadians(i.toDouble())
                            val wave = ((kotlin.math.sin(phase) + 1.0) / 2.0).toFloat()
                            val color = applyBrightness(base, wave * brightness)
                            setRgb(ctx, color.first, color.second, color.third)
                            Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                        }
                    }
                }

                Effect.PURPLE_PINK_BREATHING -> {
                    val purple = Triple(170, 0, 255)
                    val pink = Triple(255, 0, 170)
                    while (active && !batteryMode) {
                        for (i in 0..255 step step) {
                            if (!active || batteryMode) break
                            val color = lerpColor(purple, pink, i / 255f)
                            for (b in 0..100 step 4) {
                                if (!active || batteryMode) break
                                val wave = b / 100f
                                val smooth = wave * wave * (3f - 2f * wave)
                                setRgbBright(ctx, color.first, color.second, color.third, smooth * brightness)
                                Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                            }
                            for (b in 100 downTo 0 step 4) {
                                if (!active || batteryMode) break
                                val wave = b / 100f
                                val smooth = wave * wave * (3f - 2f * wave)
                                setRgbBright(ctx, color.first, color.second, color.third, smooth * brightness)
                                Thread.sleep(effectIntervalMs.coerceAtLeast(1L))
                            }
                        }
                    }
                }

                Effect.DISCRETE -> {
                    var index = 0
                    while (active && !batteryMode) {
                        val (r, g, b) = colours[index % colours.size]
                        setRgbBright(ctx, r, g, b)
                        index++
                        Thread.sleep(discretems)
                    }
                }

                Effect.RAINBOW -> {
                    while (active && !batteryMode) {
                        var i = 0
                        while (active && !batteryMode && i <= 255) {
                            setRgb(ctx, clamp(255 - i), clamp(i), 0)
                            Thread.sleep(sleepms)
                            i += step
                        }
                        i = 0
                        while (active && !batteryMode && i <= 255) {
                            setRgb(ctx, 0, clamp(255 - i), clamp(i))
                            Thread.sleep(sleepms)
                            i += step
                        }
                        i = 0
                        while (active && !batteryMode && i <= 255) {
                            setRgb(ctx, clamp(i), 0, clamp(255 - i))
                            Thread.sleep(sleepms)
                            i += step
                        }
                    }
                }
            }
        } finally {
            if (!batteryMode) {
                active = false
                setRgb(ctx, 0, 0, 0)
                ctx.log("RGB LED stopped")
            }
        }
    }

    fun stop() {
        batteryMode = false
        active = false
    }

    private fun setRgb(ctx: Utils.ActionContext, r: Int, g: Int, b: Int) {
        val rr = clamp(r)
        val gg = clamp(g)
        val bb = clamp(b)
        RootHelper.runRoot(
            ctx,
            "echo $rr > $Red; echo $gg > $Green; echo $bb > $Blue"
        )
    }

    private fun clamp(v: Int): Int = when {
        v < 0 -> 0
        v > 255 -> 255
        else -> v
    }
}