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
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var scroll: Carousel
    private lateinit var row: LinearLayout
    private lateinit var empty: TextView
    private lateinit var memory: TextView
    private val cards = ArrayList<TaskCard>()
    private var clearAll: TextView? = null
    private var built = false
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
        scroll = Carousel(this) { leave() }
        row = LinearLayout(this).also {
            it.orientation = LinearLayout.HORIZONTAL
            it.gravity = Gravity.CENTER_VERTICAL
            it.layoutTransition = LayoutTransition().also { t -> t.disableTransitionType(LayoutTransition.CHANGE_APPEARING) }
        }
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            empty.visibility = View.VISIBLE; return
        }
        val showMem = Prefs.bool(this, Prefs.K_REC_MEM, false)
        if (showMem) memory.visibility = View.VISIBLE
        val dm = resources.displayMetrics
        // The picture uses up to 70% of the height; header (icon, name, close) above it, same proportions as the screen
        val header = dp(48)
        val barRoom = if (com.android.desktoplauncher.NavMode.gesture(this)) dp(56) else 0   // the floating taskbar
        val reserve = (if (showMem) dp(56) else dp(24)) + barRoom
        val cardH = ((h - header - reserve) * 0.80f).toInt().coerceAtLeast(dp(120))
        val cardW = (cardH * dm.widthPixels.toFloat() / dm.heightPixels).toInt().coerceAtMost(w - dp(48)).coerceAtLeast(dp(120))
        val side = (w - cardW) / 2
        row.setPadding(side, 0, side, (if (showMem) dp(28) else 0) + barRoom)
        val spacing = dp(PAGE_SPACING_DP)
        // newest first in [tasks]; shown newest on the right (left in right-to-left), "Clear all" on the far other end
        val order = if (rtl()) tasks else tasks.reversed()
        val btn = TextView(this).also {
            it.text = UiText.text(this, "Clear all")
            it.setTextColor(Colors.text(this)); it.textSize = 16f; it.gravity = Gravity.CENTER
            it.background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Colors.surfaceHigh(this@RecentsActivity)) }
            it.setPadding(dp(22), dp(12), dp(22), dp(12))
            it.setOnClickListener { clearEverything() }
        }
        clearAll = btn
        if (!rtl()) row.addView(btn, lp(WRAP, WRAP, spacing, 0))
        for (t in order) {
            val c = TaskCard(this, t, cardW, cardH, header, { open(t) }, { dismiss(it) }, { v -> showMenu(v, t) })
            cards.add(c)
            row.addView(c, lp(cardW, cardH + header, spacing / 2, 0))
        }
        if (rtl()) row.addView(btn, lp(WRAP, WRAP, spacing, 0))
        scroll.post {
            // start on the newest card (the app that was open): right end for left-to-right, left end for right-to-left
            if (rtl()) scroll.scrollTo(0, 0) else scroll.scrollTo(row.width, 0)
            scroll.cardW = cardW + spacing
            scroll.snapNow()
        }
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
        row.removeView(c); cards.remove(c)
        if (cards.isEmpty()) main.postDelayed({ leave() }, 180)
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
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { scroll.step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { cards.minByOrNull { scroll.distanceToCenter(it) }?.let { open(it.task) }; return true }
        }
        return super.onKeyDown(code, e)
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

/** The row of cards: scrolls sideways, settles on the nearest card, a tap on empty space leaves. */
private class Carousel(c: Context, val onEmptyTap: () -> Unit) : HorizontalScrollView(c) {
    var cardW = 0
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var down = false
    private val snap = Runnable { snapNow() }

    init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFillViewport = true }

    private fun centers(): List<Pair<View, Int>> {
        val row = getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until row.childCount).map { row.getChildAt(it) }.filter { it is TaskCard }.map { it to (it.left + it.width / 2) }
    }
    fun distanceToCenter(v: View): Int = Math.abs((v.left + v.width / 2) - (scrollX + width / 2))

    fun snapNow() {
        val mid = scrollX + width / 2
        val t = centers().minByOrNull { Math.abs(it.second - mid) } ?: return
        smoothScrollTo(t.second - width / 2, 0)
    }
    fun step(dir: Int) {
        val c = centers().sortedBy { it.second }
        if (c.isEmpty()) return
        val mid = scrollX + width / 2
        val cur = c.indices.minByOrNull { Math.abs(c[it].second - mid) } ?: 0
        smoothScrollTo(c[(cur + dir).coerceIn(0, c.size - 1)].second - width / 2, 0)
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        removeCallbacks(snap)
        if (!down) postDelayed(snap, 110)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; downX = ev.x; downY = ev.y; removeCallbacks(snap) }
            MotionEvent.ACTION_UP -> {
                down = false
                if (Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop) onEmptyTap()
                else postDelayed(snap, 110)
            }
            MotionEvent.ACTION_CANCEL -> { down = false; postDelayed(snap, 110) }
        }
        return super.onTouchEvent(ev)
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { down = true; removeCallbacks(snap) }
        return super.onInterceptTouchEvent(ev)
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
private class TaskCard(c: Context, val task: RTask, val cw: Int, val ch: Int, val headerH: Int,
                       val onOpen: () -> Unit, val onClose: (TaskCard) -> Unit, val onMenu: (View) -> Unit) : LinearLayout(c) {
    private val thumb = Thumb(c, task)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        val header = FrameLayout(c)
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
        val title = TextView(c).also {
            it.text = task.label; it.setTextColor(Colors.text(c)); it.textSize = 14f
            it.maxLines = 1; it.ellipsize = TextUtils.TruncateAt.END; it.setPadding(c.dp(8), 0, 0, 0)
            it.maxWidth = cw - c.dp(110)
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
        if (hover) {
            val w = context.dp(4).toFloat()
            stroke.strokeWidth = w
            stroke.color = Colors.accent(context)
            cv.drawRoundRect(RectF(w / 2, w / 2, width - w / 2, height - w / 2), radius - w / 2, radius - w / 2, stroke)
        }
    }
}
