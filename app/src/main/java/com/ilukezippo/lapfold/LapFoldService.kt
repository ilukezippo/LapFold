package com.ilukezippo.lapfold

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private fun Int.within(lo: Int, hi: Int) = if (hi < lo) lo else coerceIn(lo, hi)

class LapFoldService : AccessibilityService(), KeyListener, PadListener {

    companion object {
        var instance: LapFoldService? = null
            private set
        const val ACTION_TOGGLE = "com.ilukezippo.lapfold.TOGGLE"
        private const val CHANNEL = "lapfold"
        private const val NOTIF_ID = 1
        /** Apps whose own trackpad mode LapFold drives (package prefixes). */
        val REMOTE_APPS = listOf("com.splashtop")
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val mods = Modifiers()
    private lateinit var gestures: GestureEngine

    private var panel: View? = null
    private var keyboard: KeyboardView? = null
    private var trackpad: TrackpadView? = null
    private var scrollStrip: ScrollStripView? = null
    private var navButtons: NavButtonsView? = null
    private var arrow: TextView? = null
    private var barLabels: List<TextView> = emptyList()
    private var padRowView: LinearLayout? = null
    private var showTab: TextView? = null
    var collapsed = false
        private set
    private var pointer: PointerView? = null
    private var pointerParams: WindowManager.LayoutParams? = null

    /** Bounds of LapFold's own split-screen pane, if it's in split screen. */
    private var pane: Rect? = null
    private var area = Area(0f, 0f, 1f, 1f)
    private var px = 0f
    private var py = 0f
    private var lastNoFocusToast = 0L

    var isOn = false
        private set

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // close the notification shade first so split screen acts on the app underneath
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            main.postDelayed({ toggle(split = true) }, 350)
        }
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        // fold / unfold / rotate / resolution change: refit to the new screen size
        override fun onDisplayChanged(id: Int) { if (isOn) main.postDelayed({ safely { relayout() } }, 250) }
    }

    /** Never let a layout problem kill the service (Android then switches it off). */
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { CrashLog.write(this, e) }
    }

    override fun onServiceConnected() {
        CrashLog.install(this)
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        gestures = GestureEngine(this)
        runCatching { unregisterReceiver(toggleReceiver) }
        registerReceiver(toggleReceiver, IntentFilter(ACTION_TOGGLE), RECEIVER_NOT_EXPORTED)
        getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, main)
        createChannel()
        updateNotification()
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        turnOff()
        runCatching { unregisterReceiver(toggleReceiver) }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        instance = null
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
        return super.onUnbind(intent)
    }

    override fun onDestroy() { turnOff(); instance = null; super.onDestroy() }

    // ---------- on / off ----------

    fun toggle(split: Boolean) = if (isOn) turnOff() else turnOn(split)

    /** split = put the current app on top and LapFold's pane below (Samsung split screen). */
    fun turnOn(split: Boolean = false) {
        if (isOn) return
        isOn = true
        mods.clear()
        softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)   // keep Gboard out of the way
        try { buildOverlays() } catch (e: Exception) {
            CrashLog.write(this, e); isOn = true; turnOff()
            Toast.makeText(this, "LapFold couldn't open the keyboard. Details in the app.", Toast.LENGTH_LONG).show()
            return
        }
        afterStateChange()
        if (split && prefs.autoSplit) startSplit()
    }

    private fun startSplit() {
        if (MainActivity.current?.isInMultiWindowMode == true) return
        if (!performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) return
        // the current app moves to the first half; fill the other half with LapFold's pane
        main.postDelayed({
            if (!isOn) return@postDelayed
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                .putExtra(MainActivity.EXTRA_PANE, true)
            runCatching { startActivity(i) }
        }, 700)
    }

    fun turnOff() {
        if (!isOn) return
        isOn = false
        closeEditor()
        // leave split screen: closing LapFold's pane gives the other app the full screen again
        MainActivity.current?.let { if (it.isInMultiWindowMode && it.isPane) it.finishAndRemoveTask() }
        gestures.cancel()
        runCatching { softKeyboardController.setShowMode(SHOW_MODE_AUTO) }  // Gboard is back
        if (!collapsed) {
            panel?.let { runCatching { wm.removeView(it) } }
            pointer?.let { runCatching { wm.removeView(it) } }
        }
        showTab?.let { runCatching { wm.removeView(it) } }
        showTab = null; arrow = null; barLabels = emptyList(); padRowView = null; collapsed = false
        panel = null; pointer = null; keyboard = null; trackpad = null; scrollStrip = null; navButtons = null
        afterStateChange()
    }

    private fun afterStateChange() {
        updateNotification()
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
    }

    /** Hide the keyboard to a small arrow tab; Gboard works normally meanwhile. */
    fun collapse() {
        if (!isOn || collapsed) return
        collapsed = true
        closeEditor()
        gestures.cancel()
        pointer?.let { runCatching { wm.removeView(it) } }
        // slide the keyboard down, then remove its window
        panel?.let { v ->
            v.animate().translationY(v.height.toFloat()).setDuration(220)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { runCatching { wm.removeView(v) }; v.translationY = 0f }
                .start()
        }
        runCatching { softKeyboardController.setShowMode(SHOW_MODE_AUTO) }
        val t = if (prefs.dark) Theme.DARK else Theme.LIGHT
        val tab = showTab ?: TextView(this).apply {
            text = "⌃"; textSize = 22f; gravity = Gravity.CENTER
            setOnClickListener { expand() }
        }.also { showTab = it }
        styleTab(tab, t)
        tab.alpha = 0f
        runCatching { wm.addView(tab, overlayParams(tabRect(), true)) }
        tab.animate().alpha(1f).setStartDelay(160).setDuration(180).start()
        afterStateChange()
    }

    fun expand() {
        if (!isOn || !collapsed) return
        collapsed = false
        showTab?.let { runCatching { wm.removeView(it) } }
        softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
        panel?.let { v ->
            v.animate().cancel()
            if (v.parent != null) runCatching { wm.removeView(v) }
            val r = panelRect()
            v.translationY = r.height().toFloat()
            runCatching { wm.addView(v, overlayParams(r, true)) }
            // slide the keyboard up into place
            v.animate().translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        pointer?.let { v -> pointerParams?.let { runCatching { wm.addView(v, it) } } }
        relayout()
        afterStateChange()
    }

    /** The "show" tab sits on the right edge at the hinge, clear of Gboard's keys. */
    private fun tabRect(): Rect {
        val u = usable()
        val s = screen()
        val d = resources.displayMetrics.density
        val w = (46 * d).toInt(); val h = (64 * d).toInt()
        val top = s.top + s.height() / 2 - h - (8 * d).toInt()
        return Rect(u.right - w, top, u.right, top + h)
    }

    private fun styleTab(v: TextView, t: Theme) {
        v.setTextColor(t.keyText)
        v.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(t.keyBg)
            val r = 14 * resources.displayMetrics.density
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)   // rounded on the left, flat against the edge
            setStroke((1 * resources.displayMetrics.density).toInt(), t.padBorder)
        }
        v.elevation = 6 * resources.displayMetrics.density
    }

    fun setPane(r: Rect?) {
        if (r == pane) return
        pane = r
        if (isOn) safely { relayout() }
    }

    fun applyTheme() {
        val t = if (prefs.dark) Theme.DARK else Theme.LIGHT
        keyboard?.theme = t
        trackpad?.theme = t
        scrollStrip?.theme = t
        navButtons?.theme = t
        arrow?.setTextColor(t.keySubText)
        barLabels.forEach { it.setTextColor(t.keySubText) }
        showTab?.let { styleTab(it, t) }
        panel?.setBackgroundColor(t.panelBg)
    }

    // ---------- overlays ----------

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    /** Screen area minus the navigation bar / taskbar, so they stay reachable. */
    private fun usable(): Rect {
        val m = wm.currentWindowMetrics
        val b = Rect(m.bounds)
        val nav = m.windowInsets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement())
        b.left += nav.left; b.right -= nav.right; b.bottom -= nav.bottom
        return b
    }

    private fun panelRect(): Rect {
        val s = screen()
        val u = usable()
        val l = prefs.layout
        val w = (u.width() * l.widthPct / 100f).toInt()
        val h = (s.height() * l.heightPct / 100f).toInt().coerceAtMost(u.bottom - s.top)
        prefs.floatPos?.let { (x, y) ->
            // moved by hand: keep its spot, but stay on screen
            val fx = x.within(s.left, s.right - w); val fy = y.within(s.top, u.bottom - h)
            return Rect(fx, fy, fx + w, fy + h)
        }
        val p = pane
        // use LapFold's split pane only when it's the lower half (top/bottom split)
        if (p != null && p.width() > 0 && p.height() > 0 && s.contains(p) && p.top > s.top + s.height() * 0.25f) {
            val r = Rect(p); r.intersect(u); return r
        }
        // docked: Height is measured from the bottom of the screen, so 50% starts right at the hinge
        val left = u.left + (u.width() - w) / 2
        val top = (s.top + s.height() * (100 - l.heightPct) / 100f).toInt().coerceAtMost(u.bottom - 1)
        return Rect(left, top, left + w, u.bottom)
    }

    private fun pointerArea(panelR: Rect): Area {
        val s = screen()
        return when {
            panelR.top > s.top + s.height() * 0.25f -> Area(s.left.toFloat(), s.top.toFloat(), s.right.toFloat(), panelR.top.toFloat())
            panelR.left > s.left -> Area(s.left.toFloat(), s.top.toFloat(), panelR.left.toFloat(), s.bottom.toFloat())
            panelR.right < s.right -> Area(panelR.right.toFloat(), s.top.toFloat(), s.right.toFloat(), s.bottom.toFloat())
            else -> Area(s.left.toFloat(), s.top.toFloat(), s.right.toFloat(), (s.top + s.height() / 2).toFloat())
        }
    }

    private fun overlayParams(r: Rect, touchable: Boolean) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left; y = r.top
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun buildOverlays() {
        val t = if (prefs.dark) Theme.DARK else Theme.LIGHT
        val d = resources.displayMetrics.density
        val kb = KeyboardView(this, mods, this).apply { theme = t }
        val tp = TrackpadView(this, this).apply { theme = t }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.panelBg)
            val pad = (6 * d).toInt()
            setPadding(pad, 0, pad, pad)
        }

        // handle bar: drag it to move the keyboard; ✎ = edit sizes, ⌄ = hide
        val bar = FrameLayout(this)
        val editBtn = TextView(this).apply {
            text = "✎  Edit"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(t.keySubText); setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            setOnClickListener { toggleEditor() }
        }
        val hideArrow = TextView(this).apply {
            text = "⌄"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(t.keySubText); includeFontPadding = false
            setOnClickListener { collapse() }
        }
        val grip = TextView(this).apply {
            text = "⠿  Move"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(t.keySubText); setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        }
        bar.addView(editBtn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        bar.addView(hideArrow, FrameLayout.LayoutParams((90 * d).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        bar.addView(grip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        attachMover(bar); attachMover(grip)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (30 * d).toInt()))
        arrow = hideArrow; barLabels = listOf(editBtn, grip)

        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.62f))
        val strip = ScrollStripView(this, this).apply { theme = t }
        val nav = NavButtonsView(this, listOf(
            "◀ Back" to { mouseBack() },
            "Forward ▶" to { mouseForward() },
            "PrtSc" to { printScreen() },
        )).apply { theme = t }
        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        padRow.addView(strip); padRow.addView(tp); padRow.addView(nav)
        root.addView(padRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.38f))
        scrollStrip = strip; navButtons = nav; padRowView = padRow

        panel = root; keyboard = kb; trackpad = tp
        applyLayoutWeights()
        val pr = panelRect()
        wm.addView(root, overlayParams(pr, true))

        val pv = PointerView(this)
        area = pointerArea(pr)
        px = area.left + area.width / 2; py = area.top + area.height / 2
        val pp = overlayParams(Rect(0, 0, pv.sizePx, pv.sizePx), false)
        pointerParams = pp
        wm.addView(pv, pp)
        pointer = pv
        placePointer()
        updatePointerVisibility()
    }

    /** Apply the Edit-mode sizes to the keyboard / trackpad row. */
    private fun applyLayoutWeights() {
        val l = prefs.layout
        val d = resources.displayMetrics.density
        val gap = (10 * d).toInt()
        keyboard?.let { (it.layoutParams as LinearLayout.LayoutParams).weight = l.keyboardPct / 100f; it.requestLayout() }
        padRowView?.let { row ->
            (row.layoutParams as LinearLayout.LayoutParams).weight = 1f - l.keyboardPct / 100f
            row.weightSum = 1f
            fun lp(pct: Int, left: Int = 0, right: Int = 0) =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct / 100f).apply { leftMargin = left; rightMargin = right }
            // centre the three parts like a laptop: empty space split on both sides
            val used = l.stripPct + l.trackpadPct + l.buttonsPct
            row.weightSum = maxOf(100, used) / 100f
            scrollStrip?.layoutParams = lp(l.stripPct, right = gap)
            trackpad?.layoutParams = lp(l.trackpadPct)
            navButtons?.layoutParams = lp(l.buttonsPct, left = gap)
            row.requestLayout()
        }
    }

    // ---------- moving the keyboard ----------

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachMover(v: View) {
        var startX = 0f; var startY = 0f; var winX = 0; var winY = 0; var moving = false
        v.setOnTouchListener { _, e ->
            val root = panel ?: return@setOnTouchListener false
            val lp = root.layoutParams as WindowManager.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; winX = lp.x; winY = lp.y; moving = false
                    // if it's still docked, start from where it is drawn now
                    if (lp.gravity != (Gravity.TOP or Gravity.START)) { winX = 0; winY = 0 } }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX; val dy = e.rawY - startY
                    if (!moving && Math.hypot(dx.toDouble(), dy.toDouble()) > 12 * resources.displayMetrics.density) moving = true
                    if (moving) {
                        val s = screen()
                        lp.x = (winX + dx).toInt().within(s.left, s.right - lp.width)
                        lp.y = (winY + dy).toInt().within(s.top, s.bottom - lp.height)
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        prefs.floatPos = Pair(lp.x, lp.y)
                        area = pointerArea(Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height))
                        val (cx2, cy2) = PointerMath.clamp(px, py, area); px = cx2; py = cy2; placePointer()
                        editor?.let { positionEditor() }
                    }
                }
            }
            true   // keep receiving the drag (returning false on DOWN made Move do nothing)
        }
    }

    // ---------- edit mode ----------

    private var editor: View? = null

    private fun toggleEditor() { if (editor != null) closeEditor() else openEditor() }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    private fun editorRect(): Rect {
        val d = resources.displayMetrics.density
        val s = screen()
        val p = (panel?.layoutParams as? WindowManager.LayoutParams)
        val w = minOf((380 * d).toInt(), s.width() - (16 * d).toInt())
        val h = (430 * d).toInt()
        val panelTop = p?.y ?: (s.top + s.height() / 2)
        val top = if (panelTop - h - (8 * d).toInt() >= s.top) panelTop - h - (8 * d).toInt() else s.top + (8 * d).toInt()
        val left = s.centerX() - w / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun positionEditor() {
        val v = editor ?: return
        val r = editorRect()
        (v.layoutParams as WindowManager.LayoutParams).apply { x = r.left; y = r.top; width = r.width(); height = r.height() }
            .also { runCatching { wm.updateViewLayout(v, it) } }
    }

    private fun openEditor() {
        val t = if (prefs.dark) Theme.DARK else Theme.LIGHT
        val d = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(t.panelBg); cornerRadius = 12 * d; setStroke((1 * d).toInt(), t.padBorder)
            }
            elevation = 10 * d
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        card.addView(TextView(this).apply { text = "Edit layout"; textSize = 17f; setTextColor(t.keyText); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        for ((label, range, acc) in PanelLayout.FIELDS) {
            val (get, set) = acc
            val value = TextView(this).apply { textSize = 13f; setTextColor(t.keySubText) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(t.keyText) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(value)
            card.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            val cur = get(prefs.layout)
            value.text = "$cur%"
            card.addView(android.widget.SeekBar(this).apply {
                max = range.last - range.first; progress = cur - range.first
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = range.first + p
                        value.text = "$v%"
                        prefs.layout = set(prefs.layout, v)
                        applyLayoutWeights(); relayout()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun action(label: String, accent: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (accent) android.graphics.Color.WHITE else t.keyText)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(if (accent) t.accent else t.keyBg); cornerRadius = 6 * d }
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
        val gapLp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (8 * d).toInt() } }
        actions.addView(action("Reset", false) {
            prefs.layout = PanelLayout.DEFAULT; prefs.floatPos = null
            applyLayoutWeights(); relayout(); closeEditor(); openEditor()
        }, gapLp())
        actions.addView(action("Dock", false) { prefs.floatPos = null; relayout(); positionEditor() }, gapLp())
        actions.addView(action("Done", true) { closeEditor() }, gapLp())
        card.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        editor = card
        runCatching { wm.addView(card, overlayParams(editorRect(), true)) }
    }

    // ---------- mouse side buttons ----------

    /** Send a key combo to the focused text box; false if nothing is focused. */
    private fun sendCombo(code: Int, extra: List<Mod> = emptyList()): Boolean {
        val ic = inputMethod?.currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        fun ev(action: Int, c: Int, m: Int) = KeyEvent(now, now, action, c, 0, m,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        var m = 0
        for (mod in extra) { m = m or mod.meta; ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, modCode(mod), m)) }
        ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, code, m)); ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, code, m))
        for (mod in extra.reversed()) { m = m and mod.meta.inv(); ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, modCode(mod), m)) }
        return true
    }

    // Mouse Back / Forward do what Alt+Left / Alt+Right do on Windows (browser, Explorer…).
    private fun mouseBack() { if (!sendCombo(KeyEvent.KEYCODE_DPAD_LEFT, listOf(Mod.ALT))) performGlobalAction(GLOBAL_ACTION_BACK) }
    private fun mouseForward() {
        if (!sendCombo(KeyEvent.KEYCODE_DPAD_RIGHT, listOf(Mod.ALT)))
            Toast.makeText(this, "Tap a text box (or Splashtop's keyboard button) first", Toast.LENGTH_SHORT).show()
    }
    // Print Screen goes to the PC in Splashtop; with nothing focused, takes a phone screenshot.
    private fun printScreen() { if (!sendCombo(KeyEvent.KEYCODE_SYSRQ)) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }

    private fun relayout() {
        if (collapsed) {
            showTab?.let { v -> val r = tabRect(); (v.layoutParams as? WindowManager.LayoutParams)?.apply {
                width = r.width(); height = r.height(); x = r.left; y = r.top
            }?.also { runCatching { wm.updateViewLayout(v, it) } } }
            return
        }
        val root = panel ?: return
        val pr = panelRect()
        (root.layoutParams as WindowManager.LayoutParams).apply {
            width = pr.width(); height = pr.height(); x = pr.left; y = pr.top
        }.also { runCatching { wm.updateViewLayout(root, it) } }
        area = pointerArea(pr)
        val (cx, cy) = PointerMath.clamp(px, py, area)
        px = cx; py = cy
        placePointer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // fold/unfold or rotation: wait for new window metrics, then refit
        if (isOn) main.postDelayed({ safely { relayout() } }, 350)
    }

    private fun placePointer() {
        val v = pointer ?: return
        val lp = pointerParams ?: return
        lp.x = px.toInt(); lp.y = py.toInt()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ---------- keyboard ----------

    override fun onKey(key: Key) {
        val ic = inputMethod?.currentInputConnection
        if (ic == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastNoFocusToast > 3000) {
                lastNoFocusToast = now
                Toast.makeText(this, "Tap a text box first", Toast.LENGTH_SHORT).show()
            }
            mods.consumeOneShots(); return
        }
        // Caps Lock flips Shift for letters only
        val shiftNeeded = mods.isActive(Mod.SHIFT) != (mods.caps && key.isLetter)
        val active = Mod.entries.filter { m ->
            if (m == Mod.SHIFT) shiftNeeded else mods.isActive(m)
        }
        val meta = active.fold(0) { acc, m -> acc or m.meta }
        val now = SystemClock.uptimeMillis()
        fun ev(action: Int, code: Int, m: Int) = KeyEvent(now, now, action, code, 0, m,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        // press modifiers like a real keyboard, so remote-desktop apps see them too
        var m = 0
        for (mod in active) { m = m or mod.meta; ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, modCode(mod), m)) }
        ic.sendKeyEvent(ev(KeyEvent.ACTION_DOWN, key.keyCode, meta))
        ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, key.keyCode, meta))
        for (mod in active.reversed()) { m = m and mod.meta.inv(); ic.sendKeyEvent(ev(KeyEvent.ACTION_UP, modCode(mod), m)) }
        mods.consumeOneShots()
        keyboard?.invalidate()
    }

    private fun modCode(m: Mod) = when (m) {
        Mod.SHIFT -> KeyEvent.KEYCODE_SHIFT_LEFT
        Mod.CTRL -> KeyEvent.KEYCODE_CTRL_LEFT
        Mod.ALT -> KeyEvent.KEYCODE_ALT_LEFT
        Mod.WIN -> KeyEvent.KEYCODE_META_LEFT
    }

    override fun onModifier(mod: Mod) { mods.tap(mod); keyboard?.invalidate() }
    override fun onCaps() { mods.caps = !mods.caps; keyboard?.invalidate() }
    override fun onHide() { turnOff() }

    // ---------- trackpad ----------

    // Remote-desktop apps (Splashtop) draw their own mouse pointer. In "relay" mode the
    // trackpad drives that pointer through the app's trackpad mode instead of LapFold's:
    // finger movement is replayed as a relative drag, taps as taps, 2 fingers as 2 fingers.
    private var foregroundPkg: String? = null
    private val relay: Boolean
        get() = when (prefs.remoteMode) {
            1 -> true
            2 -> false
            else -> foregroundPkg?.let { p -> REMOTE_APPS.any { p.startsWith(it) } } == true
        }
    private val ignoredPkgs = setOf("com.android.systemui", "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard", "com.sec.android.app.launcher")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignoredPkgs) return
        if (pkg != foregroundPkg) { foregroundPkg = pkg; updatePointerVisibility() }
    }

    fun updatePointerVisibility() {
        pointer?.visibility = if (relay) View.INVISIBLE else View.VISIBLE
    }

    private val cx get() = area.left + area.width / 2
    private val cy get() = area.top + area.height / 2
    private val twoFingerSpread get() = 70 * resources.displayMetrics.density
    private var relayDragging = false

    /** Move the relayed finger; lift and re-centre when it nears the edge of the app area. */
    private fun relayMove(dx: Float, dy: Float, fingers: Int) {
        if (!gestures.active) gestures.begin(cx, cy, fingers, if (fingers > 1) twoFingerSpread else 0f)
        val margin = 48 * resources.displayMetrics.density
        val nx = gestures.x + dx; val ny = gestures.y + dy
        if (nx < area.left + margin || nx > area.right - margin || ny < area.top + margin || ny > area.bottom - margin) {
            gestures.end()
            main.postDelayed({ if (isOn && !gestures.active) gestures.begin(cx, cy, fingers, if (fingers > 1) twoFingerSpread else 0f) }, 30)
            return
        }
        gestures.move(nx, ny)
    }

    private fun movePointer(dx: Float, dy: Float) {
        val s = prefs.speed
        val (nx, ny) = PointerMath.clamp(px + PointerMath.accel(dx, s), py + PointerMath.accel(dy, s), area)
        px = nx; py = ny
        placePointer()
    }

    override fun onMove(dx: Float, dy: Float) {
        if (relay) { relayMove(PointerMath.accel(dx, prefs.speed), PointerMath.accel(dy, prefs.speed), 1); return }
        movePointer(dx, dy)
        if (gestures.active) gestures.move(px, py)
    }

    override fun onFingerUp() {
        if (relay && !relayDragging && gestures.active) gestures.end()
    }

    override fun onClick() = if (relay) gestures.tap(cx, cy) else gestures.tap(px, py)
    override fun onRightClick() =
        if (relay) gestures.tap(cx, cy, 2, twoFingerSpread) else gestures.longPress(px, py)

    override fun onDragStart() {
        if (relay) {
            // trackpad-mode drag = tap, then touch again and move (double-tap-drag)
            relayDragging = true
            gestures.tap(cx, cy)
            main.postDelayed({ if (isOn && relayDragging) gestures.begin(cx, cy) }, 90)
            return
        }
        pointer?.held = true; gestures.begin(px, py)
    }
    override fun onDragEnd() {
        relayDragging = false
        pointer?.held = false; gestures.end()
    }

    // scrolling = a finger dragged from the pointer position, clamped to the app area
    // (relay mode: a two-finger drag, which remote-desktop apps turn into the mouse wheel)
    private var sx = 0f
    private var sy = 0f
    override fun onScrollStart() {
        if (relay) { gestures.begin(cx, cy, 2, twoFingerSpread); return }
        sx = px; sy = py; gestures.begin(sx, sy)
    }
    override fun onScroll(dx: Float, dy: Float) {
        val dir = if (prefs.naturalScroll) 1f else -1f
        if (relay) { relayMove(dx * 1.6f * dir, dy * 1.6f * dir, 2); return }
        val (nx, ny) = PointerMath.clamp(sx + dx * 1.6f * dir, sy + dy * 1.6f * dir, area)
        if (nx == sx && ny == sy) return
        sx = nx; sy = ny
        // hit the edge of the app area: lift and start again from the pointer
        if (ny <= area.top || ny >= area.bottom - 1f || nx <= area.left || nx >= area.right - 1f) {
            gestures.end(); main.postDelayed({ if (isOn) onScrollStart() }, 40); return
        }
        gestures.move(sx, sy)
    }
    override fun onScrollEnd() = gestures.end()

    // ---------- notification ----------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "LapFold toggle", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun updateNotification() {
        val toggle = PendingIntent.getBroadcast(this, 0,
            Intent(ACTION_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NO_AUTO_ON, true),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (isOn) "LapFold is on" else "LapFold is off")
            .setContentText(if (isOn) "Keyboard + trackpad on the bottom half" else "Tap Turn on for laptop mode")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (isOn) "Turn off" else "Turn on", toggle).build())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }
}
