package com.ilukezippo.lapfold

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewTreeObserver
import android.widget.*

/**
 * Settings screen. In Samsung split screen it is also the "pane" the panel is drawn over:
 * put your app on top and LapFold on the bottom.
 */
class MainActivity : Activity() {
    companion object { const val EXTRA_NO_AUTO_ON = "no_auto_on" }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var toggleBtn: Button
    private val dp get() = resources.displayMetrics.density

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { reportPane() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        window.statusBarColor = Color.parseColor("#1C1C1C")
        setContentView(buildUi())
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onResume() {
        super.onResume()
        val svc = LapFoldService.instance
        if (svc != null && !intent.getBooleanExtra(EXTRA_NO_AUTO_ON, false)) svc.turnOn()
        intent.removeExtra(EXTRA_NO_AUTO_ON)
        reportPane()
        refresh()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i) }

    override fun onStop() {
        super.onStop()
        LapFoldService.instance?.setPane(null)
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(inMulti: Boolean, c: Configuration) {
        super.onMultiWindowModeChanged(inMulti, c)
        window.decorView.post { reportPane() }
    }

    /** Tell the service where our split-screen pane is so the keyboard fills exactly that. */
    private fun reportPane() {
        val svc = LapFoldService.instance ?: return
        if (!isInMultiWindowMode) { svc.setPane(null); return }
        val v = window.decorView
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (v.width == 0) return
        svc.setPane(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
    }

    private fun refresh() {
        val svc = LapFoldService.instance
        enableBtn.text = if (svc == null) "1. Allow LapFold in Accessibility" else "✓ Accessibility allowed"
        enableBtn.isEnabled = svc == null
        toggleBtn.isEnabled = svc != null
        toggleBtn.text = if (svc?.isOn == true) "Turn LapFold off" else "Turn LapFold on"
        status.text = when {
            svc == null -> "LapFold needs the Accessibility permission to type and move the pointer. Gboard stays your keyboard."
            svc.isOn -> "On — keyboard and trackpad are on the bottom half."
            else -> "Off — use the notification, the Quick Settings tile, or open this app to turn it on."
        }
    }

    // ---------- UI ----------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#2B2B2B")); cornerRadius = 10 * dp }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
    }

    private fun label(t: String, size: Float = 15f, bold: Boolean = false, color: String = "#F3F3F3") =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.parseColor("#0078D4")); cornerRadius = 6 * dp }
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (top * dp).toInt() }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt(); setPadding(p, p, p, p)
        }
        col.addView(label("LapFold", 28f, true))
        col.addView(label("Turn your Fold into a laptop", 14f, color = "#A0A0A0"))

        status = label("", 14f, color = "#CFCFCF")
        col.addView(status, lp(16))

        enableBtn = btn("") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        col.addView(enableBtn, lp(16))
        toggleBtn = btn("") { LapFoldService.instance?.toggle(); refresh() }
        col.addView(toggleBtn, lp(10))

        val look = card()
        look.addView(label("Look", 16f, true))
        val themeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val dark = RadioButton(this).apply { text = "Dark"; setTextColor(Color.WHITE); id = 101 }
        val light = RadioButton(this).apply { text = "Light"; setTextColor(Color.WHITE); id = 102 }
        themeGroup.addView(dark); themeGroup.addView(light)
        themeGroup.check(if (prefs.dark) 101 else 102)
        themeGroup.setOnCheckedChangeListener { _, id -> prefs.dark = id == 101; LapFoldService.instance?.applyTheme() }
        look.addView(themeGroup, lp(6))
        col.addView(look, lp(18))

        val pad = card()
        pad.addView(label("Trackpad", 16f, true))
        val speedLabel = label("Pointer speed", 14f, color = "#CFCFCF")
        pad.addView(speedLabel, lp(8))
        val seek = SeekBar(this).apply {
            max = 30; progress = ((prefs.speed - 0.4f) * 20).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, u: Boolean) { prefs.speed = 0.4f + v / 20f }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        pad.addView(seek, lp(4))
        val natural = Switch(this).apply {
            text = "Natural scrolling (content follows fingers)"; setTextColor(Color.WHITE)
            isChecked = prefs.naturalScroll
            setOnCheckedChangeListener { _, c -> prefs.naturalScroll = c }
        }
        pad.addView(natural, lp(8))
        col.addView(pad, lp(14))

        val help = card()
        help.addView(label("How to use", 16f, true))
        help.addView(label(
            "• Turn on/off: notification button, Quick Settings tile (add \"LapFold\"), or open this app.\n" +
            "• Laptop look: open Samsung split screen with your app (e.g. Splashtop) on top and LapFold on the bottom. " +
            "The keyboard fills LapFold's half. Without split screen it covers the bottom half.\n" +
            "• Tap a text box in the top app, then type. Gboard stays hidden while LapFold is on.\n" +
            "• Trackpad: 1 finger moves the pointer, tap = click, 2-finger tap or right button = right-click (long press), " +
            "2-finger drag = scroll, hold left button + move = drag.\n" +
            "• Shift/Ctrl/Alt/Win: tap once for the next key, twice to lock, again to release.\n" +
            "• Splashtop: use its touch mode so clicks land where the pointer is.\n" +
            "• ⏻ key turns LapFold off.",
            13.5f, color = "#CFCFCF"), lp(6))
        col.addView(help, lp(14))

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1C1C1C"))
            addView(col)
        }
    }
}
