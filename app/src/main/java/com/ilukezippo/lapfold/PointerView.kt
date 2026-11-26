package com.ilukezippo.lapfold

import android.content.Context
import android.graphics.*
import android.view.View

/** Classic Windows arrow cursor. Hot spot is the top-left corner of the view. */
class PointerView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    val sizePx = (26 * dp).toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.4f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        val u = dp
        moveTo(1 * u, 1 * u); lineTo(1 * u, 19 * u); lineTo(5.5f * u, 14.5f * u)
        lineTo(9 * u, 22 * u); lineTo(12 * u, 20.5f * u); lineTo(8.5f * u, 13.5f * u)
        lineTo(14.5f * u, 13.5f * u); close()
    }
    var held = false
        set(v) { field = v; fill.color = if (v) 0xFF9FD0FF.toInt() else Color.WHITE; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawPath(path, fill); c.drawPath(path, stroke)
    }
}
