package com.helix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

// contact blaku64th on discord if you have any issues ^^
class CircularUsageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFFFFFFF.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFFFFFFF.toInt()
    }
    private val arcRect = RectF()

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    var ringColor: Int = 0xFF4FC3F7.toInt()
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    var trackColor: Int = 0x33FFFFFF
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidthDp: Float = 10f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    init {
        progressPaint.color = ringColor
        trackPaint.color = trackColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (88 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val stroke = strokeWidthDp * density
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val pad = stroke / 2f + 2f * density
        arcRect.set(pad, pad, width - pad, height - pad)

        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
        val sweep = 360f * (progress / 100f)
        if (sweep > 0.5f) {
            canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
        }

        val cx = width / 2f
        val cy = height / 2f

        valuePaint.textSize = 18f * density
        val valueText = "${progress.toInt()}%"
        val valueY = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f - 4f * density
        canvas.drawText(valueText, cx, valueY, valuePaint)

        if (label.isNotEmpty()) {
            labelPaint.textSize = 11f * density
            labelPaint.color = 0xFFAAAAAA.toInt()
            val labelY = cy + 16f * density
            canvas.drawText(label, cx, labelY, labelPaint)
        }
    }
}
