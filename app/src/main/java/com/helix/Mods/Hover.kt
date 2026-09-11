package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Hover {

    var speed: Float = 2f
    var posX: Float = 0f
    var posY: Float = 0f
    var posZ: Float = 0f

    @Volatile
    var enabled: Boolean = false

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var armed = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        runCatching {
            HeadlockHelper.armOffset(ctx)
            armed = true
            enabled = true
            writeNow(ctx)
            ctx.log("[Hover] armed speed=$speed")
        }.onFailure { ctx.log("[Hover] arm failed: ${it.message}") }

        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[Hover error] ${e.message}") }
        job = scope.launch(handler) {
            var last = System.nanoTime()
            while (isActive) {
                try {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    last = now
                    posY += speed * dt
                    writeNow(ctx)
                } catch (e: Throwable) {
                    ctx.log("[Hover error] ${e.message}")
                }
                delay(14)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        if (armed) {
            ctx?.let { runCatching { HeadlockHelper.disarm(it) } }
            armed = false
        }
    }

    fun resetPosition() { posX = 0f; posY = 0f; posZ = 0f }

    private fun writeNow(ctx: Utils.ActionContext) {
        scope.launch(Dispatchers.IO) {
            runCatching { HeadlockHelper.writeOffset(ctx, posX, posY, posZ) }
        }
    }
}
