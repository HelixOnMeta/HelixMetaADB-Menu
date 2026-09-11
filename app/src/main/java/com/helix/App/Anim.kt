package com.helix

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

// contact blaku64th on discord if you have any issues ^^
object Anim {

    fun bindPressFeedback(view: View, pressedScale: Float = 0.95f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160L)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            }
            false
        }
    }

    fun bindPressFeedbackToTree(root: View) {
        if (root.isClickable) bindPressFeedback(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) bindPressFeedbackToTree(root.getChildAt(i))
        }
    }

    fun crossfadeSwap(hide: List<View>, show: View, duration: Long = 170L) {
        hide.forEach { v ->
            if (v.visibility != View.VISIBLE) return@forEach
            v.animate().cancel()
            v.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
                .start()
        }

        show.animate().cancel()
        show.alpha = 0f
        show.translationY = 14f
        show.visibility = View.VISIBLE
        show.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(duration / 2)
            .setDuration(duration + 60L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun staggerIn(container: ViewGroup, perViewDelay: Long = 26L, duration: Long = 220L, maxDelay: Long = 260L) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = 16f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * perViewDelay).coerceAtMost(maxDelay))
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}