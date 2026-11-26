package com.ilukezippo.lapfold

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lapfold", Context.MODE_PRIVATE)
    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()
    var speed: Float
        get() = sp.getFloat("speed", 1.0f)
        set(v) = sp.edit().putFloat("speed", v).apply()
    var autoSplit: Boolean
        get() = sp.getBoolean("autosplit", true)
        set(v) = sp.edit().putBoolean("autosplit", v).apply()
    /** 0 = auto (Splashtop), 1 = always, 2 = off */
    var remoteMode: Int
        get() = sp.getInt("remote", 0)
        set(v) = sp.edit().putInt("remote", v).apply()
    var layout: PanelLayout
        get() = PanelLayout(
            sp.getInt("l_h", 50), sp.getInt("l_w", 100), sp.getInt("l_kb", 62),
            sp.getInt("l_pad", 45), sp.getInt("l_strip", 6), sp.getInt("l_btn", 13),
        ).clamped()
        set(v) { val c = v.clamped(); sp.edit().putInt("l_h", c.heightPct).putInt("l_w", c.widthPct)
            .putInt("l_kb", c.keyboardPct).putInt("l_pad", c.trackpadPct).putInt("l_strip", c.stripPct)
            .putInt("l_btn", c.buttonsPct).apply() }
    /** Top-left of the panel when moved by hand; null = docked at the bottom. */
    var floatPos: Pair<Int, Int>?
        get() = if (sp.contains("f_x")) Pair(sp.getInt("f_x", 0), sp.getInt("f_y", 0)) else null
        set(v) { if (v == null) sp.edit().remove("f_x").remove("f_y").apply()
                 else sp.edit().putInt("f_x", v.first).putInt("f_y", v.second).apply() }
    var naturalScroll: Boolean
        get() = sp.getBoolean("natural", false)
        set(v) = sp.edit().putBoolean("natural", v).apply()
}

data class Theme(
    val panelBg: Int, val keyBg: Int, val keyPressed: Int, val keyText: Int,
    val keySubText: Int, val keyEdge: Int, val accent: Int, val padBg: Int, val padBorder: Int, val buttonDivider: Int,
) {
    companion object {
        val DARK = Theme(
            panelBg = 0xFF1C1C1C.toInt(), keyBg = 0xFF2D2D2D.toInt(), keyPressed = 0xFF4A4A4A.toInt(),
            keyText = 0xFFF3F3F3.toInt(), keySubText = 0xFFA0A0A0.toInt(), keyEdge = 0xFF141414.toInt(), accent = 0xFF0078D4.toInt(),
            padBg = 0xFF262626.toInt(), padBorder = 0xFF3A3A3A.toInt(), buttonDivider = 0xFF3A3A3A.toInt(),
        )
        val LIGHT = Theme(
            panelBg = 0xFFE9E9E9.toInt(), keyBg = 0xFFFFFFFF.toInt(), keyPressed = 0xFFCFCFCF.toInt(),
            keyText = 0xFF1B1B1B.toInt(), keySubText = 0xFF6B6B6B.toInt(), keyEdge = 0xFFC2C2C2.toInt(), accent = 0xFF0078D4.toInt(),
            padBg = 0xFFF7F7F7.toInt(), padBorder = 0xFFC8C8C8.toInt(), buttonDivider = 0xFFC8C8C8.toInt(),
        )
    }
}
