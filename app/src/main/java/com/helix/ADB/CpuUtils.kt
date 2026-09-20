package com.helix

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// contact blaku64th on discord if you have any issues ^^
data class CpuMonitorInfo(
    val tempCelsius: Int = 0,
    val littleCoreMinFreqMhz: Int = 0,
    val littleCoreMaxFreqMhz: Int = 0,
    val bigCoreMinFreqMhz: Int = 0,
    val bigCoreMaxFreqMhz: Int = 0,
    val littleCoreUsagePercent: Int = 0,
    val bigCoreUsagePercent: Int = 0,
    val allCoreUsagePercent: Int = 0,
    val littleCores: List<Int> = emptyList(),
    val bigCores: List<Int> = emptyList()
)

data class CpuThresholds(
    val maxTempCelsius: Int = 85,
    val criticalTempCelsius: Int = 95,
    val highUsagePercent: Int = 90,
    val enableAutoThrottle: Boolean = false
)

object CpuUtils {
    const val SCRIPT_NAME = "min_freq_lock.sh"
    private const val TAG = "CpuUtils"

    const val DEFAULT_LITTLE_FREQ = "691200"
    const val DEFAULT_BIG_FREQ = "691200"

    @Volatile
    var thresholds = CpuThresholds()

    @Volatile
    private var cachedLittleCores: List<Int>? = null
    @Volatile
    private var cachedBigCores: List<Int>? = null

    private data class CoreStat(val idle: Long, val total: Long)
    private var previousCoreStats: Map<String, CoreStat>? = null

    suspend fun getCpuMonitorInfo(): CpuMonitorInfo = withContext(Dispatchers.IO) {
        val little = getLittleCores()
        val big = getBigCores()

        val tempCelsius = findCpuTemperature()

        val littleMinRaw = if (little.isNotEmpty()) {
            RootHelper.readSysfs("/sys/devices/system/cpu/cpu${little.first()}/cpufreq/scaling_min_freq")
        } else ""
        val littleMaxRaw = if (little.isNotEmpty()) {
            RootHelper.readSysfs("/sys/devices/system/cpu/cpu${little.first()}/cpufreq/scaling_max_freq")
        } else ""
        val bigMinRaw = if (big.isNotEmpty()) {
            RootHelper.readSysfs("/sys/devices/system/cpu/cpu${big.first()}/cpufreq/scaling_min_freq")
        } else ""
        val bigMaxRaw = if (big.isNotEmpty()) {
            RootHelper.readSysfs("/sys/devices/system/cpu/cpu${big.first()}/cpufreq/scaling_max_freq")
        } else ""

        val usageMap = getCpuUsage()
        val littleUsage = little.mapNotNull { usageMap["cpu$it"] }.averageOrZero()
        val bigUsage = big.mapNotNull { usageMap["cpu$it"] }.averageOrZero()
        val allUsage = usageMap.values.averageOrZero()

        CpuMonitorInfo(
            tempCelsius = tempCelsius,
            littleCoreMinFreqMhz = littleMinRaw.toIntOrNull()?.div(1000) ?: 0,
            littleCoreMaxFreqMhz = littleMaxRaw.toIntOrNull()?.div(1000) ?: 0,
            bigCoreMinFreqMhz = bigMinRaw.toIntOrNull()?.div(1000) ?: 0,
            bigCoreMaxFreqMhz = bigMaxRaw.toIntOrNull()?.div(1000) ?: 0,
            littleCoreUsagePercent = littleUsage,
            bigCoreUsagePercent = bigUsage,
            allCoreUsagePercent = allUsage,
            littleCores = little,
            bigCores = big
        )
    }

    suspend fun isThresholdExceeded(): Boolean {
        val info = getCpuMonitorInfo()
        val t = thresholds
        return info.tempCelsius >= t.maxTempCelsius ||
                info.tempCelsius >= t.criticalTempCelsius ||
                info.allCoreUsagePercent >= t.highUsagePercent
    }

    suspend fun getThresholdStatus(): String {
        val info = getCpuMonitorInfo()
        val t = thresholds
        return buildString {
            append("CPU ${info.tempCelsius}°C / ${t.maxTempCelsius}°C  ")
            append("Usage ${info.allCoreUsagePercent}% / ${t.highUsagePercent}%")
            if (info.tempCelsius >= t.criticalTempCelsius) append("  [CRITICAL]")
            else if (info.tempCelsius >= t.maxTempCelsius) append("  [HOT]")
            else if (info.allCoreUsagePercent >= t.highUsagePercent) append("  [HIGH LOAD]")
        }
    }

    suspend fun getLittleCores(): List<Int> {
        cachedLittleCores?.let { return it }
        return discoverCores().first.also { cachedLittleCores = it }
    }

    suspend fun getBigCores(): List<Int> {
        cachedBigCores?.let { return it }
        return discoverCores().second.also { cachedBigCores = it }
    }

    private suspend fun discoverCores(): Pair<List<Int>, List<Int>> = withContext(Dispatchers.IO) {
        val present = mutableListOf<Int>()
        for (i in 0..7) {
            val exists = RootHelper.runAsRootLocal("test -d /sys/devices/system/cpu/cpu$i && echo yes")
                .contains("yes")
            if (exists) present.add(i)
        }

        if (present.isEmpty()) {
            return@withContext (0..3).toList() to (4..7).toList()
        }

        val freqMap = present.associateWith { core ->
            RootHelper.readSysfs("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                .toLongOrNull() ?: 0L
        }

        val maxFreq = freqMap.values.maxOrNull() ?: 0L
        val minFreq = freqMap.values.minOrNull() ?: 0L

        val little = if (maxFreq == minFreq) {
            present.take(present.size / 2)
        } else {
            freqMap.filter { it.value < maxFreq }.keys.sorted()
        }
        val big = present.filter { it !in little }

        val safeLittle = little.ifEmpty { present.take(4) }
        val safeBig = big.ifEmpty { present.drop(4) }

        Log.d(TAG, "Discovered cores – little=$safeLittle  big=$safeBig")
        safeLittle to safeBig
    }

    private suspend fun findCpuTemperature(): Int = withContext(Dispatchers.IO) {
        val d = "${'$'}"
        val zoneTypeCmd = """
            for z in /sys/class/thermal/thermal_zone*; do
                type=${d}(cat "${d}z/type" 2>/dev/null)
                case "${d}type" in
                    *cpu*|*CPU*|*soc*|*SOC*|*xo*|*skin*)
                        temp=${d}(cat "${d}z/temp" 2>/dev/null)
                        if [ -n "${d}temp" ]; then
                            echo "${d}temp"
                            exit 0
                        fi
                        ;;
                esac
            done
            for z in /sys/class/thermal/thermal_zone*; do
                temp=${d}(cat "${d}z/temp" 2>/dev/null)
                if [ -n "${d}temp" ]; then
                    echo "${d}temp"
                    exit 0
                fi
            done
        """.trimIndent()

        val raw = RootHelper.runAsRootLocal(zoneTypeCmd).trim()
            .lines()
            .firstOrNull { it.toIntOrNull() != null }
            ?: "0"

        val milli = raw.toIntOrNull() ?: 0
        if (milli > 1000) milli / 1000 else milli
    }

    private suspend fun getCpuUsage(): Map<String, Int> = withContext(Dispatchers.IO) {
        val usageMap = mutableMapOf<String, Int>()
        try {
            val stat = RootHelper.runAsRootLocal("cat /proc/stat").lines()
            val currentStats = mutableMapOf<String, CoreStat>()

            for (line in stat) {
                if (!line.startsWith("cpu")) continue
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5) continue
                val cpuName = parts[0]
                if (cpuName == "cpu") continue

                val user = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val nice = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val system = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val idle = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

                val totalTime = user + nice + system + idle + iowait + irq + softirq
                currentStats[cpuName] = CoreStat(idle, totalTime)
            }

            previousCoreStats?.let { prevStats ->
                for ((cpuName, current) in currentStats) {
                    val prev = prevStats[cpuName] ?: continue
                    val totalDiff = current.total - prev.total
                    val idleDiff = current.idle - prev.idle
                    val usage = if (totalDiff > 0) {
                        ((100.0 * (totalDiff - idleDiff)) / totalDiff).toInt().coerceIn(0, 100)
                    } else 0
                    usageMap[cpuName] = usage
                }
            }
            previousCoreStats = currentStats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate CPU usage", e)
        }
        usageMap
    }

    suspend fun getGovernor(): Pair<String, String> = withContext(Dispatchers.IO) {
        val little = getLittleCores().firstOrNull() ?: 0
        val big = getBigCores().firstOrNull() ?: 4
        val littleGov = RootHelper.readSysfs("/sys/devices/system/cpu/cpu$little/cpufreq/scaling_governor")
        val bigGov = RootHelper.readSysfs("/sys/devices/system/cpu/cpu$big/cpufreq/scaling_governor")
        littleGov to bigGov
    }

    suspend fun setGovernor(governor: String) = withContext(Dispatchers.IO) {
        val little = getLittleCores()
        val big = getBigCores()
        val cmds = (little + big).joinToString("\n") {
            "echo \"$governor\" > /sys/devices/system/cpu/cpu$it/cpufreq/scaling_governor"
        }
        RootHelper.runAsRootLocal(cmds)
    }

    suspend fun isPerformanceMode(): Boolean = withContext(Dispatchers.IO) {
        val (littleGov, bigGov) = getGovernor()
        littleGov == "performance" && bigGov == "performance"
    }

    suspend fun getMinFreqScript(littleFreq: String, bigFreq: String): String {
        val little = getLittleCores()
        val big = getBigCores()

        val littleCmds = little.joinToString("\n") {
            "echo \"$littleFreq\" > /sys/devices/system/cpu/cpu$it/cpufreq/scaling_min_freq 2>/dev/null"
        }
        val bigCmds = big.joinToString("\n") {
            "echo \"$bigFreq\" > /sys/devices/system/cpu/cpu$it/cpufreq/scaling_min_freq 2>/dev/null"
        }

        return """
            #!/system/bin/sh
            # Helix CPU min-freq lock (auto-generated)
            while true; do
                $littleCmds
                $bigCmds
                sleep 2
            done
        """.trimIndent()
    }

    private fun Collection<Int>.averageOrZero(): Int =
        if (isEmpty()) 0 else (sum().toDouble() / size).toInt()
}
