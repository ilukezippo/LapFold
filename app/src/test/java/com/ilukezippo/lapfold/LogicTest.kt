package com.ilukezippo.lapfold

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun key(id: String) = KeyLayout.rows.flatten().first { it.id == id }

    @Test fun letterLabelFollowsShiftXorCaps() {
        val a = key("a")
        assertEquals("a", KeyLayout.labelFor(a, shift = false, caps = false))
        assertEquals("A", KeyLayout.labelFor(a, shift = true, caps = false))
        assertEquals("A", KeyLayout.labelFor(a, shift = false, caps = true))
        assertEquals("a", KeyLayout.labelFor(a, shift = true, caps = true))
    }

    @Test fun symbolLabelUsesShiftLabelAndIgnoresCaps() {
        val one = key("1")
        assertEquals("1", KeyLayout.labelFor(one, shift = false, caps = true))
        assertEquals("!", KeyLayout.labelFor(one, shift = true, caps = false))
    }

    @Test fun layoutHasWindowsKeys() {
        val ids = KeyLayout.rows.flatten().map { it.id }.toSet()
        for (id in listOf("esc", "tab", "caps", "lshift", "lctrl", "win", "lalt", "space", "enter", "bksp", "left", "up", "down", "right", "f1", "f12", "del")) {
            assertTrue("missing $id", id in ids)
        }
        assertEquals(KeyEvent.KEYCODE_META_LEFT, key("win").keyCode)
    }

    @Test fun modifierOneShotThenLockThenOff() {
        val m = Modifiers()
        m.tap(Mod.SHIFT)
        assertEquals(ModState.ONESHOT, m.state(Mod.SHIFT))
        m.tap(Mod.SHIFT)
        assertEquals(ModState.LOCKED, m.state(Mod.SHIFT))
        m.tap(Mod.SHIFT)
        assertEquals(ModState.OFF, m.state(Mod.SHIFT))
    }

    @Test fun oneShotClearsAfterKeyButLockStays() {
        val m = Modifiers()
        m.tap(Mod.CTRL)
        m.tap(Mod.SHIFT); m.tap(Mod.SHIFT)
        assertTrue(m.metaState() and KeyEvent.META_CTRL_ON != 0)
        assertTrue(m.metaState() and KeyEvent.META_SHIFT_ON != 0)
        m.consumeOneShots()
        assertEquals(ModState.OFF, m.state(Mod.CTRL))
        assertEquals(ModState.LOCKED, m.state(Mod.SHIFT))
        assertEquals(0, m.metaState() and KeyEvent.META_CTRL_ON)
    }

    @Test fun pointerClampsInsideArea() {
        val area = Area(0f, 0f, 1000f, 500f)
        assertEquals(Pair(0f, 0f), PointerMath.clamp(-20f, -5f, area))
        assertEquals(Pair(999f, 499f), PointerMath.clamp(5000f, 900f, area))
        assertEquals(Pair(10f, 20f), PointerMath.clamp(10f, 20f, area))
    }

    @Test fun accelerationGrowsWithSpeed() {
        val slow = PointerMath.accel(2f, 1f)
        val fast = PointerMath.accel(40f, 1f)
        assertTrue(slow >= 2f)
        assertTrue(fast / 40f > slow / 2f)
        assertEquals(-PointerMath.accel(10f, 1f), PointerMath.accel(-10f, 1f), 0.0001f)
    }
}

class UpdateTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("v1.0.10", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("v0.9.9", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.0.0"))
    }
}

class PanelLayoutTest {
    @Test fun defaultsAreLaptopLike() {
        val d = PanelLayout.DEFAULT
        assertEquals(50, d.heightPct); assertEquals(100, d.widthPct); assertEquals(62, d.keyboardPct)
    }
    @Test fun clampKeepsValuesUsable() {
        val c = PanelLayout(heightPct = 5, widthPct = 300, keyboardPct = 99, trackpadPct = 0, stripPct = 50, buttonsPct = 1).clamped()
        assertEquals(PanelLayout(25, 100, 85, 20, 15, 6), c)
    }
    @Test fun editorFieldsRoundTrip() {
        var l = PanelLayout.DEFAULT
        for ((_, range, acc) in PanelLayout.FIELDS) {
            l = acc.second(l, range.first)
            assertEquals(range.first, acc.first(l))
        }
    }
}

class ClampEdgeTest {
    @Test fun tinyAreaDoesNotCrash() {
        val a = Area(10f, 10f, 10.5f, 10f)
        assertEquals(Pair(10f, 10f), PointerMath.clamp(50f, -50f, a))
    }
}
