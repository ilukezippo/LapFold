package com.ilukezippo.lapfold

import kotlin.math.abs
import kotlin.math.min

data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

object PointerMath {
    fun clamp(x: Float, y: Float, a: Area): Pair<Float, Float> =
        Pair(x.coerceIn(a.left, a.right - 1f), y.coerceIn(a.top, a.bottom - 1f))

    /** Windows-like pointer acceleration: faster finger movement covers more distance. */
    fun accel(delta: Float, sensitivity: Float): Float {
        val gain = sensitivity * (1.2f + min(abs(delta) / 12f, 2.5f))
        return delta * gain
    }
}
