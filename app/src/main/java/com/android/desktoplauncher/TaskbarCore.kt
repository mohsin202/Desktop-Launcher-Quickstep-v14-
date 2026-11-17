package com.android.desktoplauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.view.ContextThemeWrapper
import android.util.Log
import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.os.Looper

/**
 * Draws the desktop taskbar in an accessibility overlay window. The overlay is shown over every app,
 * including fullscreen/immersive ones, and sits in the navigation bar area at the bottom.
 */
interface TaskbarHost {
    val running: Collection<String>
    val focused: String?
    fun global(action: Int)
    /** Long press on Back (0), Home (1) or Recents (2). */
    fun navLong(kind: Int) {}
    fun hideRunning(pkg: String)
    /** Ends the app's tasks (like swiping it away in Recents); only possible when the launcher is a system app. */
    fun closeApp(pkg: String) {}
    fun canClose(): Boolean = false
    fun toggleDrawer()
    fun openSearch()
    val imeShown: Boolean
    fun pickIme()
    /** Gesture navigation: the hidden (transient) taskbar. */
    fun expandBar(hover: Boolean = false) {}
    fun barHover(inside: Boolean) {}
    fun barTouched() {}
    fun barOutside() {}
    fun appLaunched() {}
}

/**
 * The taskbar itself. It no longer lives in an accessibility service: TouchInteractionService (bound by SystemUI at boot)
 * starts it, so it runs without anything being switched on in Settings > Accessibility. The accessibility service
 * is not used at all any more.
 */
class TaskbarCore private constructor(base: Context) : ContextWrapper(base), TaskbarHost {
    companion object {
        @Volatile var instance: TaskbarCore? = null
        @Synchronized fun ensure(c: Context): TaskbarCore? {
            instance?.let { return it }
            return try {
                val t = TaskbarCore(c.applicationContext ?: c)
                instance = t
                t.start()
                instance
            } catch (e: Throwable) { Log.e("DesktopTaskbar", "taskbar start failed", e); instance = null; null }
        }
        fun shutdown() { instance?.stop() }
    }

    private fun overlayType(): Int = NavWindow.TYPE
    private val windows: List<AccessibilityWindowInfo> get() = emptyList()   // no accessibility service: no window list
    private val wm: WindowManager get() = (baseContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    private lateinit var uctx: Context
    private var bar: TaskbarView? = null
    /** 0 = bar along the bottom; 1 / 2 = vertical bar over a system navigation bar on the left / right edge. */
    private var barSide = 0
    private var barShown = true
    /** True while the bar is a real navigation-bar window (space reserved for apps); false = plain overlay fallback. */
    var navMode = false; private set
    private var drawer: DrawerView? = null
    override val running = LinkedHashSet<String>()
    override var focused: String? = null
    override var imeShown = false
    private var editing = false
    private var editPkg: String? = null
    /** How many Windows-key events this service has been given (shown in the test dialog), the app that was open at the last press, and when it was released. */

    private val listener: () -> Unit = { sync() }
    /** Apps the user hid from the taskbar; they only come back when they are opened again. */
    private val dismissed = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    // ---- Gesture navigation: the taskbar hides in a thin strip (the gesture handle) and swipes up on demand ----
    private var expanded = false
    /** The Recents screen is showing: the taskbar stays visible there. */
    private var inRecents = false
    fun recentsVisible(v: Boolean) {
        inRecents = v
        if (v) { if (autoHide()) setExpanded(true) } else setExpanded(false)
    }
    private val stashRun = Runnable { if (drawer == null) setExpanded(false) }
    private fun gest() = NavMode.gesture(this)
    private fun stripPx() = uctx.dp(24)
    /** Gesture navigation with "Auto-hide taskbar" on: the bar hides in a thin strip. Off: the pill is always shown. */
    private fun autoHide() = gest() && Prefs.bool(this, Prefs.K_AUTOHIDE, true)
    private fun collapsedNow() = autoHide() && !expanded
    /** Floating pill: always (gesture, auto-hide off / three buttons with the pill option), or while swiped up. */
    private fun floatingNow() = if (gest()) (!autoHide() || expanded) else Prefs.bool(this, Prefs.K_PILL, false)
    /** Height (width on a side edge) of the bar window now. */
    /**
     * Height of the bar window. At the bottom the window is always as wide as the screen and its frame is exactly the space the
     * bar reserves; the pill is drawn inside it (12 dp from the bottom, and 12 dp free above it when it is permanent).
     */
    private fun winThick(side: Int = barSide): Int = when {
        collapsedNow() -> stripPx()
        side == 0 && floatingNow() -> uctx.dp(Prefs.tbDp(this)) + uctx.dp(12) * (if (autoHide()) 1 else 2)
        else -> uctx.dp(Prefs.tbDp(this))
    }
    /** Space reserved for apps: in gesture mode only the strip, the shown taskbar floats over the app. */
    private fun insetThick() = if (autoHide()) stripPx()
        else if (floatingNow()) uctx.dp(Prefs.tbDp(this)) + 2 * uctx.dp(12)   // equal gap above and below the pill
        else uctx.dp(Prefs.tbDp(this))

    fun setExpanded(on: Boolean) {
        handler.removeCallbacks(stashRun)
        if (!autoHide()) { expanded = false; hoverOpened = false; return }
        if (!on && inRecents) return
        if (on && drawer == null && !inRecents) handler.postDelayed(stashRun, 5000)
        if (expanded == on) return
        expanded = on
        if (!on) hoverOpened = false
        applyWindow()
        scheduleSample()
    }
    private var hoverOpened = false
    override fun expandBar(hover: Boolean) { if (!expanded) hoverOpened = hover; setExpanded(true) }
    /** The mouse is over / left the shown taskbar: it stays while the pointer is on it and goes shortly after the pointer leaves. */
    override fun barHover(inside: Boolean) {
        if (!expanded || drawer != null || inRecents) return
        handler.removeCallbacks(stashRun)
        if (!inside) handler.postDelayed(stashRun, if (hoverOpened) 2000 else 5000)
    }
    override fun barTouched() { if (expanded && drawer == null && !inRecents) { handler.removeCallbacks(stashRun); handler.postDelayed(stashRun, 5000) } }
    override fun barOutside() { if (expanded && drawer == null) setExpanded(false) }
    override fun appLaunched() { if (autoHide()) { if (drawer != null) closeDrawer(); setExpanded(false) } }

    // ---- What is behind the bar decides the colour of the gesture handle and of the bar's buttons ----
    private var inkDark = true
    private var lastInk: Int? = null
    private fun themeDark() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Transparent bar: its buttons are light on a dark background and dark on a light one. */
    private fun inkWanted() = barShown && !collapsedNow() && barSide == 0 && Prefs.bool(this, Prefs.K_BAR_CLEAR, true)
    private fun sampleWanted() = barShown && (collapsedNow() || inkWanted())
    private fun scrimFrac() = Prefs.scrim(this) / 100f

    private var shadeTick = false

    // The real thing, as in Trebuchet: SurfaceFlinger reports the brightness behind the status bar. Needs ACCESS_SURFACE_FLINGER
    // (system-signed apps only); when we are not allowed no sample ever arrives and the guesses below are used.
    @Volatile private var sampledLuma: Float? = null
    @Volatile private var sampleCount = 0
    private var sampler: android.view.CompositionSamplingListener? = null
    private var samplerArea: android.graphics.Rect? = null
    private var samplerLight = false

    private fun startSampler() {
        val area = android.graphics.Rect(0, 0, screenBounds().width(), maxOf(statusTop(), uctx.dp(24)))
        if (sampler != null && samplerArea == area) return
        stopSampler()
        try {
            val s = object : android.view.CompositionSamplingListener({ r -> handler.post(r) }) {
                override fun onSampleCollected(medianLuma: Float) {
                    sampleCount++
                    sampledLuma = medianLuma
                    samplerLight = if (samplerLight) medianLuma > 0.45f else medianLuma > 0.55f
                    applyShade()
                }
            }
            android.view.CompositionSamplingListener.register(s, android.view.Display.DEFAULT_DISPLAY, null, area)
            sampler = s; samplerArea = area
        } catch (t: Throwable) { Log.w("DesktopTaskbar", "luma sampling not possible: $t"); sampler = null }
    }
    private fun stopSampler() {
        val s = sampler ?: return
        try { android.view.CompositionSamplingListener.unregister(s); s.destroy() } catch (_: Throwable) {}
        sampler = null; samplerArea = null
    }
    fun sampleInfo() = if (sampleCount > 0) "SurfaceFlinger luma sampling works ($sampleCount samples, last " + sampledLuma + ")"
        else "SurfaceFlinger luma sampling: no samples (permission missing?)"

    private fun scheduleSample() {
        if (!inkWanted() && lastInk != null) { lastInk = null; bar?.setInk(null) }
        if (!sampleWanted()) { stopSampler(); return }
        startSampler()
        applyShade()
        if (!shadeTick) { shadeTick = true; handler.postDelayed(shadeRun, 500) }
    }

    /** Looks at the app on top twice a second (a cheap task query, no pixels) and re-colours the icons when it changed. */
    private val shadeRun = object : Runnable {
        override fun run() {
            if (!sampleWanted()) { shadeTick = false; return }
            applyShade()
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Whether the status bar (and so the bar behind our icons) is light: then the icons are dark, otherwise white.
     * 1. When SystemUI sends its navigation-bar value (three-button navigation), that.
     * 2. Otherwise the app on top: the light-status-bar flag of its theme, else the brightness of its status-bar / window colour.
     * 3. On the home screen: whether the wallpaper allows dark text.
     * The stock launcher can ask the display compositor for the real brightness, but that needs a system-signed app.
     */
    private fun lightBehind(): Boolean {
        if (sampleCount > 0 && sampledLuma != null) return samplerLight
        if (SysUi.darkSeen) return SysUi.darkIntensity > 0.5f
        try {
            @Suppress("DEPRECATION")
            val ti = getSystemService(ActivityManager::class.java).getRunningTasks(3).firstOrNull { (it.topActivity?.packageName ?: "") != "com.android.systemui" }
            val pkg = ti?.topActivity?.packageName
            if (ti != null && pkg != null && pkg != packageName) {
                val td = ti.taskDescription
                if (td != null) {
                    val ap = try { td.javaClass.getMethod("getSystemBarsAppearance").invoke(td) as Int } catch (_: Throwable) { -1 }
                    if (ap >= 0 && (ap and 8) != 0) return true   // APPEARANCE_LIGHT_STATUS_BARS
                    var c = try { td.statusBarColor } catch (_: Throwable) { 0 }
                    if (android.graphics.Color.alpha(c) < 0xE0) c = try { td.backgroundColor } catch (_: Throwable) { 0 }
                    if (c == 0) c = try { td.primaryColor } catch (_: Throwable) { 0 }
                    if (c != 0) return androidx_lum(c) > 0.7f
                }
                return false
            }
        } catch (_: Throwable) {}
        // our own screens / the home screen: the wallpaper
        return try {
            val wc = android.app.WallpaperManager.getInstance(this).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
            wc != null && (wc.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        } catch (_: Throwable) { !themeDark() }
    }
    private fun androidx_lum(c: Int): Float =
        ((0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)) / 255.0).toFloat()

    private fun applyShade() {
        val b = bar ?: return
        val dark = !lightBehind()   // dark behind: light icons
        if (collapsedNow()) { b.setHandleDark(dark); return }
        if (!inkWanted()) return
        inkDark = dark
        val ink = if (dark) 0xFFF4F4F4.toInt() else 0xFF1B1B1F.toInt()
        if (ink != lastInk) { lastInk = ink; b.setInk(ink) }
    }
    private val shadeListener: () -> Unit = { if (sampleWanted()) applyShade() }

    private fun fullLen(side: Int) = if (side == 0) screenBounds().width() else screenBounds().height()

    /** Length of the bar along its edge: the whole edge, or in gesture mode (shown) a pill as wide as its icons, at most the screen. */
    private fun fitLen(b: TaskbarView, side: Int): Int {
        val full = fullLen(side)
        if (!floatingNow()) return full
        return b.contentLen().coerceAtMost(full - 2 * uctx.dp(12)).coerceAtLeast(uctx.dp(Prefs.tbDp(this)) * 3)
    }

    /** Size and position of the bar window: whole edge, or (gesture mode, shown) a floating pill in the middle of the edge. */
    private fun place(lp: WindowManager.LayoutParams, b: TaskbarView, side: Int) {
        val t = winThick(side)
        val len = fitLen(b, side)
        b.setLength(len)
        val fl = floatingNow()
        val m = uctx.dp(12)
        if (side == 0) {
            lp.width = MATCH; lp.height = t
            lp.gravity = Gravity.BOTTOM
            lp.x = 0; lp.y = 0
        } else {
            val h = if (side == 2) Gravity.END else Gravity.START
            lp.width = t; lp.height = if (fl) len else len
            lp.gravity = if (fl) h or Gravity.CENTER_VERTICAL else Gravity.TOP or h
            lp.x = if (fl) m else 0; lp.y = 0
        }
    }

    private fun applyWindow() {
        val b = bar ?: return
        try {
            val lp = b.layoutParams as WindowManager.LayoutParams
            b.setMode(collapsedNow(), winThick())
            place(lp, b, barSide)
            if (navMode) NavWindow.provideInsets(lp, barSide, insetThick(), uctx.dp(48))
            wm.updateViewLayout(b, lp)
        } catch (e: Throwable) { Log.e("DesktopTaskbar", "Could not resize taskbar", e) }
    }

    /** The icons changed while the pill is shown: its width follows. */
    private fun fitBar() {
        val b = bar ?: return
        if (!floatingNow()) return
        try {
            val lp = b.layoutParams as WindowManager.LayoutParams
            val want = fitLen(b, barSide)
            if (want == b.curLen()) return
            if (barSide == 0) { b.setLength(want); return }   // the window is full width: only the pill inside changes
            place(lp, b, barSide)
            wm.updateViewLayout(b, lp)
        } catch (_: Throwable) {}
    }

    /** Navigation mode changed (three buttons <-> gestures): rebuild the bar's buttons. */
    private val navObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { expanded = false; handler.removeCallbacks(stashRun); sync() }
    }

    /** Called when SystemUI connects (TouchInteractionService): tell it the taskbar is the navigation bar. */
    fun onSysUiConnected() {
        if (bar != null) { SysUi.taskbarEnabled(true); SysUi.taskbarStatus(true, false) }
    }
    private val pruneTick = object : Runnable {
        override fun run() { scan(); if (bar != null) { navWin = detectNavWin(); setBarShown(systemBarsVisible() || navWin) }; handler.postDelayed(this, if (SysTasks.trusted(this@TaskbarCore)) 1500 else 4000) }
    }

    private fun start() {
        try {
            instance = this
            SysUi.listeners.add(shadeListener)
            try { baseContext.registerComponentCallbacks(configCb) } catch (_: Throwable) {}
            try { contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor(NavMode.KEY), false, navObserver) } catch (_: Throwable) {}
            uctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
            Bus.add(listener)
            sync()
            handler.postDelayed(pruneTick, 4000)
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Taskbar service failed to initialize", e)
            removeBar()
            if (instance === this) instance = null
        }
    }

    fun stop() {
        SysUi.listeners.remove(shadeListener)
        stopSampler()
        edge?.stop()
        try { baseContext.unregisterComponentCallbacks(configCb) } catch (_: Throwable) {}
        handler.removeCallbacks(pruneTick)
        try { contentResolver.unregisterContentObserver(navObserver) } catch (_: Throwable) {}
        SysUi.taskbarEnabled(false)
        Bus.remove(listener)
        closeDrawer()
        removeBar()
        if (instance === this) instance = null
    }

    /**
     * Removes apps whose process no longer exists (swiped away from Recents). This only works when the
     * ROM lets third-party apps see other apps' processes; if the list looks restricted we do nothing.
     */
    private fun pruneDead() {
        if (running.isEmpty() || bar == null) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val procs = am.runningAppProcesses ?: return
            val alive = HashSet<String>()
            for (p in procs) p.pkgList?.let { alive.addAll(it) }
            if (alive.count { it != packageName } < 4) return
            var ch = false
            val it = running.iterator()
            while (it.hasNext()) {
                val pkg = it.next()
                if (pkg !in alive && pkg != focused) { it.remove(); ch = true }
            }
            if (ch) bar?.refresh()
        } catch (_: Exception) {}
    }

    override fun pickIme() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }
        catch (_: Exception) {}
    }

    private fun skip(p: String): Boolean {
        if (p == packageName || p == "com.android.systemui") return true
        return packageManager.getLaunchIntentForPackage(p) == null
    }

    private fun scan() {
        var fg: String? = null
        var imeWin = false
        val vis = LinkedHashSet<String>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) imeWin = true
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val p = w.root?.packageName?.toString() ?: continue
                if (skip(p)) continue
                vis += p
                if (w.isActive) fg = p
            }
        } catch (_: Exception) {}
        var changed = false
        // As a system app the list comes from the system itself (the Recents list): an app is "running" exactly while its task exists.
        if (syncTasks(fg)) {
            var c2 = taskCh
            if (fg != focused) { if (expanded && drawer == null) handler.post { setExpanded(false) }; focused = fg; c2 = true }
            val ime0 = imeWin || editing || SysUi.has(SysUiState.IME_SHOWING)
            if (ime0 != imeShown) { imeShown = ime0; c2 = true }
            if (c2) bar?.refresh()
            navWin = detectNavWin(); sync(false); return
        }
        // Keep a short recent-app list, not just windows visible on this exact frame.
        // Order is kept (first opened = first) so the taskbar does not jump around while scrolling.
        val prevFocus = focused
        for (p in vis) {
            if (p in dismissed) {
                if (p == fg && p != prevFocus) dismissed.remove(p) else continue
            }
            if (running.add(p)) changed = true
        }
        while (running.size > 10) { running.remove(running.first()); changed = true }
        if (fg != focused) { if (expanded && drawer == null) handler.post { setExpanded(false) }; focused = fg; changed = true }
        val ime = imeWin || editing
        if (ime != imeShown) { imeShown = ime; changed = true }
        if (changed) bar?.refresh()
        navWin = detectNavWin()
        // Accessibility events also arrive when the keyguard is shown/hidden.
        sync(false)
    }

    /**
     * System-app mode: rebuilds [running] from the system's task list. Returns false (and changes nothing) when the system does not
     * show other apps' tasks, i.e. when the launcher is an ordinary app.
     */
    private var taskCh = false
    private fun syncTasks(fg: String? = null): Boolean {
        val l = SysTasks.tasks(this) ?: return false
        val homes = try {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        val alive = LinkedHashSet<String>()
        for (t in l) if (!skip(t.pkg) && t.pkg !in homes) alive += t.pkg
        dismissed.retainAll(alive)          // a hidden app that has no task any more may come back when it is opened again
        val f = fg ?: focused
        val pk = LinkedHashSet<String>()
        for (p in alive) { if (p in dismissed) { if (p == f && p != focused) dismissed.remove(p) else continue }; pk += p }
        var ch = running.retainAll(pk)
        for (p in pk) if (running.add(p)) ch = true
        taskCh = ch
        if (ch && bar != null && fg == null) bar?.refresh()
        return true
    }

    override fun hideRunning(pkg: String) { running.remove(pkg); dismissed.add(pkg); bar?.refresh() }
    override fun closeApp(pkg: String) { Thread { SysTasks.removeApp(this, pkg); handler.post { running.remove(pkg); bar?.refresh() } }.start() }
    override fun canClose(): Boolean = SysTasks.trusted(this)
    /** Actions of the old accessibility service, done without it where the system lets an app do so. */
    private fun a11yAction(a: Int) {
        when (a) {
            AccessibilityService.GLOBAL_ACTION_BACK -> SysUi.back()
            AccessibilityService.GLOBAL_ACTION_HOME ->
                try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> SysUi.toggleNotifications()
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN -> try {
                val pm = getSystemService(android.os.PowerManager::class.java)
                pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, SystemClock.uptimeMillis())
            } catch (_: Throwable) {}
            else -> {}   // split screen, screenshot, power menu: only an accessibility service can do these
        }
    }
    override fun global(action: Int) {
        // Back goes through SystemUI's own proxy when connected: exactly what the stock navigation bar does
        if (action == AccessibilityService.GLOBAL_ACTION_BACK && SysUi.back()) return
        // Recents: our own Overview screen (same one SystemUI opens through TouchInteractionService)
        if (action == AccessibilityService.GLOBAL_ACTION_RECENTS) { com.android.quickstep.RecentsActivity.toggle(this); return }
        a11yAction(action)
    }

    override fun navLong(kind: Int) {
        val key = when (kind) { 0 -> Prefs.K_LP_BACK; 1 -> Prefs.K_LP_HOME; else -> Prefs.K_LP_RECENTS }
        try {
            when (Prefs.getInt(this, key, if (kind == 1) NavActions.ASSIST else NavActions.NONE)) {
                NavActions.ASSIST -> startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                NavActions.SPLIT -> a11yAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                NavActions.SHOT -> a11yAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                NavActions.LOCK -> a11yAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                NavActions.POWER -> a11yAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                NavActions.NOTIF -> a11yAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                NavActions.QS -> a11yAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                NavActions.DRAWER -> toggleDrawer()
                NavActions.BACK -> a11yAction(AccessibilityService.GLOBAL_ACTION_BACK)
                NavActions.HOME -> a11yAction(AccessibilityService.GLOBAL_ACTION_HOME)
                NavActions.RECENTS -> com.android.quickstep.RecentsActivity.toggle(this)
            }
        } catch (e: Exception) { Log.e("DesktopTaskbar", "Long-press action failed", e) }
    }
    override fun openSearch() {
        if (drawer == null) openDrawer()
        drawer?.post { drawer?.focusSearch() }
    }

    private val configCb = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { handler.post { sync() } }
        override fun onLowMemory() {}
    }

    /** Re-reads preferences: adds/removes the bar and updates its size. */
    private var edge: EdgeGestures? = null
    /** Edge back gesture: only in gesture navigation and when switched on. */
    private fun updateEdge() {
        val want = NavMode.gesture(this) && Prefs.bool(this, Prefs.K_EDGE_BACK, true)
        if (want) { val e = edge ?: EdgeGestures(this) { SysUi.back() }.also { edge = it }; e.start() }
        else edge?.stop()
    }

    private fun sync(refresh: Boolean = true) {
        updateEdge()
        try {
            val keyguardLocked = try { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true } catch (_: Exception) { false }
            if (keyguardLocked) {
                removeBar(); closeDrawer(); return
            }
            // While the system bars are hidden (video, fullscreen) keep the bar where it was and just hide it.
            val insetVis = systemBarsVisible()
            val vis = insetVis || navWin
            val side = if (insetVis) navSide() else barSide   // always the edge that is the device's own bottom
            if (bar != null && barSide != side) removeBar()
            val b = bar
            if (b != null && side != 0) {
                val lp = b.layoutParams as? WindowManager.LayoutParams
                if (lp != null && lp.width != winThick()) { removeBar(); addBar() } else if (refresh) { b.refresh(); applyWindow() }
            } else if (b == null) addBar() else {
                val lp = b.layoutParams as? WindowManager.LayoutParams
                if (lp != null && (refresh || lp.height != winThick())) {
                    lp.height = winThick()
                    b.setMode(collapsedNow(), lp.height)
                    place(lp, b, 0)
                    if (navMode) NavWindow.provideInsets(lp, 0, insetThick(), uctx.dp(48))
                    Blur.apply(lp, uctx, 24)
                    try { wm.updateViewLayout(b, lp) } catch (t: Throwable) {
                        Log.e("DesktopTaskbar", "Could not update taskbar window", t)
                    }
                }
                if (refresh) b.refresh()
            }
            setBarShown(vis)
            scheduleSample()
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Taskbar sync failed", e)
            removeBar()
        }
    }

    /**
     * Which screen edge the bar is on. From 600dp up it is always the bottom. Below 600dp (phones) it stays on the device's own
     * bottom edge when the phone is turned: that is the right or left edge in landscape, and the icons turn to stay upright.
     */
    private fun navSide(): Int = try {
        if (!gest() && resources.configuration.smallestScreenWidthDp >= 600) 0
        else when (wm.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 2
            android.view.Surface.ROTATION_270 -> 1
            else -> 0
        }
    } catch (_: Exception) { 0 }

    /** Screen size in pixels (WindowMetrics exists from Android 11). */
    private fun screenBounds(): android.graphics.Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") run { val p = android.graphics.Point(); wm.defaultDisplay.getRealSize(p); android.graphics.Rect(0, 0, p.x, p.y) }

    /** False while both the navigation bar and the status bar are hidden. The metrics only count bars that are showing. */
    private fun systemBarsVisible(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 30) true else {   // Android 7-10 cannot tell: the taskbar simply stays
        val w = wm.currentWindowMetrics.windowInsets
        val n = w.getInsets(android.view.WindowInsets.Type.navigationBars())
        val st = w.getInsets(android.view.WindowInsets.Type.statusBars())
        n.left + n.right + n.bottom + n.top + st.top > 0
        }
    } catch (_: Exception) { true }

    /**
     * The buttons that swipe up from the edge (or appear when the mouse touches the edge) are the system's own navigation bar
     * window; it only exists while they are showing. The insets do not report these short-lived ("transient") bars.
     */
    private var navWin = false

    private fun detectNavWin(): Boolean = try {
        val b = screenBounds()
        val sw = b.width(); val sh = b.height()
        val r = android.graphics.Rect()
        windows.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            if (w.title?.toString() == "DesktopTaskbar") return@any false   // our own bar is a system-type window too
            w.getBoundsInScreen(r)
            if (r.left < 0 || r.top < 0 || r.right > sw || r.bottom > sh || r.isEmpty) return@any false
            val bottomStrip = r.bottom >= sh - 2 && r.height() <= sh / 4 && r.width() >= sw / 2
            val sideStrip = (r.left <= 2 || r.right >= sw - 2) && r.width() <= sw / 4 && r.height() >= sh / 2
            bottomStrip || sideStrip
        }
    } catch (_: Exception) { false }

    private fun statusTop(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        else Compat.statusBarHeight(this)
    } catch (_: Exception) { 0 }

    private fun addBar() {
        var v: TaskbarView? = null
        try {
            val side = navSide()
            val thick = winThick(side)
            val top = if (side != 0) statusTop() else 0
            val len = screenBounds().height()
            v = TaskbarView(uctx, this, true, false, side, len, top, collapsedNow(), thick)
            fun params(nav: Boolean): WindowManager.LayoutParams {
                val lp = WindowManager.LayoutParams(
                    if (side != 0) thick else MATCH, if (side != 0) len else thick,
                    if (nav) NavWindow.TYPE else overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = if (side == 0) Gravity.BOTTOM else Gravity.TOP or (if (side == 2) Gravity.END else Gravity.START)
                lp.setFitInsetsTypes(0)
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                place(lp, v!!, side)
                if (nav) {
                    NavWindow.prepare(lp)
                    NavWindow.provideInsets(lp, side, insetThick(), uctx.dp(48))
                } else {
                    // not under the punch hole or a side navigation bar in landscape
                    val si = Insets.safe(uctx)
                    if (!floatingNow() && side == 0 && si.left + si.right > 0) {
                        lp.width = screenBounds().width() - si.left - si.right
                        lp.x = (si.left - si.right) / 2
                    }
                }
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                lp.title = "DesktopTaskbar"
                Blur.apply(lp, uctx, 24)
                return lp
            }
            // First the real navigation-bar window (reserves space, like the stock taskbar). If the system refuses it
            // (missing signature permissions) the bar is added as the old accessibility overlay instead.
            var done = false
            if (!NavWindow.refused) {
                try { wm.addView(v, params(true)); done = true; navMode = true }
                catch (t: Throwable) { NavWindow.refused = true; Log.w("DesktopTaskbar", "Navigation-bar window refused, using overlay: $t") }
            }
            if (!done) { wm.addView(v, params(false)); navMode = false }
            bar = v
            barSide = side
            barShown = true
            // follow the system bars: hidden in video / fullscreen, back when the system buttons come back
            v.onInsets = { handler.post { sync(false) } }
            v.onContent = { handler.post { fitBar() } }
            lastInk?.let { v.setInk(it) }
            if (SysUi.connected) onSysUiConnected()
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not add taskbar overlay", e)
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
            bar = null
        }
    }

    /** The window stays alive (so it keeps receiving inset changes) but is invisible and lets touches through while hidden. */
    private fun setBarShown(show: Boolean) {
        val b = bar ?: return
        if (show == barShown) return
        barShown = show
        try {
            val lp = b.layoutParams as WindowManager.LayoutParams
            lp.flags = if (show) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                       else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            b.alpha = if (show) 1f else 0f
            wm.updateViewLayout(b, lp)
            if (!show) closeDrawer()
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not show/hide taskbar", e)
        }
    }

    private fun removeBar() {
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar = null
    }

    override fun toggleDrawer() { if (drawer != null) closeDrawer() else openDrawer() }

    fun openDrawer() {
        if (drawer != null) return
        if (autoHide() && !expanded && bar != null) setExpanded(true)
        val gap = if (floatingNow()) uctx.dp(12) else 0
        val barH = if (bar != null && barSide == 0) winThick() else 0
        val barW = if (bar != null && barSide != 0) winThick() + gap else 0
        val screenH = screenBounds().height()
        val d = DrawerView(uctx, uctx.dp(if (floatingNow() && barSide == 0 && !autoHide()) 0 else if (floatingNow()) 12 else 8), false, if (barW > 0) barSide else 0, floatingNow() && barW == 0) { closeDrawer() }
        d.dragHook = { on ->
            // while an icon is carried, the drawer window lets touches / drops through to the desktop
            try {
                val dlp = d.layoutParams as WindowManager.LayoutParams
                dlp.flags = if (on) dlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            else dlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                wm.updateViewLayout(d, dlp)
            } catch (_: Exception) {}
            LauncherActivity.blurFromOverlay(!on && drawer === d)
        }
        val lp = WindowManager.LayoutParams(
            if (barW > 0) screenBounds().width() - barW else MATCH, screenH - barH - (if (barW > 0) statusTop() else 0),
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (barSide == 1) Gravity.END else Gravity.START)
        if (barW > 0) lp.y = statusTop()
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        lp.title = "DesktopDrawer"
        // Do not use BLUR_BEHIND on this full-screen touch layer: Android blurs the entire
        // app area and some ROMs also stop dispatching touches. The panel itself is translucent.
        try { wm.addView(d, lp); drawer = d; d.post { d.onShown() }; LauncherActivity.blurFromOverlay(true) } catch (_: Exception) {}
        handler.removeCallbacks(stashRun)   // the taskbar stays while the drawer is open
        raiseBar()
    }

    /**
     * The keyboard is layered directly above the window that owns the text field (the drawer). Re-adding the
     * taskbar afterwards puts it above the drawer, so it also stays above the keyboard.
     */
    private fun raiseBar() {
        val b = bar ?: return
        try {
            val lp = b.layoutParams
            wm.removeViewImmediate(b)
            wm.addView(b, lp)
        } catch (e: Exception) {
            Log.e("DesktopTaskbar", "Could not raise taskbar", e)
            bar = null
            sync()
        }
    }

    fun closeDrawer() {
        drawer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        drawer = null
        LauncherActivity.blurFromOverlay(false)
        if (expanded) setExpanded(false)   // after the drawer: hide the taskbar, the gesture handle returns
    }

    fun isDrawerOpen() = drawer != null
}

class TaskbarView(private val ctx: Context, private val svc: TaskbarHost, private val showNav: Boolean, private val pill: Boolean,
                  private val side: Int = 0, private val len: Int = 0, private val inset: Int = 0,
                  collapsed0: Boolean = false, thick0: Int = 0) : FrameLayout(ctx) {
    private var collapsed = collapsed0
    private var thickPx = if (thick0 > 0) thick0 else ctx.dp(Prefs.tbDp(ctx))
    private val handleV = View(ctx)
    private var handleDark = true
    private var inkColor: Int? = null
    /** Colour of the buttons: the theme's, or (transparent bar) light / dark by what is behind the bar. */
    fun ink(): Int = inkColor ?: Colors.text(ctx)
    fun inkOrNull(): Int? = inkColor
    fun setInk(c: Int?) { if (c == inkColor) return; inkColor = c; if (!collapsed) refresh() }
    private var gMouse = false
    private var hoverPending = false
    private val hoverRun = Runnable { hoverPending = false; if (collapsed) svc.expandBar(true) }
    private var lenPx = len
    private var reserveNow = 0
    /** Called after the icons were rebuilt (the pill's width follows). */
    var onContent: (() -> Unit)? = null
    /**
     * Side mode: the whole bar is laid out horizontally in [body] (len x thickness) and turned 90 degrees so it lies along the
     * screen edge; the icons are turned back, so they stay upright like the system navigation buttons do.
     */
    /** Set by the service: called when the window insets change, so it can check whether the system bars are showing. */
    var onInsets: (() -> Unit)? = null

    private val body: FrameLayout? = if (side != 0) FrameLayout(ctx) else null
    /** Bottom bar: holds the icons; it is the whole window, or (pill) a smaller box inside the full-size window. */
    private val pillBox = FrameLayout(ctx)
    private val host: FrameLayout get() = body ?: pillBox
    private val center = LinearLayout(ctx)
    private val right = LinearLayout(ctx)
    private val appsRow = LinearLayout(ctx)
    private var curCell = 0
    private val scroll = SnapScroll(ctx) { curCell }
    private var menu: PopupMenuX? = null
    private var lastFocused: String? = null

    init {
        // The taskbar is always left-to-right, whatever direction the rest of the UI uses.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        center.layoutDirection = View.LAYOUT_DIRECTION_LTR
        right.layoutDirection = View.LAYOUT_DIRECTION_LTR
        appsRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        scroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        pillBox.elevation = if (pill) ctx.dp(6).toFloat() else 0f
        if (body == null) addView(pillBox, LayoutParams(MATCH, MATCH))
        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER_VERTICAL
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        appsRow.orientation = LinearLayout.HORIZONTAL
        appsRow.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.addView(appsRow, FrameLayout.LayoutParams(WRAP, MATCH))
        if (showNav) setOnApplyWindowInsetsListener { _, ins -> onInsets?.invoke(); ins }
        host.addView(center, LayoutParams(MATCH, MATCH, Gravity.START or Gravity.CENTER_VERTICAL).also {
            it.leftMargin = ctx.dp(4)
        })
        host.addView(right, LayoutParams(WRAP, MATCH, Gravity.END).also { it.rightMargin = ctx.dp(8) })
        host.addView(handleV, LayoutParams(ctx.dp(108), ctx.dp(4), Gravity.CENTER))
        if (body != null) {
            val thick = thickPx
            body.pivotX = 0f; body.pivotY = 0f
            // Left edge (device top to the right): the bar reads top to bottom. Right edge: bottom to top, like the system buttons.
            if (side == 1) { body.rotation = 90f; body.translationX = thick.toFloat() }
            else { body.rotation = -90f; body.translationY = lenPx.toFloat() }
            // keep the icons clear of the status bar area, which is now part of the bar
            body.setPadding(if (side == 1) inset else 0, 0, if (side == 2) inset else 0, 0)
            addView(body, LayoutParams(lenPx, thick))
        }
        layoutPill()
        refresh()
    }

    /** The bar is drawn as a floating pill: gesture navigation (when shown) or the pill option with three buttons. */
    private fun floatingShape() = showNav && !collapsed && (NavMode.gesture(ctx) || Prefs.bool(ctx, Prefs.K_PILL, false))

    /** Gesture mode: collapsed = only the thin strip with the handle, otherwise the whole taskbar. */
    fun setMode(c: Boolean, t: Int) {
        val changed = c != collapsed || t != thickPx
        collapsed = c; thickPx = t
        layoutPill()
        if (!c) { handleV.animate().cancel(); handleV.scaleX = 1f; handleV.scaleY = 1f }
        body?.let {
            val l = it.layoutParams; l.height = t; it.layoutParams = l
            if (side == 1) it.translationX = t.toFloat()
            val p = if (!c && floatingShape()) 0 else inset   // the pill floats clear of the status bar
            it.setPadding(if (side == 1) p else 0, 0, if (side == 2) p else 0, 0)
        }
        if (changed) refresh()
    }

    /** Dark background behind the strip = light handle, light background = grey handle. */
    fun setHandleDark(d: Boolean) {
        if (d == handleDark) return
        handleDark = d
        if (collapsed) handleV.background = Colors.rounded(if (d) 0xDDFFFFFF.toInt() else 0xCC4A4A4A.toInt(), ctx.dp(2).toFloat())
    }

    /** The handle gets a little thicker while a finger or the mouse is on it, like the system one. */
    private fun widen(on: Boolean) {
        handleV.animate().cancel()
        handleV.animate().scaleX(if (on) 1.35f else 1f).scaleY(if (on) 2f else 1f).setDuration(120).start()
    }

    override fun dispatchHoverEvent(ev: android.view.MotionEvent): Boolean {
        if (showNav && NavMode.gesture(ctx)) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_HOVER_ENTER, android.view.MotionEvent.ACTION_HOVER_MOVE -> {
                    if (collapsed) {
                        widen(true)
                        if (!hoverPending) { hoverPending = true; postDelayed(hoverRun, 300) }
                    } else svc.barHover(true)
                }
                android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                    hoverPending = false; removeCallbacks(hoverRun)
                    if (collapsed) widen(false) else svc.barHover(false)
                }
            }
        }
        return super.dispatchHoverEvent(ev)
    }

    fun curLen() = lenPx

    /** Bottom bar: the pill is a box (icons wide, 48 dp high) at the bottom of the full-size window; otherwise it fills the window. */
    private fun layoutPill() {
        if (body != null) return
        val lp = pillBox.layoutParams as? LayoutParams ?: return
        if (floatingShape()) {
            lp.width = lenPx; lp.height = ctx.dp(Prefs.tbDp(ctx))
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = ctx.dp(12)
        } else {
            lp.width = MATCH; lp.height = MATCH; lp.gravity = Gravity.NO_GRAVITY; lp.bottomMargin = 0
        }
        pillBox.layoutParams = lp
    }

    /** Length along the screen edge (the pill is as long as its icons). */
    fun setLength(l: Int) {
        if (l == lenPx) return
        lenPx = l
        layoutPill()
        body?.let {
            val lp = it.layoutParams; lp.width = l; it.layoutParams = lp
            if (side == 2) it.translationY = l.toFloat()
        }
    }

    /** How long the bar wants to be: the icons plus the right-hand part. */
    fun contentLen(): Int {
        var w = 0
        for (i in 0 until appsRow.childCount) {
            val lp = appsRow.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            w += lp.width + lp.leftMargin + lp.rightMargin
        }
        return w + curCell * 2 + ctx.dp(4) + reserveNow + ctx.dp(8)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (showNav && NavMode.gesture(ctx)) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { svc.barOutside(); return true }
            // the window is full width: a touch on its empty part (beside the pill) counts as "outside"
            if (!collapsed && body == null && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                val x = ev.x.toInt(); val y = ev.y.toInt()
                if (x < pillBox.left || x > pillBox.right || y < pillBox.top || y > pillBox.bottom) { svc.barOutside(); return true }
            }
            svc.barTouched()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Back / Home / Recents buttons are shown in the taskbar with three-button navigation; in gesture navigation they are swipes. */
    private fun navButtons() = showNav && Prefs.bool(ctx, Prefs.K_NAV, true) && !NavMode.gesture(ctx)

    // ---- Gesture navigation: swipe from the bar towards the screen: quickly = Home, slowly and far (hold) = Recents ----
    private val gSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var gx = 0f; private var gy = 0f; private var gActive = false
    private var gVt: android.view.VelocityTracker? = null

    /** How far the finger went towards the inside of the screen (up for a bottom bar, left / right for a side bar). */
    private fun inward(x: Float, y: Float) = when (side) { 1 -> x - gx; 2 -> gx - x; else -> gy - y }
    private fun across(x: Float, y: Float) = when (side) { 0 -> x - gx; else -> y - gy }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!showNav || !NavMode.gesture(ctx)) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gx = ev.rawX; gy = ev.rawY; gActive = false
                gVt?.recycle(); gVt = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                gVt?.addMovement(ev)
                if (!gActive) {
                    val d = inward(ev.rawX, ev.rawY); val o = Math.abs(across(ev.rawX, ev.rawY))
                    if (d > gSlop * 2 && d > o * 1.5f) { gActive = true; return true }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { gVt?.recycle(); gVt = null }
        }
        return false
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!showNav || !NavMode.gesture(ctx)) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gx = ev.rawX; gy = ev.rawY; gActive = false
                gMouse = ev.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_MOUSE
                if (collapsed) widen(true)
                gVt?.recycle(); gVt = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                gVt?.addMovement(ev)
                if (!gActive) {
                    val d = inward(ev.rawX, ev.rawY); val o = Math.abs(across(ev.rawX, ev.rawY))
                    if (d > gSlop * 2 && d > o * 1.5f) gActive = true
                }
                return true
            }
        }
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            val was = gActive
            gActive = false
            if (collapsed) {
                if (!hoverPending) widen(false)
                // a mouse click on the strip shows the taskbar at once
                if (!was && gMouse && ev.actionMasked == android.view.MotionEvent.ACTION_UP) svc.expandBar()
            }
            val vt = gVt; gVt = null
            vt?.addMovement(ev)
            vt?.computeCurrentVelocity(1000)
            val speed = when (side) { 1 -> vt?.xVelocity ?: 0f; 2 -> -(vt?.xVelocity ?: 0f); else -> -(vt?.yVelocity ?: 0f) }
            vt?.recycle()
            val dist = inward(ev.rawX, ev.rawY)
            if (was && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                if (collapsed) {
                    // thin strip: a light swipe shows the taskbar; a long slow one = Recents; a long fast one = Home
                    performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                    if (dist >= ctx.dp(110) && speed < 600f) svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    else if (dist >= ctx.dp(80) && speed >= 800f) svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                    else svc.expandBar()
                } else if (dist >= ctx.dp(24)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                    if (dist >= ctx.dp(110) && speed < 600f) svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    else svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }
        }
        return true
    }

    /** Mouse wheel scrolls the app strip sideways. */
    override fun onGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
            val dy = ev.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            val dx = ev.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
            scroll.scrollBy(((dx - dy) * ctx.dp(48)).toInt(), 0)
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun ripple(): android.graphics.drawable.Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33000000), null, Colors.rounded(0xFFFFFFFF.toInt(), ctx.dp(10).toFloat()))

    fun refresh() {
        if (collapsed) {
            // only the gesture handle
            host.background = null
            center.removeAllViews(); right.removeAllViews(); appsRow.removeAllViews()
            center.visibility = View.GONE; right.visibility = View.GONE
            handleV.background = Colors.rounded(if (handleDark) 0xDDFFFFFF.toInt() else 0xCC4A4A4A.toInt(), ctx.dp(2).toFloat())
            handleV.visibility = View.VISIBLE
            return
        }
        handleV.visibility = View.GONE
        center.visibility = View.VISIBLE
        val barCol = Colors.taskbar(ctx)
        val floating = floatingShape()
        // transparent bar: no Material You colour, only a neutral scrim (light theme: white, dark theme: black) of the chosen strength
        val clear = showNav && Prefs.bool(ctx, Prefs.K_BAR_CLEAR, true)
        val scrimCol = (if (Colors.night(ctx)) 0 else 0xFFFFFF) or ((Prefs.scrim(ctx) * 255 / 100) shl 24)
        val bg = if (pill) Colors.panel(ctx, barCol, ctx.dp(28).toFloat())
                 else if (floating) {
                     // thin Material You outline so the pill stands out on any background
                     val r = ctx.dp(Prefs.tbDp(ctx)) / 2f
                     val ring = android.graphics.drawable.GradientDrawable().apply {
                         shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                         cornerRadius = r
                         setColor(0)
                         setStroke(ctx.dp(1).coerceAtLeast(1), (Colors.accent(ctx) and 0x00FFFFFF) or 0xE6000000.toInt())
                     }
                     android.graphics.drawable.LayerDrawable(arrayOf(Colors.panel(ctx, if (clear) scrimCol else barCol, r), ring))
                 }
                 // flat bar: no colour of its own (the apps paint the band, now that the system scrim is off), or the Material You colour
                 else if (clear) android.graphics.drawable.ColorDrawable(scrimCol)
                 else android.graphics.drawable.ColorDrawable(barCol)
        host.background = bg
        // Keep the scroll position across rebuilds (the strip used to jump back to the start).
        val keepX = scroll.scrollX
        center.removeAllViews()
        right.removeAllViews()
        appsRow.removeAllViews()
        val h = ctx.dp(Prefs.tbDp(ctx))
        val cell = minOf(h, ctx.dp(52))
        curCell = cell
        right.visibility = if (showNav) View.VISIBLE else View.GONE

        center.addView(StartButton(ctx).also {
            it.background = ripple()
            it.contentDescription = UiText.text(ctx, "Apps")
            it.setOnClickListener { svc.toggleDrawer() }
        }, LinearLayout.LayoutParams(cell, MATCH))
        center.addView(SearchButton(ctx).also {
            it.background = ripple()
            it.contentDescription = UiText.text(ctx, "Search")
            it.setOnClickListener { svc.openSearch() }
        }, LinearLayout.LayoutParams(cell, MATCH))

        val pinned = Prefs.list(ctx, Prefs.K_PINNED).mapNotNull { ComponentName.unflattenFromString(it) }
        val shownPk = HashSet<String>()
        for (comp in pinned) {
            shownPk += comp.packageName
            appsRow.addView(appItem(comp, svc.running.contains(comp.packageName), svc.focused == comp.packageName, true, cell)
                .also { it.tag = comp.packageName }, LinearLayout.LayoutParams(cell, MATCH))
        }
        val unpinned = svc.running.filter { it !in shownPk }.mapNotNull { pkg -> AppRepo.byPkg(ctx, pkg)?.let { pkg to it.comp } }
        if (unpinned.isNotEmpty() && pinned.isNotEmpty()) {
            val divider = View(ctx)
            divider.setBackgroundColor(ink() and 0x55FFFFFF)
            appsRow.addView(divider, LinearLayout.LayoutParams(ctx.dp(1), (h * 0.58f).toInt()).also {
                it.leftMargin = ctx.dp(5); it.rightMargin = ctx.dp(5)
            })
        }
        for ((pkg, comp) in unpinned) {
            appsRow.addView(appItem(comp, true, svc.focused == pkg, false, cell).also { it.tag = pkg }, LinearLayout.LayoutParams(cell, MATCH))
        }
        center.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        val navCount = if (navButtons()) 3 else 0
        val imeCount = if (showNav && svc.imeShown && Prefs.bool(ctx, Prefs.K_IME, true)) 1 else 0
        val navReserve = if (showNav) cell * (navCount + imeCount) + (if (body != null) ctx.dp(8) else ctx.dp(92)) else ctx.dp(4)
        reserveNow = navReserve
        (center.layoutParams as? LayoutParams)?.let { it.rightMargin = navReserve; center.layoutParams = it }

        val foc = svc.focused
        val focusChanged = foc != lastFocused
        lastFocused = foc
        scroll.post {
            scroll.scrollTo(keepX, 0)
            // A newly opened / switched-to app scrolls into view; otherwise the position stays where the user left it.
            if (focusChanged && foc != null) {
                val v = appsRow.findViewWithTag<View>(foc)
                if (v != null && (v.left < scroll.scrollX || v.right > scroll.scrollX + scroll.width)) {
                    scroll.smoothScrollTo((v.left - (scroll.width - v.width) / 2).coerceAtLeast(0), 0)
                }
            }
        }

        if (showNav && svc.imeShown && Prefs.bool(ctx, Prefs.K_IME, true)) {
            right.addView(ImeButton(ctx).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.pickIme() }
            }, LinearLayout.LayoutParams(cell, MATCH))
        }
        if (navButtons()) {
            right.addView(NavButton(ctx, 0).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_BACK) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(0); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 1).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_HOME) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(1); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
            right.addView(NavButton(ctx, 2).also { b ->
                b.background = ripple(); b.setOnClickListener { svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
                b.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); svc.navLong(2); true }
            }, LinearLayout.LayoutParams(cell, MATCH))
        }
        if (showNav && body == null) {
            // taskbar mode: the category button takes the place of the time
            val pill = CatPill(ctx, 68, 22, outline = { Colors.accent(ctx) }, ink = { null }) { (Colors.iconBg(ctx) and 0x00FFFFFF) or 0xCC000000.toInt() }
            pill.setOnClickListener {
                Launch.start(ctx, android.content.Intent(ctx, LauncherActivity::class.java).setAction(LauncherActivity.ACTION_CATEGORIES))
            }
            right.addView(pill, LinearLayout.LayoutParams(ctx.dp(68), ctx.dp(22)).also {
                it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = ctx.dp(6); it.marginEnd = ctx.dp(6)
            })
        } else if (!showNav) {
            val clock = TextClock(ctx)
            clock.format12Hour = "h:mm a"
            clock.format24Hour = "HH:mm"
            clock.textSize = 13f
            clock.setTextColor(Colors.text(ctx))
            clock.gravity = Gravity.CENTER
            clock.setPadding(ctx.dp(8), 0, ctx.dp(4), 0)
            right.addView(clock, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        if (body != null) uprightIcons(body)
        onContent?.invoke()
    }

    /** Side mode: turns every icon back by the bar's rotation (the running-dot lines and dividers turn with the bar). */
    private fun uprightIcons(g: android.view.ViewGroup) {
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v is android.view.ViewGroup) uprightIcons(v)
            else if (v is android.widget.ImageView || v is NavButton || v is SearchButton || v is StartButton || v is ImeButton) v.rotation = if (side == 1) -90f else 90f
        }
    }

    private fun appItem(comp: ComponentName, running: Boolean, active: Boolean, pinned: Boolean, cell: Int): View {
        val f = FrameLayout(ctx)
        f.background = ripple()
        val ic = BadgedIcon(ctx)
        ic.setImageDrawable(Icons.app(ctx, comp, false))
        ic.dot = Prefs.bool(ctx, Prefs.K_DOTS, true) && NotifState.has(comp.packageName)
        val s = (cell * 0.58f).toInt()
        f.addView(ic, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        if (running) {
            val line = View(ctx)
            line.background = Colors.rounded(inkOrNull() ?: Colors.accent(ctx), ctx.dp(2).toFloat())   // same light/dark as the icons
            f.addView(line, FrameLayout.LayoutParams(ctx.dp(if (active) 18 else 6), ctx.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .also { it.bottomMargin = ctx.dp(3) })
        }
        f.setOnClickListener { Launch.app(ctx, comp); svc.appLaunched() }
        val show = { x: Int, y: Int ->
            menu?.dismiss()
            val m = PopupMenuX(ctx, this)
            menu = m
            val items = ArrayList<MenuItem>()
            items += MenuItem("Open") { Launch.app(ctx, comp) }
            items += MenuItem("App Info") { Launch.info(ctx, comp.packageName) }
            items += Launch.sizeMenu(ctx, comp)
            items += MenuItem(if (pinned) "Unpin from taskbar" else "Pin to taskbar") {
                Prefs.toggleInList(ctx, Prefs.K_PINNED, comp.flattenToString())
            }
            if (running && svc.canClose()) items += MenuItem("Close") { svc.closeApp(comp.packageName) }
            if (running) items += MenuItem("Hide from taskbar") { svc.hideRunning(comp.packageName) }
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            m.show(x, loc[1], items, above = true)
        }
        f.setOnLongClickListener {
            val loc = IntArray(2); f.getLocationOnScreen(loc); show(loc[0], 0); true
        }
        f.setSecondaryClick { x, y -> show(x, y) }
        return f
    }
}

/** App drawer icon: 3x3 dots, same as the all-apps button of the Launcher3 taskbar. */
class StartButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.color = barInk() ?: Colors.accent(context)   // follows the light/dark buttons; Material You colour only when the bar is not transparent
        val s = minOf(width, height) * 0.38f
        val step = s / 2f
        val r = s * 0.11f
        val l = (width - s) / 2
        val t = (height - s) / 2
        for (i in 0..2) for (j in 0..2) canvas.drawCircle(l + i * step, t + j * step, r, p)
    }
}

/** Language / keyboard switcher (globe). Shown while a keyboard is in use. */
class ImeButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(canvas: Canvas) {
        p.color = barFg()
        p.strokeWidth = context.dp(2).toFloat() * 0.8f
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.2f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p)
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
    }
}

/** The light/dark button colour the bar has chosen, or null when it is not following the background. */
fun View.barInk(): Int? {
    var p: android.view.ViewParent? = parent
    while (p != null) { if (p is TaskbarView) return p.inkOrNull(); p = p.parent }
    return null
}

/** kind: 0 back, 1 home, 2 recents */
fun View.barFg(): Int {
    var p: android.view.ViewParent? = parent
    while (p != null) { if (p is TaskbarView) return p.ink(); p = p.parent }
    return Colors.text(context)
}

/**
 * The app strip: only whole icons are visible at rest. The strip is as wide as a whole number of icons and, after a drag or
 * mouse-wheel scroll, glides to the nearest icon edge, so no icon is ever cut in half at either end.
 */
class SnapScroll(c: Context, private val cell: () -> Int) : android.widget.HorizontalScrollView(c) {
    private var touching = false
    private val snap = Runnable {
        val row = getChildAt(0) as? android.view.ViewGroup ?: return@Runnable
        var best = scrollX
        var bd = Int.MAX_VALUE
        for (i in 0 until row.childCount) {
            val d = Math.abs(row.getChildAt(i).left - scrollX)
            if (d < bd) { bd = d; best = row.getChildAt(i).left }
        }
        val max = (row.width - width).coerceAtLeast(0)
        best = best.coerceIn(0, max)
        if (Math.abs(best - scrollX) > 1) smoothScrollTo(best, 0)
    }

    override fun onMeasure(w: Int, h: Int) {
        val cw = cell()
        val size = MeasureSpec.getSize(w)
        if (cw > 0 && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && size >= cw) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(size / cw * cw, MeasureSpec.EXACTLY), h)
        } else super.onMeasure(w, h)
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        removeCallbacks(snap)
        if (!touching) postDelayed(snap, 140)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touching = true; removeCallbacks(snap) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { touching = false; removeCallbacks(snap); postDelayed(snap, 160) }
        }
        return super.onTouchEvent(ev)
    }
}

/** What a long press on Back / Home / Recents can do. */
object NavActions {
    const val NONE = 0; const val ASSIST = 1; const val SPLIT = 2; const val SHOT = 3; const val LOCK = 4
    const val POWER = 5; const val NOTIF = 6; const val QS = 7; const val DRAWER = 8
    const val BACK = 9; const val HOME = 10; const val RECENTS = 11
    val names = listOf("None", "Assistant (Circle to Search where the system allows)", "Split screen", "Screenshot", "Lock screen",
        "Power menu", "Notifications", "Quick settings", "App drawer", "Back", "Home", "Recents")
}

/** Look of the Back / Home / Recents buttons. 0 = follows the phone's make. */
object NavStyle {
    const val SYSTEM = 0; const val LAUNCHER = 1; const val SAMSUNG = 2; const val PIXEL = 3; const val VIVO = 4; const val OPPO = 5; const val XIAOMI = 6
    val names = listOf("System (like this phone)", "Desktop Launcher", "Samsung", "Pixel", "Vivo", "Oppo", "Xiaomi")

    fun resolve(c: Context): Int {
        val v = Prefs.getInt(c, Prefs.K_NAVSTYLE, SYSTEM)
        if (v != SYSTEM) return v
        val m = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> XIAOMI
            m.contains("samsung") -> SAMSUNG
            m.contains("vivo") || m.contains("iqoo") -> VIVO
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> OPPO
            else -> PIXEL
        }
    }
}

class NavButton(c: Context, private val kind: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }

    private fun stroke(w: Float) { p.style = Paint.Style.STROKE; p.strokeWidth = w }
    private fun fill(w: Float = 0f) { p.style = if (w > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL; p.strokeWidth = w }

    /** Triangle pointing left; filled ones get softly rounded corners from a round-joined outline of the same colour. */
    private fun tri(cv: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean, w: Float) {
        val pad = if (filled) w / 2f else 0f
        val path = Path()
        path.moveTo(cx + r * 0.85f - pad, cy - r + pad); path.lineTo(cx - r * 0.85f + pad, cy); path.lineTo(cx + r * 0.85f - pad, cy + r - pad); path.close()
        if (filled) fill(w) else stroke(w)
        cv.drawPath(path, p)
    }

    private fun chevron(cv: Canvas, cx: Float, cy: Float, r: Float, w: Float) {
        val path = Path()
        path.moveTo(cx + r * 0.45f, cy - r * 0.95f); path.lineTo(cx - r * 0.45f, cy); path.lineTo(cx + r * 0.45f, cy + r * 0.95f)
        stroke(w); cv.drawPath(path, p)
    }

    private fun square(cv: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean, w: Float, corner: Float) {
        val pad = if (filled) w / 2f else 0f
        val rc = RectF(cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad)
        if (filled) fill(w) else stroke(w)
        cv.drawRoundRect(rc, corner, corner, p)
    }

    override fun onDraw(canvas: Canvas) {
        p.color = barFg()
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.17f      // same size as before: the shapes are about 0.34 of the cell
        val dp2 = context.dp(2).toFloat()
        when (NavStyle.resolve(context)) {
            NavStyle.LAUNCHER -> when (kind) {
                0 -> tri(canvas, cx, cy, r, false, dp2)
                1 -> { stroke(dp2); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r, false, dp2, r * 0.35f)
            }
            NavStyle.XIAOMI -> when (kind) {
                0 -> tri(canvas, cx, cy, r, true, r * 0.45f)
                1 -> {   // thin ring, a gap, then a solid disc; a little larger than Back and Recents
                    val big = r * 1.22f
                    stroke(r * 0.15f); canvas.drawCircle(cx, cy, big - r * 0.075f, p)
                    fill(); canvas.drawCircle(cx, cy, big * 0.66f, p)
                }
                else -> square(canvas, cx, cy, r * 0.95f, true, r * 0.3f, r * 0.4f)
            }
            NavStyle.PIXEL -> when (kind) {
                0 -> tri(canvas, cx, cy, r, true, r * 0.5f)
                1 -> { fill(); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r * 0.92f, true, r * 0.4f, r * 0.5f)
            }
            NavStyle.SAMSUNG -> when (kind) {
                0 -> chevron(canvas, cx, cy, r, r * 0.3f)
                1 -> { stroke(r * 0.26f); canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.6f, r * 0.6f, p) }   // rounded square
                else -> { stroke(r * 0.28f); for (i in -1..1) canvas.drawLine(cx + i * r * 0.85f, cy - r * 0.9f, cx + i * r * 0.85f, cy + r * 0.9f, p) }
            }
            NavStyle.VIVO -> when (kind) {
                0 -> chevron(canvas, cx, cy, r, r * 0.24f)
                1 -> { stroke(r * 0.24f); canvas.drawCircle(cx, cy, r, p) }
                else -> { stroke(r * 0.24f); for (i in -1..1) canvas.drawLine(cx - r * 0.9f, cy + i * r * 0.7f, cx + r * 0.9f, cy + i * r * 0.7f, p) }
            }
            else -> when (kind) {   // OPPO
                0 -> tri(canvas, cx, cy, r, false, r * 0.24f)
                1 -> { stroke(r * 0.24f); canvas.drawCircle(cx, cy, r, p) }
                else -> square(canvas, cx, cy, r, false, r * 0.24f, r * 0.3f)
            }
        }
    }
}


/** Dedicated taskbar search shortcut, placed beside the app-drawer button. */
class SearchButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        p.color = barFg()
        p.strokeWidth = context.dp(2).toFloat()
        val r = minOf(width, height) * 0.17f
        val cx = width / 2f - r * 0.18f
        val cy = height / 2f - r * 0.18f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.55f, cy + r * 1.55f, p)
    }
}
