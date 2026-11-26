package com.ilukezippo.lapfold

import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceSmokeTest {
    private fun connected(): LapFoldService {
        val svc = Robolectric.setupService(LapFoldService::class.java)
        val m = LapFoldService::class.java.getDeclaredMethod("onServiceConnected")
        m.isAccessible = true; m.invoke(svc)
        return svc
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun call(svc: LapFoldService, name: String) {
        val m = LapFoldService::class.java.getDeclaredMethod(name); m.isAccessible = true; m.invoke(svc)
    }

    @Test fun fullLifecycle() {
        val svc = connected()
        svc.turnOn(); idle()
        svc.onKey(KeyLayout.rows[2][1])                 // no text box focused: must not crash
        svc.onModifier(Mod.CTRL); svc.onCaps()
        svc.onMove(30f, -20f); svc.onClick(); svc.onRightClick()
        svc.onScrollStart(); svc.onScroll(0f, 40f); svc.onScrollEnd()
        svc.onDragStart(); svc.onMove(5f, 5f); svc.onDragEnd()
        call(svc, "toggleEditor"); idle(); call(svc, "toggleEditor")
        call(svc, "mouseBack"); call(svc, "printScreen")
        svc.setPane(Rect(0, 1000, 1000, 2000)); svc.setPane(null)
        svc.collapse(); idle(); svc.expand(); idle()
        svc.applyTheme()
        svc.turnOff(); idle()
        svc.turnOn(); svc.collapse(); svc.turnOff(); idle()
    }

    @Test fun floatingAndOddLayouts() {
        val svc = connected()
        val p = Prefs(svc)
        p.floatPos = Pair(-5000, 99999)
        p.layout = PanelLayout(70, 45, 85, 70, 15, 25)
        svc.turnOn(); idle()
        p.layout = PanelLayout(25, 100, 40, 20, 3, 6)
        call(svc, "relayout"); idle()
        svc.turnOff()
        p.floatPos = null
    }
}
