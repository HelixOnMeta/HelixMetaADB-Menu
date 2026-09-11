package com.helix

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object PSA {

    @Volatile var enabled: Boolean = false
        private set

    var intervalMs: Long = 14L

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        enabled = true
        val handler = CoroutineExceptionHandler { _, e -> ctx.log("[PSA error] ${e.message}") }
        job = scope.launch(handler) {
            while (isActive) {
                try {
                    zeroAll(ctx)
                } catch (e: Throwable) {
                    ctx.log("[PSA error] ${e.message}")
                }
                delay(intervalMs)
            }
        }
        ctx.log("[PSA] ON — zeroing movement-mod position every ${intervalMs}ms")
        ctx.toast("PSA ON")
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        ctx?.log("[PSA] OFF")
        ctx?.toast("PSA OFF")
    }

    private fun zeroAll(ctx: Utils.ActionContext) {
        Fly.posX = 0f; Fly.posY = 0f; Fly.posZ = 0f
        Hover.posX = 0f; Hover.posY = 0f; Hover.posZ = 0f
        LowGravity.posX = 0f; LowGravity.posY = 0f; LowGravity.posZ = 0f
        Platforms.posX = 0f; Platforms.posY = 0f; Platforms.posZ = 0f
        UpDown.posX = 0f; UpDown.posY = 0f; UpDown.posZ = 0f
        VelocityFly.posX = 0f; VelocityFly.posY = 0f; VelocityFly.posZ = 0f
        WallWalk.posX = 0f; WallWalk.posY = 0f; WallWalk.posZ = 0f
        Grapple.posX = 0f; Grapple.posY = 0f; Grapple.posZ = 0f

        if (HeadlockHelper.isArmed) {
            runCatching { HeadlockHelper.writeTranslationOnly(ctx, 0f, 0f, 0f, respectHold = false) }
        }
    }
}