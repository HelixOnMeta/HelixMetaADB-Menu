package com.helix

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

// contact blaku64th on discord if you have any issues ^^
object RgbPickerDialog {

    fun show(
        context: Context,
        initialR: Int = 255,
        initialG: Int = 0,
        initialB: Int = 0,
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
            ).apply {
                bottomMargin = px(16)
            }
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
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    tv.text = "$label: $progress"
                    onChange(progress)
                    preview.setBackgroundColor(Color.rgb(r, g, b))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            block.addView(tv)
            block.addView(seek)
            return block
        }

        root.addView(makeSlider("Red", r, Color.RED) { r = it })
        root.addView(makeSlider("Green", g, Color.GREEN) { g = it })
        root.addView(makeSlider("Blue", b, Color.BLUE) { b = it })

        AlertDialog.Builder(context)
            .setTitle("Custom LED Color")
            .setView(root)
            .setPositiveButton("Apply") { _, _ ->
                onColorChosen(r, g, b)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear Custom") { _, _ ->
                RgbLedEngine.clearCustomColor()
                Toast.makeText(context, "Custom color cleared", Toast.LENGTH_SHORT).show()
            }
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
        val dumpsys = runCatching {
            ctx.run("dumpsys battery")
        }.getOrDefault("")
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
                if (RootHelper.hasRoot(ctx)) {
                    RootHelper.runRoot(ctx, "cat $p 2>/dev/null")
                } else {
                    ctx.run("cat $p 2>/dev/null")
                }
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

    fun run(ctx: Utils.ActionContext) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("RGB LED requires root")
            ctx.toast("Root required")
            return
        }
        if (active) return

        batteryMode = false
        active = true
        ctx.log(
            when {
                customRed != null ->
                    "RGB LED started (custom ${customRed},${customGreen},${customBlue})"
                rainbow ->
                    "RGB LED started (rainbow cycle via sysfs)"
                else ->
                    "RGB LED started (discrete cycle via sysfs)"
            }
        )

        try {
            if (customRed != null) {
                while (active && !batteryMode) {
                    val r = customRed ?: 0
                    val g = customGreen ?: 0
                    val b = customBlue ?: 0
                    setRgb(ctx, r, g, b)
                    Thread.sleep(discretems)
                }
            } else if (rainbow) {
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
            } else {
                var index = 0
                while (active && !batteryMode) {
                    val (r, g, b) = colours[index % colours.size]
                    setRgb(ctx, r, g, b)
                    index++
                    Thread.sleep(discretems)
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