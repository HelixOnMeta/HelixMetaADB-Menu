package com.helix

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils

object ThemeManager {
    private const val PREF = "helix_theme"
    private const val KEY = "theme_id"

    data class Theme(
        val id: String,
        val label: String,
        val accent: Int,
        val accentSecondary: Int,
        val surface: Int,
        val card: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val stroke: Int,
        val onAccent: Int,
        val backgroundDrawable: String? = null,
        val backgroundAsset: String? = null,
        val backgroundColor: Int = surface
    )

    val THEMES = listOf(
        Theme(
            "violet", "Violet (default)",
            accent = 0xFFB98CFF.toInt(),
            accentSecondary = 0xFFD946EF.toInt(),
            surface = 0xFF121218.toInt(),
            card = 0xFF12101A.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFFAAAAAA.toInt(),
            stroke = 0x40B98CFF,
            onAccent = Color.BLACK,
            backgroundColor = 0xFF121218.toInt()
        ),
        Theme(
            "cyan", "Cyan",
            accent = 0xFF00E5FF.toInt(),
            accentSecondary = 0xFF00B8D4.toInt(),
            surface = 0xFF0A1218.toInt(),
            card = 0xFF0D1520.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFF88AABB.toInt(),
            stroke = 0x4000E5FF,
            onAccent = Color.BLACK,
            backgroundColor = 0xFF0A1218.toInt()
        ),
        Theme(
            "emerald", "Emerald",
            accent = 0xFF69F0AE.toInt(),
            accentSecondary = 0xFF00C853.toInt(),
            surface = 0xFF0A1410.toInt(),
            card = 0xFF0D1A14.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFFA0C8B0.toInt(),
            stroke = 0x4069F0AE,
            onAccent = Color.BLACK,
            backgroundColor = 0xFF0A1410.toInt()
        ),
        Theme(
            "amber", "Amber",
            accent = 0xFFFFD740.toInt(),
            accentSecondary = 0xFFFFAB00.toInt(),
            surface = 0xFF14120A.toInt(),
            card = 0xFF1A160D.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFFC8C0A0.toInt(),
            stroke = 0x40FFD740,
            onAccent = Color.BLACK,
            backgroundColor = 0xFF14120A.toInt()
        ),
        Theme(
            "high_contrast", "High contrast",
            accent = 0xFFFFFFFF.toInt(),
            accentSecondary = 0xFFEEEEEE.toInt(),
            surface = 0xFF000000.toInt(),
            card = 0xFF111111.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFFCCCCCC.toInt(),
            stroke = 0x88FFFFFF.toInt(),
            onAccent = Color.BLACK,
            backgroundColor = 0xFF000000.toInt()
        ),
        Theme(
            "red_alert", "Red alert",
            accent = 0xFFFF5252.toInt(),
            accentSecondary = 0xFFFF1744.toInt(),
            surface = 0xFF140A0A.toInt(),
            card = 0xFF1A0D0D.toInt(),
            textPrimary = Color.WHITE,
            textSecondary = 0xFFC8A0A0.toInt(),
            stroke = 0x40FF5252.toInt(),
            onAccent = Color.WHITE,
            backgroundColor = 0xFF140A0A.toInt()
        ),
        Theme(
            "dna_green", "DNA Green",
            accent = 0xFF4CAF50.toInt(),
            accentSecondary = 0xFF8BC34A.toInt(),
            surface = 0xFF0C1A10.toInt(),
            card = 0xE6122418.toInt(),
            textPrimary = 0xFFE8FFE8.toInt(),
            textSecondary = 0xFFA8D5A8.toInt(),
            stroke = 0x664CAF50,
            onAccent = Color.BLACK,
            backgroundDrawable = "green_background",
            backgroundAsset = "Green_background.jpg",
            backgroundColor = 0xFFA8D48A.toInt()
        ),
        Theme(
            "dna_red", "DNA Red",
            accent = 0xFFFF1744.toInt(),
            accentSecondary = 0xFFFF5252.toInt(),
            surface = 0xFF0A0000.toInt(),
            card = 0xE61A0808.toInt(),
            textPrimary = 0xFFFFF0F0.toInt(),
            textSecondary = 0xFFFFAAAA.toInt(),
            stroke = 0x66FF1744,
            onAccent = Color.WHITE,
            backgroundDrawable = "red_background",
            backgroundAsset = "Red_background.jpg",
            backgroundColor = 0xFF1A0000.toInt()
        ),
        Theme(
            "dna_blue", "DNA Blue",
            accent = 0xFF29B6F6.toInt(),
            accentSecondary = 0xFF0288D1.toInt(),
            surface = 0xFF001428.toInt(),
            card = 0xE6081828.toInt(),
            textPrimary = 0xFFE8F6FF.toInt(),
            textSecondary = 0xFF90CAF9.toInt(),
            stroke = 0x6629B6F6,
            onAccent = Color.BLACK,
            backgroundDrawable = "blue_background",
            backgroundAsset = "blue_background.jpg",
            backgroundColor = 0xFF0A2A4A.toInt()
        ),
        Theme(
            "dna_purple", "DNA Purple",
            accent = 0xFFB98CFF.toInt(),
            accentSecondary = 0xFFD946EF.toInt(),
            surface = 0xFF120A18.toInt(),
            card = 0xE61A1020.toInt(),
            textPrimary = 0xFFF5E8FF.toInt(),
            textSecondary = 0xFFC9A0E8.toInt(),
            stroke = 0x66B98CFF,
            onAccent = Color.BLACK,
            backgroundDrawable = "bg_dna_helix",
            backgroundAsset = "bg_dna_helix.webp",
            backgroundColor = 0xFF2A1040.toInt()
        )
    )

    fun currentId(context: Context = AppContext.app): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "violet") ?: "violet"

    fun current(context: Context = AppContext.app): Theme =
        THEMES.firstOrNull { it.id == currentId(context) } ?: THEMES.first()

    fun setTheme(context: Context, id: String) {
        if (THEMES.none { it.id == id }) return
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit { putString(KEY, id) }
        applyEverywhere(context)
    }

    fun cycle(context: Context = AppContext.app): Theme {
        val ids = THEMES.map { it.id }
        val idx = ids.indexOf(currentId(context)).let { if (it < 0) 0 else (it + 1) % ids.size }
        setTheme(context, ids[idx])
        return current(context)
    }

    fun applyToActivity(activity: Activity) {
        val t = current(activity)
        activity.window?.setBackgroundDrawable(ColorDrawable(t.backgroundColor))
        activity.window?.decorView?.setBackgroundColor(t.surface)

        val hero = activity.findViewById<ImageView?>(
            activity.resources.getIdentifier("ivHeroBg", "id", activity.packageName)
        )
        val gradient = activity.findViewById<ImageView?>(
            activity.resources.getIdentifier("ivGradientBg", "id", activity.packageName)
        )
        val content = activity.findViewById<View?>(
            activity.resources.getIdentifier("contentContainer", "id", activity.packageName)
        )

        val bmp = loadBackgroundBitmap(activity, t)
        if (bmp != null) {
            hero?.setImageBitmap(bmp)
            hero?.alpha = 1f
            hero?.scaleType = ImageView.ScaleType.CENTER_CROP
            hero?.visibility = View.VISIBLE
            hero?.setColorFilter(
                ColorUtils.setAlphaComponent(t.surface, 0x40),
                android.graphics.PorterDuff.Mode.SRC_ATOP
            )
            gradient?.setImageBitmap(bmp)
            gradient?.alpha = 0.35f
            gradient?.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val gd = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(t.backgroundColor, t.surface, ColorUtils.blendARGB(t.surface, t.accent, 0.15f))
            )
            hero?.setImageDrawable(gd)
            hero?.clearColorFilter()
            hero?.alpha = 1f
            gradient?.setImageDrawable(gd)
            gradient?.alpha = 0.5f
        }

        content?.setBackgroundColor(Color.TRANSPARENT)
        content?.let { restyleTree(it, t) }
        activity.findViewById<View?>(
            activity.resources.getIdentifier("tabLayout", "id", activity.packageName)
        )?.let { restyleTree(it, t) }
    }

    fun applyEverywhere(context: Context) {
        val act = (context as? Activity) ?: MainActivity.Instance
        if (act != null) {
            act.runOnUiThread {
                applyToActivity(act)
                runCatching { (act as? MainActivity)?.rebuildPresetsTheme() }
            }
        }
    }

    fun loadBackgroundBitmap(context: Context, theme: Theme = current(context)): android.graphics.Bitmap? {
        theme.backgroundDrawable?.let { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) {
                return runCatching {
                    BitmapFactory.decodeResource(context.resources, id)
                }.getOrNull()
            }
        }
        theme.backgroundAsset?.let { asset ->
            runCatching {
                context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return it }
            runCatching {
                context.assets.open(asset.lowercase()).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return it }
        }
        listOfNotNull(theme.backgroundAsset, theme.backgroundDrawable?.let { "$it.jpg" }).forEach { name ->
            val f = java.io.File(context.filesDir, name)
            if (f.exists()) {
                return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            }
        }
        return null
    }

    fun stylePrimaryButton(btn: Button, theme: Theme = current()) {
        val gd = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.accent, theme.accentSecondary)
        ).apply {
            cornerRadius = 14f * btn.resources.displayMetrics.density
        }
        btn.background = gd
        btn.setTextColor(theme.onAccent)
    }

    fun styleOutlineButton(btn: Button, theme: Theme = current()) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * btn.resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
            setStroke(
                (1.5f * btn.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                theme.accent
            )
        }
        btn.background = gd
        btn.setTextColor(theme.accent)
    }

    fun styleLabel(tv: TextView, primary: Boolean = true, theme: Theme = current()) {
        tv.setTextColor(if (primary) theme.textPrimary else theme.textSecondary)
    }

    private fun restyleTree(root: View, theme: Theme) {
        when (root) {
            is Button -> {
                val text = root.currentTextColor
                val bright = ColorUtils.calculateLuminance(text) > 0.5
                if (bright) styleOutlineButton(root, theme) else stylePrimaryButton(root, theme)
            }
            is TextView -> {
                val c = root.currentTextColor
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val neutral = kotlin.math.abs(r - g) < 30 && kotlin.math.abs(g - b) < 30
                if (neutral) {
                    val lum = ColorUtils.calculateLuminance(c)
                    root.setTextColor(if (lum > 0.6) theme.textPrimary else theme.textSecondary)
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                restyleTree(root.getChildAt(i), theme)
            }
        }
    }
}