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
    private val winIcon = BitmapFactory.decodeResource(resources, R.drawable.win_key)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys(w, h)

    private fun layoutKeys(w: Int, h: Int) {
        rects.clear()
        val gap = (minOf(w, h) * 0.006f).coerceIn(2f * dp, 5f * dp)
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
            if (!isPressed) {   // subtle keycap edge, like Windows 11 keys
                val base = paint.color
                paint.color = theme.keyEdge
                c.drawRoundRect(rect.left, rect.top + 1.5f * dp, rect.right, rect.bottom + 1.5f * dp, r, r, paint)
                paint.color = base
            }
            c.drawRoundRect(rect, r, r, paint)
            if (k.id == "win") {   // Windows key: icon tinted to the key text colour
                iconPaint.colorFilter = PorterDuffColorFilter(if (active) Color.WHITE else theme.keyText, PorterDuff.Mode.SRC_IN)
                val sz = minOf(rect.height(), rect.width()) * 0.42f
                val dst = RectF(rect.centerX() - sz / 2, rect.centerY() - sz / 2, rect.centerX() + sz / 2, rect.centerY() + sz / 2)
                c.drawBitmap(winIcon, null, dst, iconPaint)
                if (k.mod != null && mods.state(k.mod) == ModState.LOCKED) {
                    paint.color = Color.WHITE
                    c.drawCircle(rect.right - 6 * dp, rect.top + 6 * dp, 2.5f * dp, paint)
                }
                continue
            }
            val label = KeyLayout.labelFor(k, shift, mods.caps)
            text.color = if (active) Color.WHITE else theme.keyText
            when {
                k.small -> fitText(label, rect, 0.38f, 15f)
                label.length > 1 -> fitText(label, rect, 0.30f, 16f)
                else -> fitText(label, rect, 0.42f, 24f)
            }
            c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            // show the shifted symbol small in the corner, like real keycaps
            if (!shift && k.shiftLabel != null) {
                text.color = theme.keySubText
                text.textSize = minOf(rect.height() * 0.24f, 12 * dp)
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

    // the keyboard can be hidden or turned off while a key is held: stop repeating
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatTasks.values.forEach { handler.removeCallbacks(it) }
        repeatTasks.clear(); pressed.clear()
    }

    /** Fit a label inside its key: by height, capped in dp, and shrunk if too wide. */
    private fun fitText(label: String, rect: RectF, heightShare: Float, maxDp: Float) {
        text.textSize = minOf(rect.height() * heightShare, maxDp * dp)
        val w = text.measureText(label)
        val room = rect.width() * 0.82f
        if (w > room && w > 0f) text.textSize *= room / w
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
