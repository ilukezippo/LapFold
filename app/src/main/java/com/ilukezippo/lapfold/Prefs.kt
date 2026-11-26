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
    var naturalScroll: Boolean
        get() = sp.getBoolean("natural", false)
        set(v) = sp.edit().putBoolean("natural", v).apply()
}

data class Theme(
    val panelBg: Int, val keyBg: Int, val keyPressed: Int, val keyText: Int,
    val keySubText: Int, val accent: Int, val padBg: Int, val padBorder: Int, val buttonDivider: Int,
) {
    companion object {
        val DARK = Theme(
            panelBg = 0xFF1C1C1C.toInt(), keyBg = 0xFF2D2D2D.toInt(), keyPressed = 0xFF4A4A4A.toInt(),
            keyText = 0xFFF3F3F3.toInt(), keySubText = 0xFFA0A0A0.toInt(), accent = 0xFF0078D4.toInt(),
            padBg = 0xFF262626.toInt(), padBorder = 0xFF3A3A3A.toInt(), buttonDivider = 0xFF3A3A3A.toInt(),
        )
        val LIGHT = Theme(
            panelBg = 0xFFE9E9E9.toInt(), keyBg = 0xFFFFFFFF.toInt(), keyPressed = 0xFFCFCFCF.toInt(),
            keyText = 0xFF1B1B1B.toInt(), keySubText = 0xFF6B6B6B.toInt(), accent = 0xFF0078D4.toInt(),
            padBg = 0xFFF7F7F7.toInt(), padBorder = 0xFFC8C8C8.toInt(), buttonDivider = 0xFFC8C8C8.toInt(),
        )
    }
}
