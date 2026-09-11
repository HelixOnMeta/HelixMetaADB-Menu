package com.helix

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
class PresetsController(
    val context: Context,
    val container: LinearLayout,
    val scope: CoroutineScope,
    val ctx: Utils.ActionContext
) {

    val dp = context.resources.displayMetrics.density

    lateinit var categoryTabs: TabLayout
    lateinit var categoryContent: LinearLayout
    lateinit var macrosListContainer: LinearLayout
    private var statusStrip: TextView? = null

    /** Current section body that new controls are added into (null = top level). */
    private var currentSectionBody: LinearLayout? = null

    /** Remember expand/collapse per section title across re-renders of the same category. */
    private val sectionExpanded = mutableMapOf<String, Boolean>()

    val categories = Utils.Category.entries.toTypedArray()
    var currentCategory: Utils.Category = categories.first()

    private fun c(resId: Int) = ContextCompat.getColor(context, resId)
    private val colorAccent by lazy { runCatching { c(R.color.accent) }.getOrDefault(0xFFB98CFF.toInt()) }
    private val colorOnAccent by lazy { runCatching { c(R.color.on_accent) }.getOrDefault(Color.BLACK) }
    private val colorTextPrimary by lazy { runCatching { c(R.color.text_primary) }.getOrDefault(Color.WHITE) }
    private val colorTextSecondary by lazy { runCatching { c(R.color.text_secondary) }.getOrDefault(0xFFAAAAAA.toInt()) }
    private val colorTextMuted by lazy { runCatching { c(R.color.text_muted) }.getOrDefault(0xFF6A6A70.toInt()) }
    private val colorStatusError by lazy { runCatching { c(R.color.status_error) }.getOrDefault(0xFFFF6B6B.toInt()) }
    private val colorSurface by lazy { runCatching { c(R.color.surface) }.getOrDefault(0xFF121218.toInt()) }
    private val colorCard by lazy { 0xFF12101A.toInt() }
    private val colorStroke by lazy { 0x40B98CFF }
    private val colorAccentSecondary by lazy { runCatching { c(R.color.accent_secondary) }.getOrDefault(0xFFD946EF.toInt()) }

    fun build() {
        container.removeAllViews()
        container.setPadding(px(12), px(8), px(12), px(12))

        statusStrip = TextView(context).apply {
            text = "mods: idle"
            setTextColor(colorAccent)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(px(14), px(10), px(14), px(10))
            background = rounded(colorCard, 14f, colorStroke, 1f)
            letterSpacing = 0.02f
        }
        container.addView(statusStrip, matchWidthParams().apply {
            bottomMargin = px(10)
        })
        startStatusTicker()

        categoryTabs = TabLayout(context).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
            setBackgroundColor(Color.TRANSPARENT)
            setSelectedTabIndicatorColor(colorAccent)
            setTabTextColors(colorTextSecondary, colorTextPrimary)
            setPadding(0, px(2), 0, px(2))
            elevation = 0f
        }

        val tabsCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colorCard, 16f, colorStroke, 1f)
            setPadding(px(6), px(4), px(6), px(4))
            addView(categoryTabs, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        container.addView(tabsCard, matchWidthParams().apply {
            bottomMargin = px(8)
        })

        categories.forEach { cat ->
            categoryTabs.addTab(categoryTabs.newTab().setText(displayName(cat)))
        }
        categoryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentCategory = categories[tab.position]
                renderCategory(currentCategory)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        categoryContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(4), 0, 0)
        }
        container.addView(categoryContent, matchWidthParams())

        renderCategory(currentCategory)
    }

    private fun startStatusTicker() {
        scope.launch(Dispatchers.Main) {
            while (true) {
                try {
                    val move = MovementMods.statusLine()
                    val scale = if (IPDADB.isActive) IPDADB.scaleStatus() else "scale: off"
                    val cam = if (CameraMods.anyArmed) "cam/freeze armed" else "cam: off"
                    statusStrip?.text = "$move  ·  $scale  ·  $cam"
                } catch (_: Throwable) {
                }
                delay(400)
            }
        }
    }

    fun displayName(category: Utils.Category): String = when (category) {
        Utils.Category.MODS -> "Mods"
        Utils.Category.VISUALS -> "Visuals"
        Utils.Category.HARDWARE -> "Hardware"
        Utils.Category.SETTINGS -> "Settings"
        Utils.Category.RECORDING -> "Recording"
        Utils.Category.MISC -> "Misc"
        Utils.Category.OFFLINE -> "Offline"
        Utils.Category.POWER -> "Power"
    }

    fun renderCategory(category: Utils.Category) {
        categoryContent.removeAllViews()
        currentSectionBody = null

        val buttons = Utils.Buttons.filter { it.category == category }
        val toggles = Utils.Toggles.filter { it.category == category }
        val customButtons = Catalog.mergedCustomButtons().filter { it.category == category }
        val customToggles = Catalog.mergedCustomToggles().filter { it.category == category }
        val sliders = Catalog.mergedSliders().filter { it.category == category }
        val customSliders = Catalog.mergedCustomSliders().filter { it.category == category }
        val isMisc = category == Utils.Category.MISC

        when (category) {
            Utils.Category.MODS -> renderModsGrouped(buttons, toggles, customButtons, customToggles, sliders)
            Utils.Category.HARDWARE -> renderHardwareGrouped(buttons, toggles, customButtons, customToggles, sliders)
            Utils.Category.VISUALS -> renderVisualsGrouped(buttons, toggles, customButtons, customToggles, sliders)
            Utils.Category.SETTINGS -> renderSettingsGrouped(buttons, toggles, customButtons, customToggles, sliders)
            Utils.Category.OFFLINE -> renderOfflineGrouped(buttons, toggles, customButtons, customToggles, sliders)
            else -> renderDefaultGrouped(buttons, toggles, customButtons, customToggles, sliders, isMisc)
        }

        if (customSliders.isNotEmpty()) {
            addHeader("Custom Sliders")
            customSliders.forEach { addCustomSlider(it) }
        }

        Anim.staggerIn(categoryContent)
    }

    // ─── Shared empty / macros helpers ─────────────────────────────────────

    private fun maybeEmpty(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>,
        extra: Boolean = false
    ) {
        val empty = buttons.isEmpty() && toggles.isEmpty() && customButtons.isEmpty() &&
                customToggles.isEmpty() && sliders.isEmpty() && !extra
        if (empty) {
            val tv = TextView(context).apply {
                text = "Nothing in this tab yet."
                setTextColor(colorTextSecondary)
                textSize = 12f
                setPadding(0, px(6), 0, px(6))
            }
            addToCurrent(tv)
        }
    }

    private fun addMacrosSection() {
        addHeader("Macros")
        addMacroCreator()
        macrosListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addToCurrent(macrosListContainer, matchWidthParams())
        refreshMacroList()
    }

    /** Match label against any keyword (case-insensitive). */
    private fun labelMatches(label: String, vararg keys: String): Boolean {
        val l = label.lowercase()
        return keys.any { l.contains(it.lowercase()) }
    }

    private fun renderModsGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>
    ) {
        addHint("Hold RT / LT / grips as labeled. ADB must be connected. Face buttons need OpenXR on many builds.")

        data class Bucket(val title: String, val pred: (String) -> Boolean)
        // Order matters: first matching bucket wins
        val buckets = listOf(
            Bucket("Fly") { labelMatches(it, "joystick fly", "a-button fly", "velocity fly", "hover fly", "hover", "glide", "rocket", "orbit", "surf", "fly speed", "fly accel", "fly smooth") },
            Bucket("Arms & body") { labelMatches(it, "long arms", "break hands", "tiny", "giant", "taller", "shorter", "side shift", "ipd", "scale", "arm") },
            Bucket("Camera") { labelMatches(it, "cam", "camera", "spectate", "freeze", "ghost", "anchor", "third", "shoulder", "top down", "low angle") },
            Bucket("Rotation") { labelMatches(it, "spin head", "upside", "backwards", "head spin", "rotation") },
            Bucket("Movement") { labelMatches(it, "wall", "walk", "up/down", "swim", "spaz", "grapple", "platform", "gravity", "dash", "checkpoint", "recall", "pose", "strafe", "forward", "fall", "brake", "climb", "zig", "bunny", "disarm", "blink", "jump") },
            Bucket("Input holds") { labelMatches(it, "hold ", "finger spaz", "grip spaz", "mash face", "no finger", "stop all input", "find inputs") },
            Bucket("Utility") { labelMatches(it, "overlay", "psa", "input", "recenter") }
        )

        fun placeToggles(list: List<Utils.CustomToggleAction>) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(it.label) && it.label !in used }
                if (hit.isNotEmpty()) {
                    addHeader(b.title)
                    hit.forEach {
                        used += it.label
                        addCustomToggle(it)
                    }
                }
            }
            val rest = list.filter { it.label !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other mods")
                rest.forEach { addCustomToggle(it) }
            }
        }

        fun placeButtons(list: List<Utils.CustomButtonAction>) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(it.label) && it.label !in used }
                if (hit.isNotEmpty()) {
                    addHeader("${b.title} · actions")
                    hit.forEach {
                        used += it.label
                        addCustomButton(it)
                    }
                }
            }
            val rest = list.filter { it.label !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other actions")
                rest.forEach { addCustomButton(it) }
            }
        }

        fun placeSliders(list: List<Utils.SliderAction>) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(it.label) && it.label !in used }
                if (hit.isNotEmpty()) {
                    addHeader("${b.title} · sliders")
                    hit.forEach {
                        used += it.label
                        addSlider(it)
                    }
                }
            }
            val rest = list.filter { it.label !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other sliders")
                rest.forEach { addSlider(it) }
            }
        }

        if (customToggles.isNotEmpty()) placeToggles(customToggles)
        if (customButtons.isNotEmpty()) placeButtons(customButtons)
        if (toggles.isNotEmpty()) {
            addHeader("Quick toggles")
            toggles.forEach { addToggle(it) }
        }
        if (buttons.isNotEmpty()) {
            addHeader("Quick buttons")
            buttons.forEach { addButton(it) }
        }
        if (sliders.isNotEmpty()) placeSliders(sliders)
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders)
    }

    // ─── HARDWARE ──────────────────────────────────────────────────────────

    private fun renderHardwareGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>
    ) {
        data class Bucket(val title: String, val pred: (String) -> Boolean)
        val buckets = listOf(
            Bucket("LEDs") { labelMatches(it, "led", "rgb") },
            Bucket("Root & bootloader") { labelMatches(it, "root", "magisk", "bl unlock", "bootloader", "system app", "ion", "v79") },
            Bucket("Performance") { labelMatches(it, "cpu", "gpu", "fps", "overclock", "performance", "thermal", "divide", "governor") },
            Bucket("Power & battery") { labelMatches(it, "battery", "power", "dont turn", "timeout", "saver", "stay on") },
            Bucket("Panels") { labelMatches(it, "dogfood", "gauntlet", "toast", "debug panel", "launch") },
            Bucket("Device") { labelMatches(it, "reboot", "boot", "sensor", "serial", "boot anim") }
        )

        fun <T> placeByLabel(
            list: List<T>,
            labelOf: (T) -> String,
            headerSuffix: String,
            render: (T) -> Unit
        ) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(labelOf(it)) && labelOf(it) !in used }
                if (hit.isNotEmpty()) {
                    addHeader(if (headerSuffix.isEmpty()) b.title else "${b.title} · $headerSuffix")
                    hit.forEach {
                        used += labelOf(it)
                        render(it)
                    }
                }
            }
            val rest = list.filter { labelOf(it) !in used }
            if (rest.isNotEmpty()) {
                addHeader(if (headerSuffix.isEmpty()) "Other" else "Other · $headerSuffix")
                rest.forEach { render(it) }
            }
        }

        if (customToggles.isNotEmpty()) {
            placeByLabel(customToggles, { it.label }, "", ::addCustomToggle)
        }
        if (customButtons.isNotEmpty()) {
            placeByLabel(customButtons, { it.label }, "actions", ::addCustomButton)
        }
        if (buttons.isNotEmpty()) {
            placeByLabel(buttons, { it.label }, "buttons", ::addButton)
        }
        if (toggles.isNotEmpty()) {
            placeByLabel(toggles, { it.label }, "toggles", ::addToggle)
        }
        if (sliders.isNotEmpty()) {
            placeByLabel(sliders, { it.label }, "sliders", ::addSlider)
        }
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders)
    }

    // ─── VISUALS ───────────────────────────────────────────────────────────

    private fun renderVisualsGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>
    ) {
        data class Bucket(val title: String, val pred: (String) -> Boolean)
        val buckets = listOf(
            Bucket("Overlay / metrics") { labelMatches(it, "ovr", "overlay", "metric", "mini") },
            Bucket("Guardian & world") { labelMatches(it, "guardian", "chroma", "space", "motion") },
            Bucket("Graphics quality") { labelMatches(it, "texture", "msaa", "anisotropy", "foveation", "compression", "graphics", "ada", "phase") }
        )

        fun <T> place(
            list: List<T>,
            labelOf: (T) -> String,
            suffix: String,
            render: (T) -> Unit
        ) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(labelOf(it)) && labelOf(it) !in used }
                if (hit.isNotEmpty()) {
                    addHeader(if (suffix.isEmpty()) b.title else b.title)
                    hit.forEach { used += labelOf(it); render(it) }
                }
            }
            val rest = list.filter { labelOf(it) !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other")
                rest.forEach { render(it) }
            }
        }

        if (customToggles.isNotEmpty()) place(customToggles, { it.label }, "", ::addCustomToggle)
        if (toggles.isNotEmpty()) place(toggles, { it.label }, "", ::addToggle)
        if (customButtons.isNotEmpty()) place(customButtons, { it.label }, "", ::addCustomButton)
        if (buttons.isNotEmpty()) place(buttons, { it.label }, "", ::addButton)
        if (sliders.isNotEmpty()) {
            addHeader("Sliders")
            sliders.forEach { addSlider(it) }
        }
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders)
    }

    // ─── SETTINGS ──────────────────────────────────────────────────────────

    private fun renderSettingsGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>
    ) {
        data class Bucket(val title: String, val pred: (String) -> Boolean)
        val buckets = listOf(
            Bucket("Updates & OTA") { labelMatches(it, "update", "ota", "blocker") },
            Bucket("Privacy / telemetry") { labelMatches(it, "telemetry", "hosts", "privacy") },
            Bucket("ADB & developer") { labelMatches(it, "adb", "auth", "developer", "build type", "spoof", "wireless debug") },
            Bucket("Display & power") { labelMatches(it, "screen", "stay on", "brightness", "timeout", "wifi", "dnd", "do not disturb") },
            Bucket("Macros / boot") { labelMatches(it, "macro", "boot") }
        )

        fun <T> place(
            list: List<T>,
            labelOf: (T) -> String,
            render: (T) -> Unit
        ) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(labelOf(it)) && labelOf(it) !in used }
                if (hit.isNotEmpty()) {
                    addHeader(b.title)
                    hit.forEach { used += labelOf(it); render(it) }
                }
            }
            val rest = list.filter { labelOf(it) !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other")
                rest.forEach { render(it) }
            }
        }

        if (customToggles.isNotEmpty()) place(customToggles, { it.label }, ::addCustomToggle)
        if (customButtons.isNotEmpty()) place(customButtons, { it.label }, ::addCustomButton)
        if (buttons.isNotEmpty()) place(buttons, { it.label }, ::addButton)
        if (toggles.isNotEmpty()) place(toggles, { it.label }, ::addToggle)
        if (sliders.isNotEmpty()) {
            addHeader("Sliders")
            sliders.forEach { addSlider(it) }
        }
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders)
    }

    // ─── Default (Recording / Misc) ────────────────────────────────────────


    // ─── OFFLINE (no ADB) ──────────────────────────────────────────────────

    private fun renderOfflineGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>
    ) {
        addHint("These work without ADB on the device running Helix. Brightness/timeout need Modify system settings.")

        data class Bucket(val title: String, val pred: (String) -> Boolean)
        val buckets = listOf(
            Bucket("Battery") { labelMatches(it, "battery", "local battery") },
            Bucket("Display") { labelMatches(it, "brightness", "timeout", "stay on", "font scale") },
            Bucket("Audio") { labelMatches(it, "volume", "notification", "media") },
            Bucket("Permissions") { labelMatches(it, "grant", "write settings", "overlay", "permission", "optimization") },
            Bucket("Shortcuts") { labelMatches(it, "open", "wifi", "wireless", "settings", "app details", "files", "location", "alarm", "date") }
        )

        fun <T> place(list: List<T>, labelOf: (T) -> String, render: (T) -> Unit) {
            val used = mutableSetOf<String>()
            for (b in buckets) {
                val hit = list.filter { b.pred(labelOf(it)) && labelOf(it) !in used }
                if (hit.isNotEmpty()) {
                    addHeader(b.title)
                    hit.forEach { used += labelOf(it); render(it) }
                }
            }
            val rest = list.filter { labelOf(it) !in used }
            if (rest.isNotEmpty()) {
                addHeader("Other")
                rest.forEach { render(it) }
            }
        }

        if (customToggles.isNotEmpty()) place(customToggles, { it.label }, ::addCustomToggle)
        if (customButtons.isNotEmpty()) place(customButtons, { it.label }, ::addCustomButton)
        if (toggles.isNotEmpty()) place(toggles, { it.label }, ::addToggle)
        if (buttons.isNotEmpty()) place(buttons, { it.label }, ::addButton)
        if (sliders.isNotEmpty()) {
            addHeader("Sliders")
            sliders.forEach { addSlider(it) }
        }
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders)
    }

    private fun renderDefaultGrouped(
        buttons: List<Utils.ButtonAction>,
        toggles: List<Utils.ToggleAction>,
        customButtons: List<Utils.CustomButtonAction>,
        customToggles: List<Utils.CustomToggleAction>,
        sliders: List<Utils.SliderAction>,
        isMisc: Boolean
    ) {
        if (buttons.isNotEmpty()) {
            addHeader("Buttons")
            buttons.forEach { addButton(it) }
        }
        if (toggles.isNotEmpty()) {
            addHeader("Toggles")
            toggles.forEach { addToggle(it) }
        }
        if (customButtons.isNotEmpty()) {
            addHeader("Actions")
            customButtons.forEach { addCustomButton(it) }
        }
        if (customToggles.isNotEmpty()) {
            addHeader("Toggles")
            customToggles.forEach { addCustomToggle(it) }
        }
        if (sliders.isNotEmpty()) {
            addHeader("Sliders")
            sliders.forEach { addSlider(it) }
        }
        if (isMisc) addMacrosSection()
        maybeEmpty(buttons, toggles, customButtons, customToggles, sliders, isMisc)
    }

    fun px(v: Int) = (v * dp).toInt()

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * dp
            setColor(fill)
            if (strokeDp > 0f) setStroke((strokeDp * dp).toInt().coerceAtLeast(1), stroke)
        }
    }

    private fun addHint(text: String) {
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(colorTextSecondary)
            textSize = 12f
            setPadding(px(14), px(12), px(14), px(12))
            background = rounded(0x228B5CF6, 12f)
            setLineSpacing(0f, 1.15f)
        }
        // Hints sit outside collapsible sections
        currentSectionBody = null
        categoryContent.addView(tv, matchWidthParams().apply {
            bottomMargin = px(6)
        })
    }

    /**
     * Collapsible section header.
     * - Tap to expand / collapse
     * - State remembered while you stay on the same category
     * - Chevron: ▼ expanded, ▶ collapsed
     */
    fun addHeader(text: String, startExpanded: Boolean = true) {
        val key = "${currentCategory.name}::$text"
        val expanded = sectionExpanded.getOrPut(key) { startExpanded }

        val chevron = TextView(context).apply {
            this.text = if (expanded) "▼" else "▶"
            setTextColor(colorAccent)
            textSize = 11f
            setPadding(0, 0, px(10), 0)
        }

        val title = TextView(context).apply {
            this.text = text
            setTextColor(colorAccent)
            textSize = 12f
            letterSpacing = 0.12f
            isAllCaps = true
            typeface = Typeface.DEFAULT_BOLD
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(10), px(10), px(10))
            isClickable = true
            isFocusable = true
            background = rounded(0x2212101A, 12f, colorStroke, 1f)
            addView(chevron)
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            // Slight inset so items sit under the header as one group
            setPadding(px(4), px(2), px(4), px(6))
            background = rounded(0x1012101A, 0f)
        }

        headerRow.setOnClickListener {
            val nowExpanded = body.visibility != View.VISIBLE
            body.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            chevron.text = if (nowExpanded) "▼" else "▶"
            sectionExpanded[key] = nowExpanded
        }

        categoryContent.addView(headerRow, matchWidthParams().apply {
            topMargin = px(8)
            bottomMargin = 0
        })
        categoryContent.addView(body, matchWidthParams().apply {
            topMargin = 0
            bottomMargin = px(8)
        })

        currentSectionBody = body
    }

    /** Add a view into the current collapsible section (or top-level if none). */
    private fun addToCurrent(view: View, params: LinearLayout.LayoutParams = matchWidthParams()) {
        val target = currentSectionBody ?: categoryContent
        target.addView(view, params)
    }

    fun styleFilledButton(btn: Button) {
        btn.background = gradientRounded(
            start = colorAccent,
            end = colorAccentSecondary,
            radiusDp = 14f
        )
        btn.setTextColor(colorOnAccent)
        btn.isAllCaps = false
        btn.textSize = 13.5f
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.stateListAnimator = null
        btn.elevation = 0f
        btn.setPadding(px(18), px(14), px(18), px(14))
        Anim.bindPressFeedback(btn)
    }

    fun styleOutlineButton(btn: Button, tint: Int = colorAccent) {
        btn.background = gradientOutline(
            start = 0x220D0716.toInt(),
            end = (0x33000000 or (tint and 0x00FFFFFF)),
            strokeColor = tint,
            radiusDp = 14f
        )
        btn.setTextColor(tint)
        btn.isAllCaps = false
        btn.textSize = 13.5f
        btn.stateListAnimator = null
        btn.elevation = 0f
        btn.setPadding(px(18), px(14), px(18), px(14))
        Anim.bindPressFeedback(btn)
    }

    private fun cardRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(14), px(14), px(14))
            background = rounded(colorCard, 14f, colorStroke, 1f)
        }
    }

    private fun gradientRounded(start: Int, end: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = radiusDp * dp
            shape = GradientDrawable.RECTANGLE
        }
    }

    private fun gradientOutline(start: Int, end: Int, strokeColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = radiusDp * dp
            shape = GradientDrawable.RECTANGLE
            setStroke((1.3f * dp).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun addButton(action: Utils.ButtonAction) {
        val btn = Button(context).apply {
            text = action.label
            setOnClickListener { runSequential(action.label, action.commands) }
        }
        styleFilledButton(btn)
        addToCurrent(btn, matchWidthParams())
    }

    fun addCustomButton(action: Utils.CustomButtonAction) {
        val btn = Button(context).apply {
            text = action.label
            setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    ctx.log("> ${action.label}")
                    runCatching { action.action.run(ctx) }
                        .onFailure { e -> ctx.log("[error] ${e.message}") }
                }
            }
        }
        styleOutlineButton(btn)
        addToCurrent(btn, matchWidthParams())
    }

    fun addToggle(action: Utils.ToggleAction) {
        var initializing = true
        val row = toggleRow(action.label) { isChecked ->
            if (initializing) return@toggleRow
            val cmd = if (isChecked) action.onCommand else action.offCommand
            runSequential(action.label, listOf(cmd))
        }
        addToCurrent(row, matchWidthParams())
        initializing = false
    }

    fun addCustomToggle(action: Utils.CustomToggleAction) {
        var initializing = true
        val row = toggleRow(action.label) { isChecked ->
            if (initializing) return@toggleRow
            val custom = if (isChecked) action.onEnabled else action.onDisabled
            scope.launch(Dispatchers.IO) {
                ctx.log("> ${action.label} -> ${if (isChecked) "ON" else "OFF"}")
                runCatching { custom.run(ctx) }
                    .onFailure { e -> ctx.log("[error] ${e.message}") }
            }
        }
        addToCurrent(row, matchWidthParams())
        initializing = false
    }

    fun toggleRow(label: String, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = cardRow()
        val tv = TextView(context).apply {
            text = label
            setTextColor(colorTextPrimary)
            textSize = 13.5f
            setPadding(0, 0, px(8), 0)
        }
        val sw = Switch(context).apply {
            isChecked = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(colorAccent, colorTextMuted)
            )
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(colorAccent, 0xFF2A2A32.toInt())
            )
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return row
    }

    fun addCustomSlider(action: Utils.CustomSliderAction) {
        val step = action.step ?: 1f
        val range = action.max - action.min
        val stepCount = if (step > 0f) (range / step).toInt().coerceAtLeast(1) else range.coerceAtLeast(1)

        fun indexToValue(index: Int): Float = action.min + (index * step)
        fun formatValue(value: Float): String =
            if (step % 1f == 0f) value.toInt().toString() else "%.2f".format(value)

        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(14))
            background = rounded(colorCard, 14f, colorStroke, 1f)
        }
        val label = TextView(context).apply {
            text = "${action.label}: ${formatValue(action.initial.toFloat())}"
            setTextColor(colorTextPrimary)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, px(4))
        }
        val initialIndex = if (step > 0f) {
            ((action.initial - action.min) / step).toInt()
        } else {
            action.initial - action.min
        }
        val seek = SeekBar(context).apply {
            max = stepCount
            progress = initialIndex.coerceIn(0, stepCount)
            progressTintList = ColorStateList.valueOf(colorAccent)
            thumbTintList = ColorStateList.valueOf(colorAccent)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${action.label}: ${formatValue(indexToValue(progress))}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = indexToValue(sb?.progress ?: 0)
                scope.launch(Dispatchers.IO) {
                    runCatching { action.onChange.onChange(ctx, value) }
                        .onFailure { ctx.log("[error] ${it.message}") }
                }
            }
        })
        block.addView(label)
        block.addView(seek)
        addToCurrent(block, matchWidthParams())
    }

    fun addSlider(action: Utils.SliderAction) {
        val step = action.step ?: 1f
        val range = action.max - action.min
        val stepCount = if (step > 0f) (range / step).toInt() else range

        fun indexToValue(index: Int): Float = action.min + (index * step)
        fun formatValue(value: Float): String =
            if (step % 1f == 0f) value.toInt().toString() else "%.2f".format(value)

        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(14))
            background = rounded(colorCard, 14f, colorStroke, 1f)
        }
        val label = TextView(context).apply {
            text = "${action.label}: ${formatValue(action.initial.toFloat())}"
            setTextColor(colorTextPrimary)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, px(4))
        }
        val initialIndex = if (step > 0f) {
            ((action.initial - action.min) / step).toInt()
        } else {
            action.initial - action.min
        }
        val seek = SeekBar(context).apply {
            max = stepCount
            progress = initialIndex.coerceIn(0, stepCount)
            progressTintList = ColorStateList.valueOf(colorAccent)
            thumbTintList = ColorStateList.valueOf(colorAccent)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${action.label}: ${formatValue(indexToValue(progress))}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = indexToValue(sb?.progress ?: 0)
                val cmd = action.commandTemplate.replace("%VALUE%", formatValue(value))
                runSequential(action.label, listOf(cmd))
            }
        })
        block.addView(label)
        block.addView(seek)
        addToCurrent(block, matchWidthParams())
    }

    fun styleInput(et: EditText) {
        et.background = rounded(colorCard, 14f, colorStroke, 1f)
        et.setTextColor(colorTextPrimary)
        et.setHintTextColor(colorTextMuted)
        et.setPadding(px(16), px(14), px(16), px(14))
        et.textSize = 13.5f
    }

    fun addMacroCreator() {
        val nameEt = EditText(context).apply { hint = "Macro name" }
        styleInput(nameEt)

        val cmdsEt = EditText(context).apply {
            hint = "ADB commands, one per line (no 'adb shell' prefix)"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
        }
        styleInput(cmdsEt)

        val saveBtn = Button(context).apply { text = "Save Macro" }
        styleFilledButton(saveBtn)

        saveBtn.setOnClickListener {
            val name = nameEt.text.toString().trim()
            val commands = cmdsEt.text.toString()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (name.isEmpty() || commands.isEmpty()) {
                ctx.toast("Enter a macro name and at least one command")
                return@setOnClickListener
            }

            Macros.save(context, Macros.Macro(name, commands))
            ctx.log("Saved macro \"$name\" (${commands.size} command${if (commands.size == 1) "" else "s"})")
            ctx.toast("Saved \"$name\"")
            nameEt.text.clear()
            cmdsEt.text.clear()
            refreshMacroList()
        }

        addToCurrent(nameEt, matchWidthParams())
        addToCurrent(cmdsEt, matchWidthParams())
        addToCurrent(saveBtn, matchWidthParams())
    }

    fun refreshMacroList() {
        if (!::macrosListContainer.isInitialized) return
        macrosListContainer.removeAllViews()
        val macros = Macros.loadAll(context)

        if (macros.isEmpty()) {
            val tv = TextView(context).apply {
                text = "No macros saved yet."
                setTextColor(colorTextSecondary)
                textSize = 12f
                setPadding(0, px(6), 0, px(6))
            }
            macrosListContainer.addView(tv)
            return
        }

        macros.forEach { macro -> macrosListContainer.addView(buildMacroRow(macro)) }
    }

    fun buildMacroRow(macro: Macros.Macro): LinearLayout {
        val row = cardRow()
        val tv = TextView(context).apply {
            text = "${macro.name} (${macro.commands.size} cmd${if (macro.commands.size == 1) "" else "s"})"
            setTextColor(colorTextPrimary)
            textSize = 13.5f
        }
        val runBtn = Button(context).apply {
            text = "Run"
            setOnClickListener { runSequential(macro.name, macro.commands) }
        }
        styleOutlineButton(runBtn)

        val delBtn = Button(context).apply {
            text = "Delete"
            setOnClickListener {
                Macros.delete(context, macro.name)
                ctx.log("Deleted macro \"${macro.name}\"")
                refreshMacroList()
            }
        }
        styleOutlineButton(delBtn, tint = colorStatusError)

        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(runBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = px(6) })
        row.addView(delBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = px(6) })
        return row
    }

    fun matchWidthParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px(4); bottomMargin = px(6) }

    fun runSequential(label: String, commands: List<String>) {
        scope.launch(Dispatchers.IO) {
            ctx.log("> $label")
            for (cmd in commands) {
                if (cmd.trimStart().startsWith("@@OVR@@")) {
                    val adjusted = if (cmd.contains(" distance ")) {
                        val parts = cmd.trim().split(Regex("\\s+"))
                        val raw = parts.lastOrNull()?.toFloatOrNull()
                        if (raw != null && !cmd.contains(".")) {
                            "@@OVR@@ distance ${raw / 10f}"
                        } else cmd
                    } else cmd
                    val ok = runCatching { Overlay.handleConfigCommand(ctx, adjusted) }.getOrDefault(false)

                    continue
                }
                val output = runCatching { ctx.run(cmd) }.getOrElse { "[error] ${it.message}" }
                ctx.log("$ $cmd\n${output.ifBlank { "(no output)" }}")
            }
        }
    }
}