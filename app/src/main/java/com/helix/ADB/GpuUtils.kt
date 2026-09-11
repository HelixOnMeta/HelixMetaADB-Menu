package com.helix

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GpuMonitorInfo(
    val tempCelsius: Int = 0,
    val freqMhz: Int = 0,
    val maxFreqMhz: Int = 0,
    val minFreqMhz: Int = 0,
    val usagePercent: Int = 0
)

data class GpuThresholds(
    val maxTempCelsius: Int = 80,
    val criticalTempCelsius: Int = 90,
    val highUsagePercent: Int = 95,
    val enableAutoThrottle: Boolean = false
)

object GpuUtils {
    private const val TAG = "GpuUtils"

    private const val GPU_PATH = "/sys/class/kgsl/kgsl-3d0"
    private const val GPU_DEVFREQ_PATH = "$GPU_PATH/devfreq"

    const val GPU_MIN_FREQ_SCRIPT_NAME = "gpu_min_freq_lock.sh"
    const val GPU_MAX_FREQ_SCRIPT_NAME = "gpu_max_freq_lock.sh"
    const val DEFAULT_GPU_MIN_FREQ = "285"
    const val DEFAULT_GPU_MAX_FREQ = "492"

    @Volatile
    var thresholds = GpuThresholds()

    private val FREQ_PATHS = listOf(
        "$GPU_PATH/gpuclk",
        "$GPU_DEVFREQ_PATH/cur_freq"
    )

    private val MAX_FREQ_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/max_clock_mhz",
        "$GPU_DEVFREQ_PATH/max_freq",
        "$GPU_PATH/max_gpuclk"
    )

    private val MIN_FREQ_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/min_clock_mhz",
        "$GPU_DEVFREQ_PATH/min_freq"
    )

    private val USAGE_PATHS = listOf(
        "$GPU_PATH/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
    )

    private val TEMP_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp",
        "$GPU_PATH/temp",
        "/sys/class/thermal/thermal_zone10/temp",
        "/sys/class/thermal/thermal_zone9/temp",
        "/sys/class/thermal/thermal_zone8/temp"
    )

    private val GPU_SET_MAX_FREQ_PATH_HZ = "$GPU_DEVFREQ_PATH/max_freq"
    private val GPU_SET_MIN_FREQ_PATH_HZ = "$GPU_DEVFREQ_PATH/min_freq"
    private const val GPU_SET_MIN_FREQ_PATH_MHZ = "/sys/class/kgsl/kgsl-3d0/min_clock_mhz"

    suspend fun getGpuMonitorInfo(): GpuMonitorInfo = withContext(Dispatchers.IO) {
        try {
            val freqRaw = readFirstAvailable(FREQ_PATHS)
            val maxFreqRaw = readFirstAvailable(MAX_FREQ_PATHS)
            val minFreqRaw = readFirstAvailable(MIN_FREQ_PATHS)
            val usageRaw = readFirstAvailable(USAGE_PATHS)
            val tempRaw = readFirstAvailable(TEMP_PATHS)

            val freq = convertFreq(freqRaw)
            val maxFreq = convertFreq(maxFreqRaw)
            val minFreq = convertFreq(minFreqRaw)

            val usagePercent = parseUsage(usageRaw)
            val tempCelsius = parseTemp(tempRaw)

            GpuMonitorInfo(
                tempCelsius = tempCelsius,
                freqMhz = freq,
                maxFreqMhz = maxFreq,
                minFreqMhz = minFreq,
                usagePercent = usagePercent
            )
        } catch (e: Exception) {
            Log.e(TAG, "getGpuMonitorInfo failed", e)
            GpuMonitorInfo()
        }
    }

    suspend fun isThresholdExceeded(): Boolean {
        val info = getGpuMonitorInfo()
        val t = thresholds
        return info.tempCelsius >= t.maxTempCelsius ||
                info.tempCelsius >= t.criticalTempCelsius ||
                info.usagePercent >= t.highUsagePercent
    }

    suspend fun getThresholdStatus(): String {
        val info = getGpuMonitorInfo()
        val t = thresholds
        return buildString {
            append("GPU ${info.tempCelsius}°C / ${t.maxTempCelsius}°C  ")
            append("Usage ${info.usagePercent}% / ${t.highUsagePercent}%  ")
            append("${info.freqMhz} MHz")
            if (info.tempCelsius >= t.criticalTempCelsius) append("  [CRITICAL]")
            else if (info.tempCelsius >= t.maxTempCelsius) append("  [HOT]")
            else if (info.usagePercent >= t.highUsagePercent) append("  [HIGH LOAD]")
        }
    }

    suspend fun setGpuMaxFreq(freqHz: String): Boolean = withContext(Dispatchers.IO) {
        if (freqHz.toLongOrNull() == null) {
            Log.w(TAG, "Invalid max freq: $freqHz")
            return@withContext false
        }

        val command = "echo '$freqHz' > $GPU_SET_MAX_FREQ_PATH_HZ"
        val writeResult = RootHelper.runAsRootLocal(command)

        if (writeResult.contains("ERROR:", ignoreCase = true) ||
            writeResult.contains("No such file") ||
            writeResult.contains("Permission denied")
        ) {
            Log.w(TAG, "Failed to write GPU max freq: $writeResult")
            return@withContext false
        }

        val readBack = RootHelper.readSysfs(GPU_SET_MAX_FREQ_PATH_HZ)
        val ok = readBack.trim() == freqHz.trim()
        if (ok) {
            Log.d(TAG, "GPU max freq set & verified: $freqHz Hz")
        } else {
            Log.w(TAG, "GPU max freq write ok but verify failed. expected=$freqHz got=$readBack")
        }
        ok
    }

    suspend fun setGpuMinFreq(freqHz: String): Boolean = withContext(Dispatchers.IO) {
        if (freqHz.toLongOrNull() == null) {
            Log.w(TAG, "Invalid min freq: $freqHz")
            return@withContext false
        }

        val paths = listOf(GPU_SET_MIN_FREQ_PATH_HZ, GPU_SET_MIN_FREQ_PATH_MHZ)
        for (path in paths) {
            val isMhzNode = path.endsWith("_mhz")
            val valueToWrite = if (isMhzNode) {
                (freqHz.toLongOrNull()?.div(1_000_000) ?: continue).toString()
            } else {
                freqHz
            }

            val command = "echo '$valueToWrite' > $path"
            val writeResult = RootHelper.runAsRootLocal(command)
            if (!writeResult.contains("ERROR:", ignoreCase = true)) {
                val readBack = RootHelper.readSysfs(path)
                if (readBack.trim() == valueToWrite.trim()) {
                    Log.d(TAG, "GPU min freq set & verified on $path = $valueToWrite")
                    return@withContext true
                }
            }
        }
        Log.w(TAG, "All attempts to set GPU min freq failed")
        false
    }

    suspend fun setGpuMaxFreqMhz(mhz: Int): Boolean =
        setGpuMaxFreq((mhz.toLong() * 1_000_000).toString())

    suspend fun setGpuMinFreqMhz(mhz: Int): Boolean =
        setGpuMinFreq((mhz.toLong() * 1_000_000).toString())

    fun getGpuMinFreqScript(freqMhz: String): String {
        val freqHz = (freqMhz.toLongOrNull() ?: DEFAULT_GPU_MIN_FREQ.toLong()) * 1_000_000
        return """
            #!/system/bin/sh
            # Helix GPU min-freq lock
            while true; do
                echo "$freqHz" > $GPU_SET_MIN_FREQ_PATH_HZ 2>/dev/null
                echo "$freqMhz" > $GPU_SET_MIN_FREQ_PATH_MHZ 2>/dev/null
                sleep 2
            done
        """.trimIndent()
    }

    fun getGpuMaxFreqScript(freqMhz: String): String {
        val freqHz = (freqMhz.toLongOrNull() ?: DEFAULT_GPU_MAX_FREQ.toLong()) * 1_000_000
        return """
            #!/system/bin/sh
            # Helix GPU max-freq lock
            while true; do
                echo "$freqHz" > $GPU_SET_MAX_FREQ_PATH_HZ 2>/dev/null
                sleep 2
            done
        """.trimIndent()
    }

    private suspend fun readFirstAvailable(paths: List<String>): String {
        for (path in paths) {
            val value = RootHelper.readSysfs(path)
            if (value.isNotBlank() && !value.startsWith("ERROR:")) {
                return value
            }
        }
        return ""
    }

    private fun convertFreq(raw: String): Int {
        val v = raw.trim().toLongOrNull() ?: return 0
        return when {
            v > 100_000_000 -> (v / 1_000_000).toInt()
            v > 1_000       -> (v / 1_000).toInt()
            else            -> v.toInt()
        }
    }

    private fun parseUsage(raw: String): Int {
        val cleaned = raw.trim()
        if (cleaned.contains(" ")) {
            val parts = cleaned.split(Regex("\\s+"))
            val busy = parts.getOrNull(0)?.toLongOrNull() ?: return 0
            val total = parts.getOrNull(1)?.toLongOrNull() ?: return 0
            if (total > 0) return ((100.0 * busy) / total).toInt().coerceIn(0, 100)
        }
        return cleaned.replace("%", "").toIntOrNull()?.coerceIn(0, 100) ?: 0
    }

    private fun parseTemp(raw: String): Int {
        val v = raw.trim().toIntOrNull() ?: return 0
        return if (v > 1000) v / 1000 else v
    }
}
