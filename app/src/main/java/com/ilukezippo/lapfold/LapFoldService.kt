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
        override fun onReceive(c: Context, i: Intent) { toggle() }
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

    fun toggle() = if (isOn) turnOff() else turnOn()

    fun turnOn() {
        if (isOn) return
        isOn = true
        mods.clear()
        softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)   // keep Gboard out of the way
        buildOverlays()
        afterStateChange()
    }

    fun turnOff() {
        if (!isOn) return
        isOn = false
        gestures.cancel()
        runCatching { softKeyboardController.setShowMode(SHOW_MODE_AUTO) }  // Gboard is back
        panel?.let { runCatching { wm.removeView(it) } }
        pointer?.let { runCatching { wm.removeView(it) } }
        panel = null; pointer = null; keyboard = null; trackpad = null
        afterStateChange()
    }

    private fun afterStateChange() {
        updateNotification()
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
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
        panel?.setBackgroundColor(t.panelBg)
    }

    // ---------- overlays ----------

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    private fun panelRect(): Rect {
        val s = screen()
        val p = pane
        if (p != null && p.width() > 0 && p.height() > 0 && s.contains(p)) return Rect(p)
        return Rect(s.left, s.top + s.height() / 2, s.right, s.bottom)   // bottom half = below the hinge
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
        root.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.62f))
        val padRow = FrameLayout(this)
        val padLp = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
        padRow.addView(tp, padLp)
        padRow.setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        root.addView(padRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.38f))
        // trackpad is ~45% of the panel width, centred like a laptop's
        padRow.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val want = ((r - l) * 0.45f).toInt()
            if (tp.layoutParams.width != want) { tp.layoutParams = padLp.apply { width = want }; v.post { tp.requestLayout() } }
        }

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
