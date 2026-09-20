package com.helix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
class TweakService : Service() {

    companion object {
        const val CHANNEL = "helix_tweaks"
        const val NOTIF_ID = 77
        const val EXTRA_FAN = "fan"
        const val EXTRA_FAN_SPEED = "fan_speed"
        const val EXTRA_CPU_MIN = "cpu_min"
        const val EXTRA_GPU_MIN = "gpu_min"

        @Volatile
        var instance: TweakService? = null
            private set

        fun start(
            context: Context,
            fan: Boolean = true,
            fanSpeed: Int = FanUtils.preferredSpeed,
            cpuMin: Boolean = false,
            gpuMin: Boolean = false
        ) {
            val i = Intent(context, TweakService::class.java).apply {
                putExtra(EXTRA_FAN, fan)
                putExtra(EXTRA_FAN_SPEED, fanSpeed)
                putExtra(EXTRA_CPU_MIN, cpuMin)
                putExtra(EXTRA_GPU_MIN, gpuMin)
            }
            try {
                context.startForegroundService(i)
            } catch (_: Exception) {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            instance?.shutdown()
            context.stopService(Intent(context, TweakService::class.java))
        }

        fun isRunning(): Boolean = instance != null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    private var fanEnabled = false
    private var fanSpeed = FanUtils.DEFAULT_SPEED
    private var cpuMinEnabled = false
    private var gpuMinEnabled = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif("Helix tweaks starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fanEnabled = intent?.getBooleanExtra(EXTRA_FAN, true) ?: true
        fanSpeed = intent?.getIntExtra(EXTRA_FAN_SPEED, FanUtils.preferredSpeed) ?: FanUtils.preferredSpeed
        cpuMinEnabled = intent?.getBooleanExtra(EXTRA_CPU_MIN, false) ?: false
        gpuMinEnabled = intent?.getBooleanExtra(EXTRA_GPU_MIN, false) ?: false
        FanUtils.preferredSpeed = fanSpeed

        updateNotif()
        loopJob?.cancel()
        loopJob = scope.launch { maintainLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun shutdown() {
        loopJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun maintainLoop() {
        if (fanEnabled) {
            runCatching { FanUtils.setFanState(fanSpeed) }
        }
            try {
                if (fanEnabled) {
                    FanUtils.setFanState(fanSpeed)
                }
                if (cpuMinEnabled) {
                    val script = CpuUtils.getMinFreqScript(
                        CpuUtils.DEFAULT_LITTLE_FREQ,
                        CpuUtils.DEFAULT_BIG_FREQ
                    )
                    RootHelper.runAsRootLocal(
                        """
                        for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq; do
                          [ -w "${'$'}c" ] && echo ${CpuUtils.DEFAULT_LITTLE_FREQ} > "${'$'}c" 2>/dev/null
                        done
                        """.trimIndent()
                    )
                }
                if (gpuMinEnabled) {
                    RootHelper.runAsRootLocal(
                        """
                        echo $$((${GpuUtils.DEFAULT_GPU_MIN_FREQ} * 1000000)) > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
                        echo ${GpuUtils.DEFAULT_GPU_MIN_FREQ} > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null
                        """.trimIndent()
                    )
                }
            } catch (_: Throwable) {
            }
            delay(2500)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Helix Tweaks", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Helix Tweaks")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif() {
        val parts = mutableListOf<String>()
        if (fanEnabled) parts += "fan@$fanSpeed"
        if (cpuMinEnabled) parts += "cpu-min"
        if (gpuMinEnabled) parts += "gpu-min"
        val text = if (parts.isEmpty()) "idle" else parts.joinToString(" · ")
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotif(text))
    }
}
