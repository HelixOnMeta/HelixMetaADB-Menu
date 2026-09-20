package com.helix

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

// contact blaku64th on discord if you have any issues ^^
object Anim {

    fun bindPressFeedback(view: View, pressedScale: Float = 0.92f) {
        view.isClickable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .start()
                }
            }
            false
        }
    }

    fun bindPressFeedbackToTree(root: View) {
        if (root.isClickable || root is android.widget.Button || root is android.widget.Switch) {
            bindPressFeedback(root)
        }
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
        show.translationY = 18f
        show.visibility = View.VISIBLE
        show.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(duration / 2)
            .setDuration(duration + 80L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun staggerIn(
        container: ViewGroup,
        perViewDelay: Long = 28L,
        duration: Long = 260L,
        maxDelay: Long = 320L,
        fromY: Float = 28f,
        deep: Boolean = true
    ) {
        val targets = mutableListOf<View>()
        collectAnimTargets(container, targets, deep)
        targets.forEachIndexed { index, child ->
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = fromY
            child.scaleX = 0.96f
            child.scaleY = 0.96f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * perViewDelay).coerceAtMost(maxDelay))
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    private fun collectAnimTargets(group: ViewGroup, out: MutableList<View>, deep: Boolean) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val isLeafish = child !is ViewGroup ||
                child is android.widget.Button ||
                child.childCount == 0 ||
                (child is ViewGroup && child.childCount <= 3 && hasInteractive(child))
            if (!deep || isLeafish) {
                out.add(child)
            } else if (child is ViewGroup) {
                collectAnimTargets(child, out, true)
            } else {
                out.add(child)
            }
        }
    }

    private fun hasInteractive(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c is android.widget.Button || c is android.widget.Switch || c.isClickable) return true
        }
        return false
    }

    fun playOpen(
        root: View,
        staggerRoot: ViewGroup? = null,
        duration: Long = 320L
    ) {
        root.animate().cancel()
        root.scaleX = 0.88f
        root.scaleY = 0.88f
        root.alpha = 0f
        root.translationY = 24f
        root.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(20L)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction {
                if (staggerRoot != null) {
                    staggerIn(staggerRoot, perViewDelay = 32L, duration = 240L, fromY = 22f)
                }
            }
            .start()
    }
}
