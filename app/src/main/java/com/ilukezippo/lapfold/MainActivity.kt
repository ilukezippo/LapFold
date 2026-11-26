package com.ilukezippo.lapfold

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsetsController
import android.widget.*

/**
 * Settings screen. In Samsung split screen it is also the "pane" the panel is drawn over.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_NO_AUTO_ON = "no_auto_on"
        const val EXTRA_PANE = "pane"
        var current: MainActivity? = null
            private set
        private var checkedThisLaunch = false
    }

    /** Opened by LapFold itself as the bottom split-screen pane. */
    var isPane = false
        private set

    private lateinit var prefs: Prefs
    private lateinit var c: UiColors
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var setupCard: View
    private lateinit var toggleBtn: TextView
    private lateinit var updateStatus: TextView
    private val dp get() = resources.displayMetrics.density
    private val version by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { reportPane() }

    private data class UiColors(
        val bg: Int, val card: Int, val border: Int, val text: Int, val sub: Int,
        val accent: Int, val onAccent: Int, val chip: Int, val good: Int, val off: Int,
    )

    private fun colors(dark: Boolean) = if (dark) UiColors(
        bg = 0xFF202020.toInt(), card = 0xFF2B2B2B.toInt(), border = 0xFF3A3A3A.toInt(),
        text = 0xFFF3F3F3.toInt(), sub = 0xFFA8A8A8.toInt(), accent = 0xFF4CC2FF.toInt(),
        onAccent = 0xFF00213A.toInt(), chip = 0xFF373737.toInt(), good = 0xFF6CCB5F.toInt(), off = 0xFF8A8A8A.toInt(),
    ) else UiColors(
        bg = 0xFFF3F3F3.toInt(), card = 0xFFFFFFFF.toInt(), border = 0xFFE5E5E5.toInt(),
        text = 0xFF1B1B1B.toInt(), sub = 0xFF616161.toInt(), accent = 0xFF0067C0.toInt(),
        onAccent = 0xFFFFFFFF.toInt(), chip = 0xFFF0F0F0.toInt(), good = 0xFF0F7B0F.toInt(), off = 0xFF8A8A8A.toInt(),
    )

    override fun onCreate(b: Bundle?) {
        prefs = Prefs(this)
        setTheme(if (prefs.dark) android.R.style.Theme_DeviceDefault_NoActionBar
                 else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(b)
        c = colors(prefs.dark)
        current = this
        isPane = intent.getBooleanExtra(EXTRA_PANE, false)
        window.statusBarColor = c.bg
        window.navigationBarColor = c.bg
        window.insetsController?.setSystemBarsAppearance(
            if (prefs.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(buildUi())
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        if (!isPane && !checkedThisLaunch) { checkedThisLaunch = true; checkForUpdate(silent = true) }
    }

    override fun onResume() {
        super.onResume()
        // launched as the pane but split screen didn't happen: don't cover the user's app
        if (isPane && !isInMultiWindowMode) {
            window.decorView.postDelayed({ if (!isInMultiWindowMode) finishAndRemoveTask() }, 500)
        }
        val svc = LapFoldService.instance
        if (svc != null && !intent.getBooleanExtra(EXTRA_NO_AUTO_ON, false)) svc.turnOn()
        intent.removeExtra(EXTRA_NO_AUTO_ON)
        reportPane()
        refresh()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i); setIntent(i)
        isPane = i.getBooleanExtra(EXTRA_PANE, false)
    }

    override fun onStop() {
        super.onStop()
        LapFoldService.instance?.setPane(null)
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(inMulti: Boolean, cfg: Configuration) {
        super.onMultiWindowModeChanged(inMulti, cfg)
        window.decorView.post { reportPane() }
    }

    /** Tell the service where our split-screen pane is so the keyboard fills exactly that. */
    private fun reportPane() {
        val svc = LapFoldService.instance ?: return
        if (!isInMultiWindowMode) { svc.setPane(null); return }
        val v = window.decorView
        if (v.width == 0) return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        svc.setPane(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
    }

    private fun refresh() {
        val svc = LapFoldService.instance
        setupCard.visibility = if (svc == null) View.VISIBLE else View.GONE
        toggleBtn.visibility = if (svc == null) View.GONE else View.VISIBLE
        val on = svc?.isOn == true
        (statusDot.background as GradientDrawable).setColor(if (on) c.good else c.off)
        statusTitle.text = when { svc == null -> "Setup needed"; on -> "LapFold is on"; else -> "LapFold is off" }
        statusText.text = when {
            svc == null -> "Allow LapFold in Accessibility so it can type and move the pointer. Gboard stays your keyboard."
            on -> "Keyboard and trackpad are on the bottom half."
            else -> "Turn it on here, from the notification, or from the Quick Settings tile."
        }
        toggleBtn.text = if (on) "Turn off" else "Turn on"
        styleButton(toggleBtn, primary = !on)
    }

    // ---------- updates ----------

    private fun checkForUpdate(silent: Boolean) {
        if (!silent) updateStatus.text = "Checking…"
        UpdateChecker.fetchLatest { rel, err ->
            if (isFinishing || isDestroyed) return@fetchLatest
            when {
                rel != null && UpdateChecker.isNewer(rel.version, version) -> {
                    updateStatus.text = "Update available: ${rel.version}"
                    showUpdateDialog(rel)
                }
                rel != null -> updateStatus.text = "You're up to date"
                else -> updateStatus.text = if (silent) "" else (err ?: "Couldn't check")
            }
        }
    }

    private fun showUpdateDialog(rel: Release) {
        AlertDialog.Builder(this, if (prefs.dark) AlertDialog.THEME_DEVICE_DEFAULT_DARK else AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
            .setTitle("LapFold ${rel.version} is available")
            .setMessage(("You have v$version.\n\n" + rel.notes).trim())
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl ?: rel.pageUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---------- UI helpers ----------

    private fun px(v: Int) = (v * dp).toInt()

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        if (stroke != null) setStroke(px(1), stroke)
    }

    private fun text(t: String, size: Float, color: Int = c.text, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif" + if (bold) "-medium" else "", Typeface.NORMAL)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 10f, c.border)
        setPadding(px(18), px(16), px(18), px(16))
    }

    private fun section(t: String) = text(t.uppercase(), 12f, c.sub, bold = true).apply {
        letterSpacing = 0.06f; setPadding(px(4), 0, 0, px(8))
    }

    private fun styleButton(b: TextView, primary: Boolean) {
        b.background = if (primary) rounded(c.accent, 6f) else rounded(c.chip, 6f, c.border)
        b.setTextColor(if (primary) c.onAccent else c.text)
    }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(px(16), px(12), px(16), px(12))
        styleButton(this, primary)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px(top) }

    private fun divider() = View(this).apply { setBackgroundColor(c.border) }

    /** A settings row: title + subtitle on the left, a control on the right. */
    private fun row(title: String, sub: String?, control: View?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f))
        if (sub != null) col.addView(text(sub, 13f, c.sub))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (control != null) row.addView(control)
        return row
    }

    private fun switch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(c.chip, 6f, c.border)
            setPadding(px(3), px(3), px(3), px(3))
        }
        options.forEachIndexed { i, label ->
            val t = text(label, 14f, if (i == selected) c.onAccent else c.text, bold = i == selected).apply {
                gravity = Gravity.CENTER
                setPadding(px(16), px(7), px(16), px(7))
                if (i == selected) background = rounded(c.accent, 5f)
                setOnClickListener { if (i != selected) onPick(i) }
            }
            group.addView(t)
        }
        return group
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(24), px(20), px(28))
        }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_fg)
            background = rounded(0xFF1C1C1C.toInt(), 14f)
        }
        header.addView(icon, LinearLayout.LayoutParams(px(56), px(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
        titles.addView(text("LapFold", 26f, bold = true))
        titles.addView(text("Your Fold, as a laptop", 14f, c.sub))
        header.addView(titles)
        col.addView(header)

        // status
        val status = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.off) } }
        top.addView(statusDot, LinearLayout.LayoutParams(px(10), px(10)).apply { rightMargin = px(10) })
        statusTitle = text("", 18f, bold = true)
        top.addView(statusTitle)
        status.addView(top)
        statusText = text("", 14f, c.sub)
        status.addView(statusText, lp(6))
        toggleBtn = button("Turn on", true) { LapFoldService.instance?.toggle(split = false); refresh() }
        status.addView(toggleBtn, lp(14))
        col.addView(status, lp(24))

        // setup (only until Accessibility is allowed)
        val setup = card()
        setup.addView(text("Set up", 16f, bold = true))
        setup.addView(text(
            "1. Tap the button and turn on LapFold.\n" +
            "2. If the switch is blocked: Settings → Apps → LapFold → ⋮ → Allow restricted settings, then try again.",
            14f, c.sub), lp(6))
        setup.addView(button("Open Accessibility settings", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp(14))
        setupCard = setup
        col.addView(setup, lp(12))

        // appearance
        col.addView(section("Appearance"), lp(24))
        val look = card()
        look.addView(row("Theme", "Keyboard and app", segmented(listOf("Dark", "Light"), if (prefs.dark) 0 else 1) { i ->
            prefs.dark = i == 0
            LapFoldService.instance?.applyTheme()
            recreate()
        }))
        col.addView(look)

        // trackpad & behaviour
        col.addView(section("Trackpad & behaviour"), lp(20))
        val behave = card()
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(12), 0, px(12)) }
        speedRow.addView(text("Pointer speed", 15f))
        speedRow.addView(SeekBar(this).apply {
            max = 30; progress = ((prefs.speed - 0.4f) * 20).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, u: Boolean) { prefs.speed = 0.4f + v / 20f }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, lp(6))
        behave.addView(speedRow)
        behave.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)))
        behave.addView(row("Natural scrolling", "Content follows your fingers", switch(prefs.naturalScroll) { prefs.naturalScroll = it }))
        behave.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)))
        behave.addView(row("Auto split screen", "From the notification or tile: your app on top, LapFold below",
            switch(prefs.autoSplit) { prefs.autoSplit = it }))
        col.addView(behave)

        // updates
        col.addView(section("About"), lp(20))
        val about = card()
        updateStatus = text("", 13f, c.sub)
        val verCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        verCol.addView(text("Version $version", 15f))
        verCol.addView(updateStatus)
        val upRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        upRow.addView(verCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        upRow.addView(button("Check for updates", false) { checkForUpdate(silent = false) })
        about.addView(upRow)
        about.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(14) })
        about.addView(row("GitHub", "github.com/ilukezippo/LapFold", text("↗", 18f, c.accent)).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL))) }
        })
        col.addView(about)

        // help
        col.addView(section("How to use"), lp(20))
        val help = card()
        val tips = listOf(
            "On / off" to "Notification button, Quick Settings tile, or open this app. The ⏻ key turns it off.",
            "Laptop look" to "Open your app (e.g. Splashtop), then turn LapFold on from the notification or tile. The screen splits: your app on top, keyboard below.",
            "Typing" to "Tap a text box in the top app, then type. Gboard stays hidden while LapFold is on.",
            "Trackpad" to "1 finger moves the pointer, tap = click, 2-finger tap or right button = right-click, 2-finger drag or the left strip = scroll, hold left button + move = drag.",
            "Modifiers" to "Shift, Ctrl, Alt, Win: tap once for the next key, twice to lock, again to release.",
            "Splashtop" to "Use its touch mode so clicks land where the pointer is.",
        )
        tips.forEachIndexed { i, (t, d) ->
            if (i > 0) help.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)))
            help.addView(row(t, d, null))
        }
        col.addView(help)

        return ScrollView(this).apply {
            setBackgroundColor(c.bg)
            isFillViewport = true
            addView(col)
        }
    }
}
