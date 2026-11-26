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

class LapFoldService : AccessibilityService(), KeyListener, PadListener {

    companion object {
        var instance: LapFoldService? = null
            private set
        const val ACTION_TOGGLE = "com.ilukezippo.lapfold.TOGGLE"
        private const val CHANNEL = "lapfold"
        private const val NOTIF_ID = 1
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

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        gestures = GestureEngine(this)
        registerReceiver(toggleReceiver, IntentFilter(ACTION_TOGGLE), RECEIVER_NOT_EXPORTED)
        createChannel()
        updateNotification()
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
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
        buildOverlays()
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
        // leave split screen: closing LapFold's pane gives the other app the full screen again
        MainActivity.current?.let { if (it.isInMultiWindowMode && it.isPane) it.finishAndRemoveTask() }
        gestures.cancel()
        runCatching { softKeyboardController.setShowMode(SHOW_MODE_AUTO) }  // Gboard is back
        if (!collapsed) {
            panel?.let { runCatching { wm.removeView(it) } }
            pointer?.let { runCatching { wm.removeView(it) } }
        }
        showTab?.let { runCatching { wm.removeView(it) } }
        showTab = null; arrow = null; collapsed = false
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
            text = "⌃  LapFold"; textSize = 14f; gravity = Gravity.CENTER
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

    private fun tabRect(): Rect {
        val u = usable()
        val d = resources.displayMetrics.density
        val w = (120 * d).toInt(); val h = (34 * d).toInt()
        val left = u.centerX() - w / 2
        return Rect(left, u.bottom - h - (6 * d).toInt(), left + w, u.bottom - (6 * d).toInt())
    }

    private fun styleTab(v: TextView, t: Theme) {
        v.setTextColor(t.keyText)
        v.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(t.keyBg); cornerRadius = 17 * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), t.padBorder)
        }
        v.elevation = 6 * resources.displayMetrics.density
    }

    fun setPane(r: Rect?) {
        if (r == pane) return
        pane = r
        if (isOn) relayout()
    }

    fun applyTheme() {
        val t = if (prefs.dark) Theme.DARK else Theme.LIGHT
        keyboard?.theme = t
        trackpad?.theme = t
        scrollStrip?.theme = t
        navButtons?.theme = t
        arrow?.setTextColor(t.keySubText)
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
        val p = pane
        // use LapFold's split pane only when it's the lower half (top/bottom split)
        if (p != null && p.width() > 0 && p.height() > 0 && s.contains(p) && p.top > s.top + s.height() * 0.25f) {
            val r = Rect(p); r.intersect(u); return r
        }
        return Rect(u.left, s.top + s.height() / 2, u.right, u.bottom)   // bottom half = below the hinge
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
        val kb = KeyboardView(this, mods, this).apply { theme = t }
        val tp = TrackpadView(this, this).apply { theme = t }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.panelBg)
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val d0 = resources.displayMetrics.density
        val hideArrow = TextView(this).apply {
            text = "⌄"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(t.keySubText); includeFontPadding = false
            setOnClickListener { collapse() }
        }
        root.addView(hideArrow, LinearLayout.LayoutParams((90 * d0).toInt(), (24 * d0).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        arrow = hideArrow
        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.62f))
        val d = resources.displayMetrics.density
        val strip = ScrollStripView(this, this).apply { theme = t }
        val nav = NavButtonsView(this,
            onBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            onHome = { performGlobalAction(GLOBAL_ACTION_HOME) }).apply { theme = t }
        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        val gap = (10 * d).toInt()
        padRow.addView(strip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.06f).apply { rightMargin = gap })
        padRow.addView(tp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.45f))
        padRow.addView(nav, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.13f).apply { leftMargin = gap })
        padRow.weightSum = 0.64f
        root.addView(padRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.38f))
        scrollStrip = strip; navButtons = nav

        val pr = panelRect()
        wm.addView(root, overlayParams(pr, true))
        panel = root; keyboard = kb; trackpad = tp

        val pv = PointerView(this)
        area = pointerArea(pr)
        px = area.left + area.width / 2; py = area.top + area.height / 2
        val pp = overlayParams(Rect(0, 0, pv.sizePx, pv.sizePx), false)
        pointerParams = pp
        wm.addView(pv, pp)
        pointer = pv
        placePointer()
    }

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
        if (isOn) main.postDelayed({ relayout() }, 350)
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

    private fun movePointer(dx: Float, dy: Float) {
        val s = prefs.speed
        val (nx, ny) = PointerMath.clamp(px + PointerMath.accel(dx, s), py + PointerMath.accel(dy, s), area)
        px = nx; py = ny
        placePointer()
    }

    override fun onMove(dx: Float, dy: Float) {
        movePointer(dx, dy)
        if (gestures.active) gestures.move(px, py)
    }

    override fun onClick() = gestures.tap(px, py)
    override fun onRightClick() = gestures.longPress(px, py)

    override fun onDragStart() { pointer?.held = true; gestures.begin(px, py) }
    override fun onDragEnd() { pointer?.held = false; gestures.end() }

    // scrolling = a finger dragged from the pointer position, clamped to the app area
    private var sx = 0f
    private var sy = 0f
    override fun onScrollStart() { sx = px; sy = py; gestures.begin(sx, sy) }
    override fun onScroll(dx: Float, dy: Float) {
        val dir = if (prefs.naturalScroll) 1f else -1f
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
