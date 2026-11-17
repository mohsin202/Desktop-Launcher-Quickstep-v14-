package com.android.quickstep

import android.animation.LayoutTransition
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.android.desktoplauncher.Colors
import com.android.desktoplauncher.Prefs
import com.android.desktoplauncher.RTask
import com.android.desktoplauncher.Recents
import com.android.desktoplauncher.SysTasks
import com.android.desktoplauncher.UiText
import com.android.desktoplauncher.dp
import com.android.desktoplauncher.setSecondaryClick

/**
 * The Overview (Recents) screen. The name and package are the ones the ROM's config_recentsComponentName points at
 * (com.android.launcher3/com.android.quickstep.RecentsActivity). It follows the stock Quickstep carousel: a picture of each
 * task with its icon, name and a close button above it, newest on the right, swipe a card up to remove it, "Clear all" at
 * the end, tap the empty area or press Back to leave. Hovering a card outlines it; right-click or long-press on the icon
 * opens App info (and "Freeform window" when freeform windows are on). Background: a solid Material You colour, or the
 * blurred wallpaper (Settings > Recent apps). Optionally a line with the memory in use.
 */
class RecentsActivity : Activity() {
    companion object {
        @Volatile private var current: RecentsActivity? = null
        private const val PAGE_SPACING_DP = 16

        fun show(c: Context) {
            if (current != null) return
            try {
                c.startActivity(Intent(c, RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Throwable) {}
        }
        fun hide() { current?.leave() }
        fun toggle(c: Context) { if (current != null) hide() else show(c) }

        // Alt+Tab quick switch (called from the accessibility key filter, main thread)
        private val pendingSteps = ArrayList<Int>()
        private var commitWanted = false
        fun quickStep(c: Context, dir: Int) {
            commitWanted = false
            val a = current
            if (a != null && a.built) a.quickMove(dir) else { pendingSteps.add(dir); if (a == null) show(c) }
        }
        fun quickCommit() {
            val a = current
            if (a != null && a.built) a.quickOpen() else if (a != null || pendingSteps.isNotEmpty()) commitWanted = true
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var scroll: HScroll
    private lateinit var grid: Grid
    private var vw = 0; private var vh = 0; private var reserveB = 0
    private var selCard: TaskCard? = null
    private lateinit var empty: TextView
    private lateinit var memory: TextView
    private val cards = ArrayList<TaskCard>()
    private var clearAll: TextView? = null
    var built = false; private set
    private var tasks: List<RTask> = emptyList()
    private var leaving = false
    private var menu: PopupWindow? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        current = this
        val root = FrameLayout(this)
        // Background: blurred wallpaper (tinted), or a solid Material You colour
        var blurred = false
        if (Build.VERSION.SDK_INT >= 31 && Prefs.bool(this, Prefs.K_REC_BLUR, false)) {
            try {
                if (windowManager.isCrossWindowBlurEnabled) { window.setBackgroundBlurRadius(dp(64)); blurred = true }
            } catch (_: Throwable) {}
        }
        root.setBackgroundColor(if (blurred) (Colors.surfaceSolid(this) and 0x00FFFFFF) or 0x73000000 else Colors.surfaceSolid(this))
        // Built after the first layout (not inside it), once the size is known
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (!built && v.width > 0 && v.height > 0) v.post { if (!built) safeRebuild(v.width, v.height) }
        }
        scroll = HScroll(this) { leave() }
        grid = Grid(this, rtl())
        scroll.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).also {
            it.setTextColor(Colors.text(this)); it.textSize = 20f; it.gravity = Gravity.CENTER; it.visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))
        memory = TextView(this).also {
            it.setTextColor(Colors.text(this)); it.textSize = 14f; it.gravity = Gravity.CENTER; it.visibility = View.GONE
            it.text = memoryText()
        }
        root.addView(memory, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).also { it.bottomMargin = dp(20) })
        setContentView(root)
        tasks = Recents.load(this)
    }

    /** "3.45 GB used of 6 GB" */
    private fun memoryText(): String = try {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val gb = 1024.0 * 1024 * 1024
        val total = Math.round(mi.totalMem / gb).coerceAtLeast(1)   // the system reports a little less than the installed memory
        String.format(java.util.Locale.US, "%.2f GB used of %d GB", (mi.totalMem - mi.availMem) / gb, total)
    } catch (_: Throwable) { "" }

    private fun rtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun safeRebuild(w: Int, h: Int) {
        try { rebuild(w, h) } catch (t: Throwable) {
            empty.text = "Overview error:\n$t"; empty.textSize = 14f; empty.visibility = View.VISIBLE
        }
    }

    private fun rebuild(w: Int, h: Int) {
        if (built) return
        built = true
        if (tasks.isEmpty()) {
            empty.text = UiText.text(this, "No recent items") + "\n\n" + Recents.diag
            pendingSteps.clear(); commitWanted = false
            empty.visibility = View.VISIBLE; return
        }
        val showMem = Prefs.bool(this, Prefs.K_REC_MEM, false)
        if (showMem) memory.visibility = View.VISIBLE
        val barRoom = if (com.android.desktoplauncher.NavMode.gesture(this)) dp(56) else 0   // the floating taskbar
        vw = w; vh = h
        reserveB = (if (showMem) dp(56) else dp(24)) + barRoom
        // newest first: tasks[0] is the focus card (the app that was open), the others follow in a two-row grid
        val btn = TextView(this).also {
            it.text = UiText.text(this, "Clear all")
            it.setTextColor(Colors.text(this)); it.textSize = 16f; it.gravity = Gravity.CENTER
            it.background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Colors.surfaceHigh(this@RecentsActivity)) }
            it.setPadding(dp(22), dp(12), dp(22), dp(12))
            it.setOnClickListener { clearEverything() }
        }
        clearAll = btn
        for (t in tasks) {
            val c = TaskCard(this, t, dp(100), dp(100), dp(56), { open(t) }, { dismiss(it) }, { v -> showMenu(v, t) })
            cards.add(c)
            grid.addView(c)
        }
        grid.addView(btn)
        layoutAll()
        val steps = ArrayList(pendingSteps); pendingSteps.clear()
        if (steps.isNotEmpty()) { selIdx = 0; for (d in steps) quickMove(d) }
        if (commitWanted) { commitWanted = false; main.post { quickOpen() } }
        grid.afterLayout = { grid.post { scroll.scrollTo(if (rtl()) (grid.width - vw).coerceAtLeast(0) else 0, 0) } }
    }

    /**
     * Trebuchet's tablet overview: the focus task up to 70% of the screen, centred; the other tasks in two rows to its side,
     * each row as tall as half the free height. Margins/spacing are the stock sw720dp values.
     */
    private fun layoutAll() {
        if (cards.isEmpty()) return
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat(); val sh = dm.heightPixels.toFloat()
        val side = dp(64); val top = dp(64); val rowSp = dp(36); val pageSp = dp(44); val header = dp(56)
        val gridW = (vw - 2 * side).coerceAtLeast(dp(200)); val gridH = (vh - top - reserveB).coerceAtLeast(dp(200))
        val scale = minOf(gridW / sw, gridH / sh, 0.7f)
        val fw = (sw * scale).toInt(); val fh = (sh * scale).toInt()
        val fx = (vw - fw) / 2; val fy = top + (gridH - fh) / 2
        fun put(c: TaskCard, x: Int, y: Int, w: Int, h: Int) {
            c.resize(w, h, header)
            grid.place(c, x, y - header, w, h + header)
        }
        put(cards[0], fx, fy, fw, fh)
        val rowH = (gridH + top - rowSp) / 2
        val th = (rowH - top).coerceAtLeast(dp(80)); val tw = (th * sw / sh).toInt()
        var topEnd = fx + fw + pageSp; var botEnd = topEnd
        for (i in 1 until cards.size) {
            val isTop = topEnd <= botEnd
            val x = if (isTop) topEnd else botEnd
            put(cards[i], x, if (isTop) top else top + th + rowSp + top, tw, th)
            if (isTop) topEnd += tw + pageSp else botEnd += tw + pageSp
        }
        val btn = clearAll
        var total = Math.max(topEnd, botEnd)
        if (btn != null) {
            btn.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val bw = btn.measuredWidth; val bh = btn.measuredHeight
            grid.place(btn, total, top + (gridH - bh) / 2, bw, bh)
            total += bw
        }
        grid.contentW = Math.max(total + side, vw)
        grid.requestLayout()
    }

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, mh: Int, mv: Int) = LinearLayout.LayoutParams(w, h).also { it.setMargins(mh, mv, mh, mv); it.gravity = Gravity.CENTER_VERTICAL }

    private fun open(t: RTask) {
        Recents.open(this, t)
        leave()
    }

    private fun appInfo(t: RTask) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", t.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
        leave()
    }

    /** Right-click / long-press on the icon: App info directly, or a small menu when freeform windows are on. */
    private fun showMenu(anchor: View, t: RTask) {
        if (!Prefs.bool(this, Prefs.K_FREEFORM, false)) { appInfo(t); return }
        menu?.dismiss()
        val box = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            it.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Colors.surfaceHigh(this@RecentsActivity)) }
            it.setPadding(0, dp(8), 0, dp(8))
            it.elevation = dp(8).toFloat()
        }
        fun item(label: String, run: () -> Unit) {
            box.addView(TextView(this).also {
                it.text = label; it.setTextColor(Colors.text(this)); it.textSize = 15f
                it.setPadding(dp(20), dp(12), dp(28), dp(12))
                it.setOnClickListener { menu?.dismiss(); run() }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        item(UiText.text(this, "App info")) { appInfo(t) }
        item(UiText.text(this, "Freeform window")) {
            Thread { SysTasks.setMode(t.id, 5) }.start()
            main.postDelayed({ open(t) }, 250)
        }
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(8).toFloat()
        pw.setBackgroundDrawable(null)
        menu = pw
        pw.showAsDropDown(anchor, 0, dp(4))
    }

    private fun dismiss(c: TaskCard) {
        Thread { Recents.remove(c.task.id) }.start()
        grid.removeView(c); cards.remove(c)
        if (selCard === c) selCard = null
        if (cards.isEmpty()) main.postDelayed({ leave() }, 180) else layoutAll()
    }

    private fun clearEverything() {
        val ids = cards.map { it.task.id }
        Thread { for (i in ids) Recents.remove(i) }.start()
        for (c in cards) c.animate().alpha(0f).translationY(-c.height / 2f).setDuration(160).start()
        main.postDelayed({ leave() }, 190)
    }

    fun leave() {
        if (leaving) return
        leaving = true
        menu?.dismiss()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> { leave(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveSel(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveSel(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveSel(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveSel(0, 1); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { (selCard ?: cards.firstOrNull())?.let { open(it.task) }; return true }
        }
        return super.onKeyDown(code, e)
    }

    private var selIdx = 0
    /** Alt+Tab: step through the cards in order (wrapping), the first step lands on the previous app. */
    fun quickMove(dir: Int) {
        if (cards.isEmpty()) return
        selIdx = ((if (selCard == null) 0 else cards.indexOf(selCard).coerceAtLeast(0)) + dir + cards.size) % cards.size
        select(cards[selIdx])
    }
    fun quickOpen() {
        val c = selCard ?: cards.firstOrNull()
        if (c != null) open(c.task) else leave()
    }

    /** Keyboard selection: the first arrow press marks the focus card, the next ones move to the nearest card in that direction. */
    private fun moveSel(dx: Int, dy: Int) {
        val cur = selCard
        if (cur == null) { cards.firstOrNull()?.let { select(it) }; return }
        fun cx(v: View) = v.left + v.width / 2
        fun cy(v: View) = v.top + v.height * 3 / 5
        val best = cards.filter { it !== cur && (if (dx != 0) (cx(it) - cx(cur)) * dx > 4 else (cy(it) - cy(cur)) * dy > 4) }
            .minByOrNull {
                val ax = Math.abs(cx(it) - cx(cur)); val ay = Math.abs(cy(it) - cy(cur))
                if (dx != 0) ax + 3 * ay else ay + 3 * ax
            } ?: return
        select(best)
    }
    private fun select(c: TaskCard) {
        selCard?.setSelected2(false)
        selCard = c; c.setSelected2(true)
        val m = dp(64)
        if (c.left < scroll.scrollX + m || c.right > scroll.scrollX + vw - m)
            scroll.smoothScrollTo((c.left + c.width / 2 - vw / 2).coerceIn(0, Math.max(0, grid.width - vw)), 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { leave() }

    // Home / another app on top: the screen is not needed any more
    override fun onStop() {
        super.onStop()
        com.android.desktoplauncher.TaskbarService.instance?.recentsVisible(false)
        if (!leaving) { leaving = true; finish() }
    }
    // the taskbar stays visible in Recents (gesture navigation shows it there, like Trebuchet)
    override fun onResume() { super.onResume(); com.android.desktoplauncher.TaskbarService.instance?.recentsVisible(true) }
    override fun onDestroy() {
        if (current === this) current = null
        com.android.desktoplauncher.TaskbarService.instance?.recentsVisible(false)
        super.onDestroy()
    }
}

/** Scrolls sideways (the mouse wheel too); a tap on empty space leaves. */
private class HScroll(c: Context, val onEmptyTap: () -> Unit) : HorizontalScrollView(c) {
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f

    init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> if (Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop) onEmptyTap()
        }
        return super.onTouchEvent(ev)
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val d = ev.getAxisValue(MotionEvent.AXIS_VSCROLL) + ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (d != 0f) { smoothScrollBy((-d * 160 * resources.displayMetrics.density / 2).toInt(), 0); return true }
        }
        return super.onGenericMotionEvent(ev)
    }
}

/** Places each child at an explicit spot (logical left-to-right coordinates, mirrored in right-to-left); children that move slide there. */
private class Grid(c: Context, val rtl: Boolean) : ViewGroup(c) {
    private val spots = HashMap<View, IntArray>()
    var contentW = 0
    var afterLayout: (() -> Unit)? = null

    init { clipChildren = false; clipToPadding = false }

    fun place(v: View, x: Int, y: Int, w: Int, h: Int) { spots[v] = intArrayOf(x, y, w, h) }
    override fun removeView(v: View) { spots.remove(v); super.removeView(v) }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i); val s = spots[v]
            if (s != null) v.measure(MeasureSpec.makeMeasureSpec(s[2], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s[3], MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(contentW, MeasureSpec.getSize(hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i); val s = spots[v] ?: continue
            val nl = if (rtl) contentW - s[0] - s[2] else s[0]
            val had = v.width > 0
            val ol = v.left; val ot = v.top
            v.layout(nl, s[1], nl + s[2], s[1] + s[3])
            if (had && (ol != nl || ot != s[1])) {
                v.translationX += (ol - nl); v.translationY += (ot - s[1])
                v.animate().translationX(0f).translationY(0f).setDuration(220).start()
            }
        }
        val f = afterLayout
        if (f != null && width > 0) { afterLayout = null; f() }
    }
}

/** The icon as a bitmap of its own (a drawable shared between views would get its bounds changed by the other one). */
private fun iconBitmap(c: Context, d: Drawable, px: Int): Bitmap {
    val bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)
    val dd = d.constantState?.newDrawable()?.mutate() ?: d
    dd.setBounds(0, 0, px, px)
    dd.draw(cv)
    return bm
}

/** Round close button with an X (Material You style). */
private class CloseButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isClickable = true
        contentDescription = UiText.text(c, "Close")
    }
    override fun onDraw(cv: Canvas) {
        val r = Math.min(width, height) / 2f
        p.style = Paint.Style.FILL
        p.color = Colors.surfaceHigh(context)
        cv.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.color = Colors.text(context)
        p.strokeWidth = context.dp(2).toFloat()
        p.strokeCap = Paint.Cap.ROUND
        val a = r * 0.38f
        val cx = width / 2f; val cy = height / 2f
        cv.drawLine(cx - a, cy - a, cx + a, cy + a, p)
        cv.drawLine(cx - a, cy + a, cx + a, cy - a, p)
    }
}

/** A task: icon + name pill and a close button above, the task's picture below. Swipe up (or middle-click) removes it. */
private class TaskCard(c: Context, val task: RTask, var cw: Int, var ch: Int, var headerH: Int,
                       val onOpen: () -> Unit, val onClose: (TaskCard) -> Unit, val onMenu: (View) -> Unit) : LinearLayout(c) {
    private val thumb = Thumb(c, task)
    private val header = FrameLayout(c)
    private val title = TextView(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        val pill = LinearLayout(c).also {
            it.orientation = HORIZONTAL; it.gravity = Gravity.CENTER_VERTICAL
            it.background = GradientDrawable().apply { cornerRadius = c.dp(22).toFloat(); setColor(Colors.surfaceHigh(c)) }
            it.setPadding(c.dp(6), c.dp(4), c.dp(14), c.dp(4))
            it.setOnClickListener { onOpen() }
            it.setOnLongClickListener { onMenu(it); true }
            it.setSecondaryClick { _, _ -> onMenu(it) }
        }
        val icon = ImageView(c).also { it.setImageBitmap(iconBitmap(c, task.icon, c.dp(48))); it.scaleType = ImageView.ScaleType.FIT_CENTER }
        pill.addView(icon, LayoutParams(c.dp(32), c.dp(32)))
        title.also {
            it.text = task.label; it.setTextColor(Colors.text(c)); it.textSize = 14f
            it.maxLines = 1; it.ellipsize = TextUtils.TruncateAt.END; it.setPadding(c.dp(8), 0, 0, 0)
            it.maxWidth = Math.max(cw - c.dp(110), c.dp(60))
        }
        pill.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, c.dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
        val close = CloseButton(c)
        close.setOnClickListener { onClose(this) }
        header.addView(close, FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LayoutParams(cw, headerH))
        addView(thumb, LayoutParams(cw, ch))
        isClickable = true
        isFocusable = true
        // pointer over the card: outline it
        setOnHoverListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> thumb.setHover(true)
                MotionEvent.ACTION_HOVER_EXIT -> thumb.setHover(false)
            }
            false
        }
    }

    fun resize(w: Int, h: Int, hh: Int) {
        if (w == cw && h == ch && hh == headerH) return
        cw = w; ch = h; headerH = hh
        (header.layoutParams as LayoutParams).also { it.width = w; it.height = hh }
        (thumb.layoutParams as LayoutParams).also { it.width = w; it.height = h }
        title.maxWidth = Math.max(w - context.dp(110), context.dp(60))
        requestLayout()
    }
    fun setSelected2(on: Boolean) = thumb.setSel(on)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ev.buttonState and MotionEvent.BUTTON_TERTIARY != 0) { onClose(this); return true }   // middle click closes
                downX = ev.x; downY = ev.y; dragging = false; return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (!dragging && dy < -slop && Math.abs(dy) > Math.abs(ev.x - downX)) { dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                if (dragging) { translationY = Math.min(0f, dy); alpha = (1f + translationY / (ch * 1.2f)).coerceIn(0.2f, 1f) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (dragging) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (translationY < -ch * 0.25f || vy < -1400f)) {
                        animate().translationY(-height.toFloat()).alpha(0f).setDuration(140).withEndAction { onClose(this) }.start()
                    } else animate().translationY(0f).alpha(1f).setDuration(160).start()
                    dragging = false
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop) onOpen()
                return true
            }
        }
        return true
    }
}

/** The task's picture, filled to the card width and cut at the bottom (as the stock launcher draws it), with rounded corners. */
private class Thumb(c: Context, val task: RTask) : View(c) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val radius = c.dp(24).toFloat()
    private var hover = false
    private var sel = false
    private val icon: Drawable = task.icon.constantState?.newDrawable()?.mutate() ?: task.icon

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        clipToOutline = true
        Thread {
            val b = Recents.snapshot(task.id)
            if (b != null) post { bmp = b; invalidate() }
        }.start()
    }

    fun setHover(on: Boolean) { if (hover != on) { hover = on; invalidate() } }
    fun setSel(on: Boolean) { if (sel != on) { sel = on; invalidate() } }

    override fun onDraw(cv: Canvas) {
        val b = bmp
        if (b == null) {
            cv.drawColor(task.color)
            val s = Math.min(width, height) / 3
            icon.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            icon.draw(cv)
        } else {
            val sc = Math.max(width.toFloat() / b.width, height.toFloat() / b.height)
            cv.drawBitmap(b, null, RectF(0f, 0f, b.width * sc, b.height * sc), paint)
        }
        if (hover || sel) {
            val w = context.dp(4).toFloat()
            stroke.strokeWidth = w
            stroke.color = Colors.accent(context)
            cv.drawRoundRect(RectF(w / 2, w / 2, width - w / 2, height - w / 2), radius - w / 2, radius - w / 2, stroke)
        }
    }
}
