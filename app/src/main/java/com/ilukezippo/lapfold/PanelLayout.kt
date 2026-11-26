package com.ilukezippo.lapfold

/** Sizes the user can change in Edit mode, all in percent. */
data class PanelLayout(
    val heightPct: Int = 50,     // panel height, % of screen height
    val widthPct: Int = 100,     // panel width, % of screen width
    val keyboardPct: Int = 62,   // keyboard share of the panel height (rest = trackpad row)
    val trackpadPct: Int = 45,   // trackpad width, % of panel width
    val stripPct: Int = 6,       // scroll strip width, % of panel width
    val buttonsPct: Int = 13,    // side buttons width, % of panel width
) {
    fun clamped() = PanelLayout(
        heightPct.coerceIn(25, 70), widthPct.coerceIn(45, 100), keyboardPct.coerceIn(40, 85),
        trackpadPct.coerceIn(20, 70), stripPct.coerceIn(3, 15), buttonsPct.coerceIn(6, 25),
    )

    companion object {
        val DEFAULT = PanelLayout()
        /** Slider rows shown in Edit mode: label, range and how to read / write the value. */
        val FIELDS: List<Triple<String, IntRange, Pair<(PanelLayout) -> Int, (PanelLayout, Int) -> PanelLayout>>> = listOf(
            Triple("Height", 25..70, Pair({ l -> l.heightPct }, { l, v -> l.copy(heightPct = v) })),
            Triple("Width", 45..100, Pair({ l -> l.widthPct }, { l, v -> l.copy(widthPct = v) })),
            Triple("Keyboard", 40..85, Pair({ l -> l.keyboardPct }, { l, v -> l.copy(keyboardPct = v) })),
            Triple("Trackpad", 20..70, Pair({ l -> l.trackpadPct }, { l, v -> l.copy(trackpadPct = v) })),
            Triple("Scroll strip", 3..15, Pair({ l -> l.stripPct }, { l, v -> l.copy(stripPct = v) })),
            Triple("Buttons", 6..25, Pair({ l -> l.buttonsPct }, { l, v -> l.copy(buttonsPct = v) })),
        )
    }
}
