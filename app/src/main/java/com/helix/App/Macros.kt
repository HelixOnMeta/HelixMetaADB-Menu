package com.helix

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object Macros {

    data class Macro(val name: String, val commands: List<String>)

    const val prefname = "adbconsole_macros"
    const val blob = "macros_blob"

    const val recordseperate = "\u001E"
    const val unitseperate = "\u001F"

    /** Master switch: run selected macros after boot / when MainActivity starts from boot. */
    private const val KEY_BOOT_ENABLED = "macros_run_on_boot"
    /** Comma-separated macro names to run on boot. Empty = run ALL macros. */
    private const val KEY_BOOT_NAMES = "macros_boot_names"

    fun loadAll(context: Context): List<Macro> {
        val data = prefs(context).getString(blob, "") ?: ""
        if (data.isEmpty()) return emptyList()
        return data.split(recordseperate)
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val parts = record.split(unitseperate, limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val name = parts[0]
                val commands = parts[1].split("\n").filter { it.isNotBlank() }
                if (name.isBlank() || commands.isEmpty()) null else Macro(name, commands)
            }
            .sortedBy { it.name.lowercase() }
    }

    fun save(context: Context, macro: Macro) {
        val existing = loadAll(context).filterNot { it.name == macro.name }
        writeAll(context, existing + macro)
    }

    fun delete(context: Context, name: String) {
        writeAll(context, loadAll(context).filterNot { it.name == name })
    }

    fun writeAll(context: Context, macros: List<Macro>) {
        val data = macros.joinToString(recordseperate) {
            "${it.name}$unitseperate${it.commands.joinToString("\n")}"
        }
        prefs(context).edit { putString(blob, data) }
    }

    fun prefs(context: Context) =
        context.getSharedPreferences(prefname, Context.MODE_PRIVATE)!!

    // ── Boot macros ─────────────────────────────────────────────────────────

    fun isBootEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_ENABLED, false)

    fun setBootEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_BOOT_ENABLED, enabled) }
    }

    /**
     * Names selected to run on boot.
     * Empty set means "all macros".
     */
    fun getBootMacroNames(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_BOOT_NAMES, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setBootMacroNames(context: Context, names: Set<String>) {
        prefs(context).edit {
            putString(KEY_BOOT_NAMES, names.joinToString(","))
        }
    }

    fun toggleBootMacro(context: Context, name: String, on: Boolean) {
        val cur = getBootMacroNames(context).toMutableSet()
        if (on) cur.add(name) else cur.remove(name)
        setBootMacroNames(context, cur)
    }

    fun isMacroSelectedForBoot(context: Context, name: String): Boolean {
        val selected = getBootMacroNames(context)
        // empty selection = all macros when master is on
        return selected.isEmpty() || name in selected
    }

    /** Macros that will actually run on next boot (respects master + selection). */
    fun macrosForBoot(context: Context): List<Macro> {
        if (!isBootEnabled(context)) return emptyList()
        val all = loadAll(context)
        val selected = getBootMacroNames(context)
        return if (selected.isEmpty()) all else all.filter { it.name in selected }
    }

    /**
     * Run boot macros using [ctx.run]. Safe to call from BootReceiver or MainActivity.
     * [delayBetweenMs] spaces commands so shell/ADB can keep up after boot.
     */
    fun runBootMacros(
        context: Context,
        ctx: Utils.ActionContext,
        scope: CoroutineScope,
        delayBetweenMs: Long = 400L,
        initialDelayMs: Long = 2_000L
    ) {
        val list = macrosForBoot(context)
        if (list.isEmpty()) {
            ctx.log("[BootMacros] none to run (toggle off or no macros)")
            return
        }
        scope.launch(Dispatchers.IO) {
            delay(initialDelayMs)
            ctx.log("[BootMacros] running ${list.size} macro(s)…")
            for (macro in list) {
                ctx.log("[BootMacros] > ${macro.name}")
                for (cmd in macro.commands) {
                    val trimmed = cmd.trim()
                    if (trimmed.isEmpty()) continue
                    if (trimmed.startsWith("@@OVR@@")) {
                        runCatching { Overlay.handleConfigCommand(ctx, trimmed) }
                        continue
                    }
                    val out = runCatching { ctx.run(trimmed) }.getOrElse { "[error] ${it.message}" }
                    ctx.log("$ $trimmed\n${out.ifBlank { "(no output)" }}")
                    delay(delayBetweenMs)
                }
            }
            ctx.log("[BootMacros] done")
            ctx.toast("Boot macros finished (${list.size})")
        }
    }
}
