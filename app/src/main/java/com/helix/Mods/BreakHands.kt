package com.helix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object BreakHands {

    @Volatile var enabled: Boolean = false
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var broken = false

    fun start(ctx: Utils.ActionContext) {
        if (job?.isActive == true) return
        enabled = true
        broken = false
        Input.startfallback()
        ctx.log("[BreakHands] ON — hold grip to break, release to fix")
        ctx.toast("Break hands: hold grip")

        job = scope.launch(Dispatchers.IO) {
            while (isActive && enabled) {
                try {
                    val gripping = Input.leftSqueeze() || Input.rightSqueeze()
                    if (gripping && !broken) {
                        HeadlockHelper.breakarms(ctx)
                        broken = true
                        ctx.log("[BreakHands] grip held — arms broken")
                    } else if (!gripping && broken) {
                        HeadlockHelper.fixarms(ctx)
                        broken = false
                        ctx.log("[BreakHands] grip released — arms fixed")
                    }
                } catch (e: Throwable) {
                    ctx.log("[BreakHands error] ${e.message}")
                }
                delay(40)
            }
        }
    }

    fun stop(ctx: Utils.ActionContext? = null) {
        job?.cancel()
        job = null
        enabled = false
        if (broken) {
            ctx?.let { runCatching { HeadlockHelper.fixarms(it) } }
            broken = false
        }
        Input.releasefallback()
        ctx?.log("[BreakHands] OFF")
    }
}
