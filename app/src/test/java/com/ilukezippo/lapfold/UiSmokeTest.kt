package com.ilukezippo.lapfold

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class UiSmokeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun draw(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.draw(Canvas(Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)))
    }

    private val pad = object : PadListener {
        override fun onMove(dx: Float, dy: Float) {}
        override fun onClick() {}
        override fun onRightClick() {}
        override fun onScrollStart() {}
        override fun onScroll(dx: Float, dy: Float) {}
        override fun onScrollEnd() {}
        override fun onDragStart() {}
        override fun onDragEnd() {}
    }
    private val keys = object : KeyListener {
        override fun onKey(key: Key) {}
        override fun onModifier(mod: Mod) {}
        override fun onCaps() {}
        override fun onHide() {}
    }

    @Test fun viewsDrawAtManySizes() {
        for ((w, h) in listOf(0 to 0, 1 to 1, 300 to 200, 1080 to 600, 2208 to 1000, 2800 to 1400)) {
            draw(KeyboardView(ctx, Modifiers(), keys), w, h)
            draw(TrackpadView(ctx, pad), w, h)
            draw(ScrollStripView(ctx, pad), w, h)
            draw(NavButtonsView(ctx, listOf("◀ Back" to {}, "Forward ▶" to {}, "PrtSc" to {})), w, h)
        }
    }

    @Test fun mainActivityStarts() {
        Robolectric.buildActivity(MainActivity::class.java).setup().pause().stop().destroy()
    }

    @Test fun mainActivityLightThemeAndRecreate() {
        Prefs(ctx).dark = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        c.recreate()
        Prefs(ctx).dark = true
        c.recreate()
    }

    @Test fun mainActivityStartsWithErrorCard() {
        CrashLog.write(ctx, RuntimeException("test"))
        Robolectric.buildActivity(MainActivity::class.java).setup()
        CrashLog.clear(ctx)
    }
}
