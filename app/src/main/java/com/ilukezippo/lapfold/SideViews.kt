package com.ilukezippo.lapfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Vertical strip next to the trackpad that works like a mouse wheel. */
@SuppressLint("ViewConstructor")
class ScrollStripView(ctx: Context, private val listener: PadListener) : View(ctx) {
    var theme: Theme = Theme.DARK
        set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastY = 0f
    private var scrolling = false
    private var offset = 0f   // animates the ridges while scrolling

    override fun onDraw(c: Canvas) {
        val r = 10f * dp
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        paint.style = Paint.Style.FILL
        paint.color = if (scrolling) theme.keyPressed else theme.padBg
        c.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * dp; paint.color = theme.padBorder
        c.drawRoundRect(rect, r, r, paint)
        // wheel ridges
        paint.color = theme.keySubText; paint.strokeWidth = 1.2f * dp
        val step = 10 * dp
        var y = (offset % step + step) % step + 14 * dp
        while (y < height - 14 * dp) {
            c.drawLine(width * 0.3f, y, width * 0.7f, y, paint); y += step
        }
        paint.style = Paint.Style.FILL; paint.color = theme.keySubText
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 11 * dp
        c.drawText("▲", width / 2f, 12 * dp, paint)
        c.drawText("▼", width / 2f, height - 4 * dp, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = e.y; scrolling = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                if (!scrolling && dy != 0f) {
                    scrolling = true; listener.onScrollStart()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (scrolling) { listener.onScroll(0f, dy); offset += dy; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrolling) listener.onScrollEnd()
                scrolling = false; invalidate()
            }
        }
        return true
    }
}

/** A vertical stack of buttons to the right of the trackpad. */
@SuppressLint("ViewConstructor")
class NavButtonsView(ctx: Context, private val buttons: List<Pair<String, () -> Unit>>) : View(ctx) {
    var theme: Theme = Theme.DARK
        set(v) { field = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var pressed = -1
    private val gap get() = 6 * dp
    private fun rect(i: Int): RectF {
        val n = buttons.size
        val h = (height - gap * (n - 1)) / n
        val top = i * (h + gap)
        return RectF(1f, top + 1f, width - 1f, top + h)
    }
    private fun indexAt(y: Float) = (0 until buttons.size).firstOrNull { rect(it).let { r -> y >= r.top - gap && y <= r.bottom + gap } } ?: -1

    override fun onDraw(c: Canvas) {
        for (i in buttons.indices) {
            val r = rect(i)
            paint.color = if (pressed == i) theme.keyPressed else theme.keyBg
            c.drawRoundRect(r, 8 * dp, 8 * dp, paint)
            text.color = theme.keyText
            text.textSize = minOf(r.height() * 0.34f, 15 * dp, r.width() / 6.5f)
            c.drawText(buttons[i].first, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = indexAt(e.y)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (pressed in buttons.indices) buttons[pressed].second()
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
