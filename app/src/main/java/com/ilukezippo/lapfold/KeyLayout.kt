package com.ilukezippo.lapfold

import android.view.KeyEvent.*

enum class Mod(val meta: Int) {
    SHIFT(META_SHIFT_ON or META_SHIFT_LEFT_ON),
    CTRL(META_CTRL_ON or META_CTRL_LEFT_ON),
    ALT(META_ALT_ON or META_ALT_LEFT_ON),
    WIN(META_META_ON or META_META_LEFT_ON),
}

enum class KeyKind { NORMAL, MODIFIER, CAPS, HIDE }

data class Key(
    val id: String,
    val label: String,
    val keyCode: Int,
    val shiftLabel: String? = null,
    val width: Float = 1f,
    val kind: KeyKind = KeyKind.NORMAL,
    val mod: Mod? = null,
    val repeats: Boolean = true,
    val small: Boolean = false,
) {
    val isLetter get() = label.length == 1 && label[0].isLetter()
}

object KeyLayout {
    private fun ch(c: String, code: Int, shift: String? = null) = Key(c, c, code, shift)
    private fun fn(n: Int) = Key("f$n", "F$n", KEYCODE_F1 + n - 1, small = true, repeats = false)
    private fun letters(s: String) = s.map { c -> ch(c.toString(), KEYCODE_A + (c - 'a')) }

    val rows: List<List<Key>> = listOf(
        listOf(Key("esc", "Esc", KEYCODE_ESCAPE, small = true, repeats = false)) +
            (1..12).map { fn(it) } +
            listOf(
                Key("del", "Del", KEYCODE_FORWARD_DEL, small = true),
                Key("hide", "⏻", 0, small = true, kind = KeyKind.HIDE, repeats = false),
            ),
        listOf(ch("`", KEYCODE_GRAVE, "~")) +
            "1234567890".mapIndexed { i, c -> ch(c.toString(), KEYCODE_0 + (c - '0'), "!@#$%^&*()"[i].toString()) } +
            listOf(ch("-", KEYCODE_MINUS, "_"), ch("=", KEYCODE_EQUALS, "+"),
                Key("bksp", "⌫ Backspace", KEYCODE_DEL, width = 2f)),
        listOf(Key("tab", "Tab", KEYCODE_TAB, width = 1.5f)) + letters("qwertyuiop") +
            listOf(ch("[", KEYCODE_LEFT_BRACKET, "{"), ch("]", KEYCODE_RIGHT_BRACKET, "}"),
                Key("\\", "\\", KEYCODE_BACKSLASH, "|", width = 1.5f)),
        listOf(Key("caps", "Caps", KEYCODE_CAPS_LOCK, width = 1.8f, kind = KeyKind.CAPS, repeats = false)) +
            letters("asdfghjkl") +
            listOf(ch(";", KEYCODE_SEMICOLON, ":"), ch("'", KEYCODE_APOSTROPHE, "\""),
                Key("enter", "Enter ⏎", KEYCODE_ENTER, width = 2.2f)),
        listOf(Key("lshift", "Shift", KEYCODE_SHIFT_LEFT, width = 2.3f, kind = KeyKind.MODIFIER, mod = Mod.SHIFT, repeats = false)) +
            letters("zxcvbnm") +
            listOf(ch(",", KEYCODE_COMMA, "<"), ch(".", KEYCODE_PERIOD, ">"), ch("/", KEYCODE_SLASH, "?"),
                Key("rshift", "Shift", KEYCODE_SHIFT_RIGHT, width = 1.7f, kind = KeyKind.MODIFIER, mod = Mod.SHIFT, repeats = false),
                Key("up", "▲", KEYCODE_DPAD_UP)),
        listOf(
            Key("lctrl", "Ctrl", KEYCODE_CTRL_LEFT, width = 1.3f, kind = KeyKind.MODIFIER, mod = Mod.CTRL, repeats = false),
            Key("win", "⊞", KEYCODE_META_LEFT, width = 1.2f, kind = KeyKind.MODIFIER, mod = Mod.WIN, repeats = false),
            Key("lalt", "Alt", KEYCODE_ALT_LEFT, width = 1.2f, kind = KeyKind.MODIFIER, mod = Mod.ALT, repeats = false),
            Key("space", "", KEYCODE_SPACE, width = 6.3f),
            Key("ralt", "Alt", KEYCODE_ALT_RIGHT, width = 1.1f, kind = KeyKind.MODIFIER, mod = Mod.ALT, repeats = false),
            Key("rctrl", "Ctrl", KEYCODE_CTRL_RIGHT, width = 1.1f, kind = KeyKind.MODIFIER, mod = Mod.CTRL, repeats = false),
            Key("left", "◀", KEYCODE_DPAD_LEFT),
            Key("down", "▼", KEYCODE_DPAD_DOWN),
            Key("right", "▶", KEYCODE_DPAD_RIGHT),
        ),
    )

    fun labelFor(key: Key, shift: Boolean, caps: Boolean): String = when {
        key.isLetter -> if (shift != caps) key.label.uppercase() else key.label
        shift && key.shiftLabel != null -> key.shiftLabel
        else -> key.label
    }
}
