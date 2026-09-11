package com.helix

// contact blaku64th on discord if you have any issues ^^
object Overlay {

    const val SettingsReceiver = "com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver"

    const val EnableOverlay = "com.oculus.ovrmonitormetricsservice.ENABLE_OVERLAY"
    const val DisableOverlay = "com.oculus.ovrmonitormetricsservice.DISABLE_OVERLAY"

    const val EnableStat = "com.oculus.ovrmonitormetricsservice.ENABLE_STAT"
    const val DisableStat = "com.oculus.ovrmonitormetricsservice.DISABLE_STATS"
    const val EnableStats = "com.oculus.ovrmonitormetricsservice.ENABLE_STATS"
    const val DisableStats = "com.oculus.ovrmonitormetricsservice.DISABLE_STATS"

    const val EnableGraph = "com.oculus.ovrmonitormetricsservice.ENABLE_GRAPH"
    const val DisableGraph = "com.oculus.ovrmonitormetricsservice.DISABLE_GRAPH"

    const val EnableCSV = "com.oculus.ovrmonitormetricsservice.ENABLE_CSV"
    const val DisableCSV = "com.oculus.ovrmonitormetricsservice.DISABLE_CSV"

    val Stats = listOf(
        "battery_level_percentage",
        "cpu_level",
        "gpu_level",
        "average_frame_rate",
        "stale_frame_count",
        "cpu_utilization_percentage",
        "gpu_utilization_percentage",
        "app_gpu_time_microseconds"
    )
    @Volatile var headlocked: Boolean = true
    @Volatile var pitch: Float = 0f
    @Volatile var yaw: Float = 0f
    @Volatile var scale: Int = 2
    @Volatile var distance: Float = 1.0f
    @Volatile var graphsEnabled: Boolean = false
    @Volatile var statsEnabled: Boolean = true

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun enable(
        ctx: Utils.ActionContext,
        stats: List<String> = Stats
    ) {
        runCatching {
            broadcast(ctx, DisableGraph)
            broadcast(ctx, DisableStats)
            broadcast(ctx, DisableOverlay)
        }

        applyOverlayParams(ctx)

        if (statsEnabled) {
            broadcast(ctx, EnableStats)
            stats.forEach { stat ->
                broadcast(ctx, EnableStat, "--es stat $stat")
            }
        }

        if (graphsEnabled) {
            broadcast(ctx, EnableGraph)
        }

        isEnabled = true
        ctx.log("OVR Metrics overlay ON")
    }

    fun applyOverlayParams(ctx: Utils.ActionContext) {
        val s = scale.coerceIn(1, 3)
        val p = pitch.coerceIn(-90f, 90f)
        val y = yaw.coerceIn(-180f, 180f)
        val d = distance.coerceAtLeast(0.1f)
        val extras = buildString {
            append("--eb headlocked $headlocked")
            append(" --ef pitch $p")
            append(" --ef yaw $y")
            append(" --ei scale $s")
            append(" --ef distance $d")
        }
        broadcast(ctx, EnableOverlay, extras)
    }

    fun disable(ctx: Utils.ActionContext) {
        runCatching {
            broadcast(ctx, DisableGraph)
            broadcast(ctx, DisableStats)
            broadcast(ctx, DisableOverlay)
            broadcast(ctx, DisableOverlay)
        }
        isEnabled = false
        ctx.log("OVR Metrics Tool overlay OFF")
    }


    fun setHeadlocked(ctx: Utils.ActionContext, value: Boolean) {
        headlocked = value
        if (isEnabled) applyOverlayParams(ctx)
        ctx.log("OVR headlocked = $headlocked")
    }

    fun setPitch(ctx: Utils.ActionContext, value: Float) {
        pitch = value.coerceIn(-90f, 90f)
        if (isEnabled) applyOverlayParams(ctx)
        ctx.log("OVR pitch = $pitch")
    }

    fun setYaw(ctx: Utils.ActionContext, value: Float) {
        yaw = value.coerceIn(-180f, 180f)
        if (isEnabled) applyOverlayParams(ctx)
        ctx.log("OVR yaw = $yaw")
    }

    fun setScale(ctx: Utils.ActionContext, value: Int) {
        scale = value.coerceIn(1, 3)
        if (isEnabled) applyOverlayParams(ctx)
        ctx.log("OVR scale = $scale")
    }

    fun setDistance(ctx: Utils.ActionContext, value: Float) {
        distance = value.coerceAtLeast(0.1f)
        if (isEnabled) applyOverlayParams(ctx)
        ctx.log("OVR distance = $distance")
    }

    fun setGraphs(ctx: Utils.ActionContext, on: Boolean) {
        graphsEnabled = on
        if (isEnabled) {
            if (on) broadcast(ctx, EnableGraph)
            else broadcast(ctx, DisableGraph)
        }
        ctx.log("OVR graphs = $on")
    }

    fun setStats(ctx: Utils.ActionContext, on: Boolean) {
        statsEnabled = on
        if (isEnabled) {
            if (on) {
                broadcast(ctx, EnableStats)
                Stats.forEach { broadcast(ctx, EnableStat, "--es stat $it") }
            } else {
                broadcast(ctx, DisableStats)
            }
        }
        ctx.log("OVR stats = $on")
    }


    fun enableStat(ctx: Utils.ActionContext, stat: String) =
        broadcast(ctx, EnableStat, "--es stat $stat")

    fun disableStat(ctx: Utils.ActionContext, stat: String) =
        broadcast(ctx, DisableStat, "--es stat $stat")

    fun enableAllStats(ctx: Utils.ActionContext) =
        broadcast(ctx, EnableStats)

    fun disableAllStats(ctx: Utils.ActionContext) =
        broadcast(ctx, DisableStats)

    fun enableGraph(ctx: Utils.ActionContext, stat: String? = null) {
        if (stat.isNullOrBlank()) broadcast(ctx, EnableGraph)
        else broadcast(ctx, EnableGraph, "--es stat $stat")
    }

    fun disableGraph(ctx: Utils.ActionContext, stat: String? = null) {
        if (stat.isNullOrBlank()) broadcast(ctx, DisableGraph)
        else broadcast(ctx, DisableGraph, "--es stat $stat")
    }

    fun enableCsv(ctx: Utils.ActionContext) {
        broadcast(ctx, EnableCSV)
        ctx.log("OVR Metrics CSV recording ON → /sdcard/Android/data/com.oculus.ovrmonitormetricsservice/files/CapturedMetrics/")
    }

    fun disableCsv(ctx: Utils.ActionContext) {
        broadcast(ctx, DisableCSV)
        ctx.log("OVR Metrics CSV recording OFF")
    }

    fun handleConfigCommand(ctx: Utils.ActionContext, raw: String): Boolean {
        val body = raw.removePrefix("@@OVR@@").trim()
        if (body.isEmpty()) return false
        val parts = body.split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) return false
        val key = parts[0].lowercase()
        val value = parts[1].trim()
        when (key) {
            "headlocked" -> setHeadlocked(ctx, value == "1" || value.equals("true", true))
            "pitch" -> setPitch(ctx, value.toFloatOrNull() ?: return false)
            "yaw" -> setYaw(ctx, value.toFloatOrNull() ?: return false)
            "scale" -> setScale(ctx, value.toIntOrNull() ?: return false)
            "distance" -> setDistance(ctx, value.toFloatOrNull() ?: return false)
            "graphs" -> setGraphs(ctx, value == "1" || value.equals("true", true))
            "stats" -> setStats(ctx, value == "1" || value.equals("true", true))
            else -> return false
        }
        return true
    }


    fun broadcast(ctx: Utils.ActionContext, action: String, extras: String = "") {
        val cmd = buildString {
            append("am broadcast -n $SettingsReceiver -a $action")
            if (extras.isNotBlank()) append(" $extras")
        }
        runCatching { ctx.run(cmd) }
            .onFailure { ctx.log("OVR Metrics broadcast failed: ${it.message}") }
    }
}