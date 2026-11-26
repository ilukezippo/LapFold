package com.ilukezippo.lapfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

interface KeyListener {
    fun onKey(key: Key)
    fun onModifier(mod: Mod)
    fun onCaps()
    fun onHide()
}

/** Windows-style keyboard drawn on a canvas. Keys fire on press, like a real keyboard. */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val mods: Modifiers, private val listener: KeyListener) : View(ctx) {
    var theme: Theme = Theme.DARK
        set(v) { field = v; invalidate() }

    private val rects = mutableListOf<Pair<Key, RectF>>()
    private val pressed = mutableMapOf<Int, Key>()   // pointerId -> key
    private val handler = Handler(Looper.getMainLooper())
    private val repeatTasks = mutableMapOf<Int, Runnable>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val dp = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys(w, h)

    private fun layoutKeys(w: Int, h: Int) {
        rects.clear()
        val gap = 4f * dp
        val rows = KeyLayout.rows
        // function row is shorter, like a laptop
        val weights = rows.map { r -> if (r.first().small) 0.7f else 1f }
        val unitH = (h - gap * (rows.size + 1)) / weights.sum()
        var y = gap
        rows.forEachIndexed { i, row ->
            val rh = unitH * weights[i]
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            val unitW = (w - gap * (row.size + 1)) / total
            var x = gap
            for (k in row) {
                val kw = unitW * k.width
                rects += k to RectF(x, y, x + kw, y + rh)
                x += kw + gap
            }
            y += rh + gap
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.panelBg)
        val shift = mods.isActive(Mod.SHIFT)
        val r = 5f * dp
        for ((k, rect) in rects) {
            val isPressed = pressed.containsValue(k)
            val active = (k.mod != null && mods.isActive(k.mod)) || (k.kind == KeyKind.CAPS && mods.caps)
            paint.color = when {
                isPressed -> theme.keyPressed
                active -> theme.accent
                else -> theme.keyBg
            }
            c.drawRoundRect(rect, r, r, paint)
            val label = KeyLayout.labelFor(k, shift, mods.caps)
            text.color = if (active) Color.WHITE else theme.keyText
            text.textSize = when {
                k.small -> rect.height() * 0.38f
                label.length > 1 -> rect.height() * 0.30f
                else -> rect.height() * 0.42f
            }
            c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            // show the shifted symbol small in the corner, like real keycaps
            if (!shift && k.shiftLabel != null) {
                text.color = theme.keySubText
                text.textSize = rect.height() * 0.24f
                c.drawText(k.shiftLabel, rect.left + rect.width() * 0.22f, rect.top + rect.height() * 0.3f, text)
            }
            if (k.mod != null && mods.state(k.mod) == ModState.LOCKED) {
                paint.color = Color.WHITE
                c.drawCircle(rect.right - 6 * dp, rect.top + 6 * dp, 2.5f * dp, paint)
            }
        }
    }

    private fun keyAt(x: Float, y: Float) = rects.firstOrNull { it.second.contains(x, y) }?.first

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keyAt(e.getX(idx), e.getY(idx)) ?: return true
                pressed[id] = k
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                fire(k)
                if (k.repeats) {
                    val task = object : Runnable {
                        override fun run() { fire(k); handler.postDelayed(this, 45) }
                    }
                    repeatTasks[id] = task
                    handler.postDelayed(task, 420)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(id)
            MotionEvent.ACTION_CANCEL -> { pressed.keys.toList().forEach { release(it) } }
        }
        return true
    }

    private fun release(id: Int) {
        repeatTasks.remove(id)?.let { handler.removeCallbacks(it) }
        pressed.remove(id)
        invalidate()
    }

    private fun fire(k: Key) {
        when (k.kind) {
            KeyKind.MODIFIER -> listener.onModifier(k.mod!!)
            KeyKind.CAPS -> listener.onCaps()
            KeyKind.HIDE -> listener.onHide()
            KeyKind.NORMAL -> listener.onKey(k)
        }
        invalidate()
    }
}
