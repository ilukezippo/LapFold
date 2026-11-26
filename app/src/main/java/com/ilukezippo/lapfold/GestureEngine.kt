package com.ilukezippo.lapfold

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/**
 * Injects touches at screen coordinates. Continuous gestures (drag, scroll, relayed trackpad
 * movement) are built from short continued strokes so the fingers stay "down" while moving.
 * Supports 1 or 2 fingers (2 fingers = side by side, [spread] px apart).
 */
class GestureEngine(private val svc: AccessibilityService) {
    private val seg = 16L
    private var strokes: List<StrokeDescription>? = null
    private var offsets: List<Float> = listOf(0f)   // x offset of each finger
    private var lastX = 0f
    private var lastY = 0f
    private var pendX: Float? = null
    private var pendY: Float? = null
    private var ending = false
    private var busy = false
    val active get() = strokes != null
    val x get() = pendX ?: lastX
    val y get() = pendY ?: lastY

    fun tap(x: Float, y: Float, fingers: Int = 1, spread: Float = 0f) = single(x, y, 40, fingers, spread)
    fun longPress(x: Float, y: Float) = single(x, y, 650, 1, 0f)

    private fun single(x: Float, y: Float, dur: Long, fingers: Int, spread: Float) {
        cancel()
        val b = GestureDescription.Builder()
        for (o in offs(fingers, spread)) b.addStroke(StrokeDescription(Path().apply { moveTo(x + o, y) }, 0, dur))
        svc.dispatchGesture(b.build(), null, null)
    }

    private fun offs(fingers: Int, spread: Float) =
        if (fingers <= 1) listOf(0f) else listOf(-spread / 2, spread / 2)

    fun begin(x: Float, y: Float, fingers: Int = 1, spread: Float = 0f) {
        cancel()
        offsets = offs(fingers, spread)
        lastX = x; lastY = y; ending = false
        strokes = offsets.map { o -> StrokeDescription(Path().apply { moveTo(x + o, y) }, 0, seg, true) }
        send(strokes!!)
    }

    fun move(x: Float, y: Float) {
        if (strokes == null) return
        pendX = x; pendY = y
        if (!busy) flush()
    }

    fun end() {
        if (strokes == null) return
        ending = true
        if (!busy) flush()
    }

    fun cancel() {
        strokes = null; pendX = null; pendY = null; ending = false; busy = false
    }

    private fun flush() {
        val ss = strokes ?: return
        val tx = pendX ?: lastX
        val ty = pendY ?: lastY
        pendX = null; pendY = null
        val cont = !ending
        val next = try {
            ss.mapIndexed { i, s ->
                val o = offsets[i]
                s.continueStroke(Path().apply { moveTo(lastX + o, lastY); lineTo(tx + o, ty) }, 0, seg, cont)
            }
        } catch (e: Exception) { cancel(); return }
        lastX = tx; lastY = ty
        strokes = if (cont) next else null
        if (!cont) ending = false
        send(next)
    }

    private fun send(ss: List<StrokeDescription>) {
        busy = true
        val b = GestureDescription.Builder()
        ss.forEach { b.addStroke(it) }
        val ok = svc.dispatchGesture(b.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    busy = false
                    // keep the fingers down (and follow movement) while the gesture is active
                    if (strokes != null) flush()
                }
                override fun onCancelled(g: GestureDescription) { busy = false; strokes = null }
            }, null)
        if (!ok) { busy = false; strokes = null }
    }
}
