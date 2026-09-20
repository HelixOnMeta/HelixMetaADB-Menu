package com.helix

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.util.Log

// contact blaku64th on discord if you have any issues ^^
object MicControl {
    private const val TAG = "MicControl"

    @Volatile
    var muted: Boolean = false
        private set

    private var focusListener: OnAudioFocusChangeListener? = null

    fun isMuted(context: Context = AppContext.app): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute || muted
        } catch (_: Exception) {
            muted
        }
    }

    fun mute(ctx: Utils.ActionContext, on: Boolean) {
        val context = AppContext.app
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.isMicrophoneMute = on
        } catch (e: Exception) {
            Log.w(TAG, "isMicrophoneMute failed: ${e.message}")
        }

        if (on) {
            if (focusListener == null) {
                focusListener = OnAudioFocusChangeListener { }
            }
            runCatching {
                am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } else {
            runCatching { am.abandonAudioFocus(focusListener) }
            focusListener = null
        }

        if (RootHelper.hasRoot(ctx)) {
            if (on) {
                RootHelper.runRoot(ctx, """
                    setprop persist.vendor.audio.fluence.voicecall false 2>/dev/null
                    setprop persist.vendor.audio.fluence.voicecomm false 2>/dev/null
                    setprop persist.audio.fluence.voicecall false 2>/dev/null
                    settings put system microphone_enabled 0 2>/dev/null
                    # soft-stop common capture clients
                    am force-stop com.oculus.vrshell 2>/dev/null
                """.trimIndent().replace("\n", "; "))
            } else {
                RootHelper.runRoot(ctx, """
                    setprop persist.vendor.audio.fluence.voicecall true 2>/dev/null
                    settings put system microphone_enabled 1 2>/dev/null
                """.trimIndent().replace("\n", "; "))
            }
        }

        muted = on
        ctx.log(if (on) "Microphone MUTED (system + soft blocks)" else "Microphone UNMUTED")
        ctx.toast(if (on) "Mic muted" else "Mic unmuted")
    }

    fun toggle(ctx: Utils.ActionContext) {
        mute(ctx, !isMuted())
    }
}
