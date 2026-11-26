package com.ilukezippo.lapfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

interface PadListener {
    fun onMove(dx: Float, dy: Float)
    fun onClick()
    fun onRightClick()
    fun onScrollStart()
    fun onScroll(dx: Float, dy: Float)
    fun onScrollEnd()
    fun onDragStart()
    fun onDragEnd()
}

/** Laptop trackpad with left/right buttons along the bottom edge. */
@SuppressLint("ViewConstructor")
class TrackpadView(ctx: Context, private val listener: PadListener) : View(ctx) {
    var theme: Theme = Theme.DARK
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonsTop get() = height * 0.80f

    private val last = mutableMapOf<Int, PointF>()          // pad fingers
    private var padDownTime = 0L
    private var travel = 0f
    private var maxFingers = 0
    private var scrolling = false
    private var gestureUsed = false   // a 2-finger gesture happened; don't treat leftover finger as tap

    private var leftId = -1
    private var rightId = -1
    private var dragging = false
    private var pressedLeft = false
    private var pressedRight = false

    override fun onDraw(c: Canvas) {
        val r = 10f * dp
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        paint.style = Paint.Style.FILL
        paint.color = theme.padBg
        c.drawRoundRect(rect, r, r, paint)
        // pressed button highlight
        if (pressedLeft || pressedRight) {
            paint.color = theme.keyPressed
            val half = width / 2f
            val b = RectF(if (pressedLeft) 1f else half, buttonsTop, if (pressedLeft) half else width - 1f, height - 1f)
            c.save(); c.clipRect(b); c.drawRoundRect(rect, r, r, paint); c.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * dp
        paint.color = theme.padBorder
        c.drawRoundRect(rect, r, r, paint)
        paint.color = theme.buttonDivider
        c.drawLine(8 * dp, buttonsTop, width - 8 * dp, buttonsTop, paint)
        c.drawLine(width / 2f, buttonsTop, width / 2f, height - 1f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                if (y >= buttonsTop) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (x < width / 2f) { leftId = id; pressedLeft = true } else { rightId = id; pressedRight = true }
                    invalidate()
                } else {
                    if (last.isEmpty()) {
                        padDownTime = SystemClock.uptimeMillis(); travel = 0f; maxFingers = 0
                        scrolling = false; gestureUsed = false
                    }
                    last[id] = PointF(x, y)
                    maxFingers = maxOf(maxFingers, last.size)
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleUp(id)
            MotionEvent.ACTION_CANCEL -> {
                if (scrolling) listener.onScrollEnd()
                if (dragging) listener.onDragEnd()
                last.clear(); leftId = -1; rightId = -1; dragging = false; scrolling = false
                pressedLeft = false; pressedRight = false; invalidate()
            }
        }
        return true
    }

    private fun handleMove(e: MotionEvent) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            val pid = e.getPointerId(i)
            val p = last[pid] ?: continue
            val dx = e.getX(i) - p.x; val dy = e.getY(i) - p.y
            sx += dx; sy += dy; n++
            p.set(e.getX(i), e.getY(i))
        }
        if (n == 0) return
        val ax = sx / n; val ay = sy / n
        travel += hypot(ax, ay)
        if (n >= 2) {
            gestureUsed = true
            if (!scrolling && travel > 10 * dp) { scrolling = true; listener.onScrollStart() }
            if (scrolling) listener.onScroll(ax, ay)
        } else if (!scrolling) {
            if (leftId != -1 && !dragging && travel > 4 * dp) { dragging = true; listener.onDragStart() }
            if (abs(ax) + abs(ay) > 0f) listener.onMove(ax, ay)
        }
    }

    private fun handleUp(id: Int) {
        when (id) {
            leftId -> {
                leftId = -1; pressedLeft = false
                if (dragging) { dragging = false; listener.onDragEnd() } else listener.onClick()
                invalidate(); return
            }
            rightId -> {
                rightId = -1; pressedRight = false
                listener.onRightClick(); invalidate(); return
            }
        }
        if (last.remove(id) == null) return
        if (last.isNotEmpty()) {
            // a finger lifted during a 2-finger gesture
            if (scrolling) { scrolling = false; listener.onScrollEnd() }
            return
        }
        val quick = SystemClock.uptimeMillis() - padDownTime < 250 && travel < 10 * dp
        when {
            scrolling -> { scrolling = false; listener.onScrollEnd() }
            quick && maxFingers >= 2 -> listener.onRightClick()
            quick && !gestureUsed && leftId == -1 -> listener.onClick()
        }
    }
}
