package com.ilukezippo.lapfold

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/**
 * Injects touches at screen coordinates. Continuous gestures (drag, scroll) are built from
 * short continued strokes so the finger stays "down" while the user keeps moving.
 */
class GestureEngine(private val svc: AccessibilityService) {
    private val seg = 16L
    private var stroke: StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pendX: Float? = null
    private var pendY: Float? = null
    private var ending = false
    private var busy = false
    val active get() = stroke != null

    fun tap(x: Float, y: Float) = single(x, y, 40)
    fun longPress(x: Float, y: Float) = single(x, y, 650)

    private fun single(x: Float, y: Float, dur: Long) {
        cancel()
        val p = Path().apply { moveTo(x, y) }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(StrokeDescription(p, 0, dur)).build(), null, null)
    }

    fun begin(x: Float, y: Float) {
        cancel()
        lastX = x; lastY = y; ending = false
        val p = Path().apply { moveTo(x, y) }
        stroke = StrokeDescription(p, 0, seg, true)
        send(stroke!!)
    }

    fun move(x: Float, y: Float) {
        if (stroke == null) return
        pendX = x; pendY = y
        if (!busy) flush()
    }

    fun end() {
        if (stroke == null) return
        ending = true
        if (!busy) flush()
    }

    fun cancel() {
        stroke = null; pendX = null; pendY = null; ending = false; busy = false
    }

    private fun flush() {
        val s = stroke ?: return
        val tx = pendX ?: lastX
        val ty = pendY ?: lastY
        pendX = null; pendY = null
        val cont = !ending
        val p = Path().apply { moveTo(lastX, lastY); lineTo(tx, ty) }
        lastX = tx; lastY = ty
        val next = try { s.continueStroke(p, 0, seg, cont) } catch (e: Exception) { cancel(); return }
        stroke = if (cont) next else null
        if (!cont) ending = false
        send(next)
    }

    private fun send(s: StrokeDescription) {
        busy = true
        val ok = svc.dispatchGesture(GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    busy = false
                    // keep the finger down (and follow movement) while the gesture is active
                    if (stroke != null) flush()
                }
                override fun onCancelled(g: GestureDescription) { busy = false; stroke = null }
            }, null)
        if (!ok) { busy = false; stroke = null }
    }
}
