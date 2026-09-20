package com.helix

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

// contact blaku64th on discord if you have any issues ^^
object VoiceChanger {
    @Volatile var enabled = false
        private set

    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile var pitchFactor = 1.0f
    @Volatile var robotAmount = 0f
    @Volatile var alienAmount = 0f
    @Volatile var gain = 1.2f
    @Volatile var echoSamples = 0
    @Volatile var echoMix = 0.25f

    data class Preset(
        val name: String,
        val pitch: Float,
        val robot: Float,
        val alien: Float,
        val gain: Float,
        val echoSamples: Int = 0,
        val echoMix: Float = 0f
    )

    val PRESETS = listOf(
        Preset("Normal", 1.0f, 0f, 0f, 1.0f),
        Preset("Deep", 0.65f, 0.05f, 0f, 1.15f),
        Preset("Chipmunk", 1.45f, 0f, 0f, 1.1f),
        Preset("Robot", 1.0f, 0.75f, 0.1f, 1.2f),
        Preset("Alien", 1.15f, 0.2f, 0.7f, 1.25f, 1200, 0.3f),
        Preset("Radio", 0.95f, 0.45f, 0f, 1.35f, 600, 0.15f),
        Preset("Helium", 1.7f, 0f, 0.15f, 1.1f),
        Preset("Monster", 0.5f, 0.35f, 0.2f, 1.4f, 2000, 0.35f)
    )

    @Volatile var activePreset: String = "Normal"

    fun applyPreset(name: String) {
        val p = PRESETS.firstOrNull { it.name.equals(name, true) } ?: return
        pitchFactor = p.pitch
        robotAmount = p.robot
        alienAmount = p.alien
        gain = p.gain
        echoSamples = p.echoSamples
        echoMix = p.echoMix
        activePreset = p.name
    }

    fun statusLine(): String =
        "Voice: ${if (enabled) "ON" else "OFF"}  preset=$activePreset  " +
                "pitch=${"%.2f".format(pitchFactor)} robot=${"%.2f".format(robotAmount)} " +
                "alien=${"%.2f".format(alienAmount)} gain=${"%.2f".format(gain)}"

    fun start(ctx: Utils.ActionContext) {
        if (enabled) return
        if (ContextCompat.checkSelfPermission(AppContext.app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ctx.toast("Need microphone permission")
            ctx.log("VoiceChanger needs RECORD_AUDIO")
            return
        }
        if (MicControl.isMuted()) {
            ctx.toast("Unmute mic first")
            ctx.log("Mic is muted — unmute to use voice changer")
            return
        }
        enabled = true
        recordJob = scope.launch { processLoop(ctx) }
        ctx.log("VoiceChanger ON — ${statusLine()}")
        ctx.toast("VoiceChanger ON")
    }

    fun stop(ctx: Utils.ActionContext) {
        enabled = false
        recordJob?.cancel()
        recordJob = null
        ctx.log("VoiceChanger OFF")
        ctx.toast("VoiceChanger OFF")
    }

    fun toggle(ctx: Utils.ActionContext) {
        if (enabled) stop(ctx) else start(ctx)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun processLoop(ctx: Utils.ActionContext) {
        val sampleRate = 16_000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val buf = ShortArray(bufferSize)
        val echoBuf = ShortArray(16_000)
        var echoWrite = 0
        var phase = 0.0

        try {
            recorder.startRecording()
            track.play()
            while (enabled && currentCoroutineContext().isActive) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    processInPlace(buf, read, echoBuf, echoWrite, phase)
                    echoWrite = (echoWrite + read) % echoBuf.size
                    phase += read
                    track.write(buf, 0, read)
                }
            }
        } catch (e: Exception) {
            ctx.log("VoiceChanger loop error: ${e.message}")
        } finally {
            runCatching { recorder.stop(); recorder.release() }
            runCatching { track.stop(); track.release() }
        }
    }

    private fun processInPlace(
        samples: ShortArray,
        n: Int,
        echoBuf: ShortArray,
        echoWrite: Int,
        phaseBase: Double
    ) {
        val pitch = pitchFactor.coerceIn(0.4f, 2.0f)
        val robot = robotAmount.coerceIn(0f, 1f)
        val alien = alienAmount.coerceIn(0f, 1f)
        val g = gain.coerceIn(0.2f, 3f)
        val echoN = echoSamples.coerceIn(0, echoBuf.size - 1)
        val eMix = echoMix.coerceIn(0f, 0.8f)

        val stepped = ShortArray(n)
        for (i in 0 until n) {
            val src = ((i * pitch).toInt()).coerceIn(0, n - 1)
            stepped[i] = samples[src]
        }

        for (i in 0 until n) {
            var s: Float = stepped[i].toFloat() * g

            if (robot > 0.01f) {
                val steps = (6 + (robot * 28).toInt()).coerceAtLeast(2).toFloat()
                s = (s / steps).toInt() * steps
            }
            if (alien > 0.01f) {
                val mod = sin((phaseBase + i) * 0.04 * (1.0 + alien * 3.0)).toFloat()
                s *= (0.55f + 0.45f * mod)
            }
            if (echoN > 0) {
                val ri = (echoWrite + i - echoN + echoBuf.size * 4) % echoBuf.size
                val delayed = echoBuf[ri].toFloat()
                s = s * (1f - eMix) + delayed * eMix
            }

            val clippedInt = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val clipped = clippedInt.toShort()
            samples[i] = clipped
            echoBuf[(echoWrite + i) % echoBuf.size] = clipped
        }
    }
}