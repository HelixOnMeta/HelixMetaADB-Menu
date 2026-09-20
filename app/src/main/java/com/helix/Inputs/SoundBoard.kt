package com.helix

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// contact blaku64th on discord if you have any issues ^^
object SoundBoard {
    private const val TAG = "SoundBoard"
    private const val PREF = "helix_soundboard"
    private const val BLOB = "entries"

    data class Clip(
        val id: String,
        val name: String,
        val path: String,
        val sourceUrl: String = ""
    )

    private var currentPlayer: MediaPlayer? = null

    private fun prefs(ctx: Context = AppContext.app) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)!!

    private fun dir(ctx: Context = AppContext.app): File =
        File(ctx.filesDir, "soundboard").also { it.mkdirs() }

    fun loadAll(context: Context = AppContext.app): List<Clip> {
        val raw = prefs(context).getString(BLOB, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\u001E').mapNotNull { line ->
            val p = line.split('\u001F')
            if (p.size < 3) null
            else Clip(p[0], p[1], p[2], p.getOrElse(3) { "" })
        }.filter { File(it.path).isFile }
    }

    private fun saveAll(context: Context, clips: List<Clip>) {
        val blob = clips.joinToString("\u001E") {
            "${it.id}\u001F${it.name}\u001F${it.path}\u001F${it.sourceUrl}"
        }
        prefs(context).edit { putString(BLOB, blob) }
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.let { File(it.path).delete() }
        saveAll(context, all.filterNot { it.id == id })
    }

    suspend fun downloadFromUrl(
        ctx: Utils.ActionContext,
        url: String,
        displayName: String? = null
    ): Clip? = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            ctx.log("Invalid URL")
            ctx.toast("Invalid URL")
            return@withContext null
        }
        try {
            val conn = URL(clean).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "HelixSoundBoard/1.0")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                ctx.log("HTTP ${conn.responseCode}")
                ctx.toast("Download failed")
                return@withContext null
            }
            val ext = when {
                clean.contains(".ogg", true) -> "ogg"
                clean.contains(".wav", true) -> "wav"
                clean.contains(".m4a", true) -> "m4a"
                clean.contains(".aac", true) -> "aac"
                else -> "mp3"
            }
            val id = UUID.randomUUID().toString().take(8)
            val name = displayName?.ifBlank { null }
                ?: clean.substringAfterLast('/').substringBefore('?').ifBlank { "clip_$id" }
            val out = File(dir(), "$id.$ext")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            if (out.length() < 64) {
                out.delete()
                ctx.log("File too small")
                return@withContext null
            }
            val clip = Clip(id, name, out.absolutePath, clean)
            val all = loadAll().filterNot { it.id == id } + clip
            saveAll(AppContext.app, all)
            ctx.log("Saved soundboard clip \"$name\" (${out.length()} bytes)")
            ctx.toast("Saved $name")
            clip
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            ctx.log("Download error: ${e.message}")
            ctx.toast("Download failed")
            null
        }
    }

    fun play(ctx: Utils.ActionContext, id: String) {
        val clip = loadAll().firstOrNull { it.id == id }
        if (clip == null) {
            ctx.toast("Clip missing")
            return
        }
        stopPlayback()
        try {
            currentPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(clip.path)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepare()
                start()
            }
            ctx.log("Playing \"${clip.name}\"")
        } catch (e: Exception) {
            ctx.log("Play failed: ${e.message}")
            ctx.toast("Play failed")
            stopPlayback()
        }
    }

    fun playByName(ctx: Utils.ActionContext, name: String) {
        val clip = loadAll().firstOrNull { it.name.equals(name, true) } ?: run {
            ctx.toast("Not found: $name")
            return
        }
        play(ctx, clip.id)
    }

    fun stopPlayback() {
        runCatching {
            currentPlayer?.stop()
            currentPlayer?.release()
        }
        currentPlayer = null
    }

    fun listSummary(): String {
        val all = loadAll()
        if (all.isEmpty()) return "Soundboard empty — add clips via URL"
        return all.joinToString("\n") { "• ${it.name} (${File(it.path).length() / 1024} KB)" }
    }
}
