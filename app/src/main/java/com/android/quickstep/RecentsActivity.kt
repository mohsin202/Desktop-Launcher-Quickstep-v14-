package com.android.quickstep

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.TextView
import com.android.desktoplauncher.RTask
import com.android.desktoplauncher.Recents
import com.android.desktoplauncher.UiText
import com.android.desktoplauncher.dp

/**
 * The Overview (Recents) screen. The name and package are the ones the ROM's config_recentsComponentName points at
 * (com.android.launcher3/com.android.quickstep.RecentsActivity). It follows the stock Quickstep carousel: a picture of each
 * task with its icon and name above it, newest on the right, swipe a card up to remove it, "Clear all" at the end, tap
 * the empty area or press Back to leave.
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
    private val cards = ArrayList<TaskCard>()
    private var clearAll: TextView? = null
    private var built = false
    private var tasks: List<RTask> = emptyList()
    private var leaving = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        current = this
        val root = object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (w > 0 && h > 0) rebuild(w, h) }
        }
        root.setBackgroundColor(0xB3000000.toInt())
        scroll = Carousel(this) { leave() }
        row = LinearLayout(this).also {
            it.orientation = LinearLayout.HORIZONTAL
            it.gravity = Gravity.CENTER_VERTICAL
            it.layoutTransition = LayoutTransition().also { t -> t.disableTransitionType(LayoutTransition.CHANGE_APPEARING) }
        }
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).also {
            it.text = UiText.text(this, "No recent items")
            it.setTextColor(Color.WHITE); it.textSize = 20f; it.gravity = Gravity.CENTER; it.visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        tasks = Recents.load(this)
    }

    private fun rtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun rebuild(w: Int, h: Int) {
        if (built) return
        built = true
        if (tasks.isEmpty()) { empty.visibility = View.VISIBLE; return }
        val dm = resources.displayMetrics
        // The picture uses up to 70% of the height; header (icon + name) above it, same proportions as the screen
        val header = dp(44)
        val cardH = ((h - header - dp(24)) * 0.80f).toInt().coerceAtLeast(dp(120))
        val cardW = (cardH * dm.widthPixels.toFloat() / dm.heightPixels).toInt().coerceAtMost(w - dp(48)).coerceAtLeast(dp(120))
        val side = (w - cardW) / 2
        row.setPadding(side, 0, side, 0)
        val spacing = dp(PAGE_SPACING_DP)
        // newest first in [tasks]; shown newest on the right (left in right-to-left), "Clear all" on the far other end
        val order = if (rtl()) tasks else tasks.reversed()
        val btn = TextView(this).also {
            it.text = UiText.text(this, "Clear all")
            it.setTextColor(Color.WHITE); it.textSize = 16f; it.gravity = Gravity.CENTER
            it.background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x33FFFFFF) }
            it.setPadding(dp(22), dp(12), dp(22), dp(12))
            it.setOnClickListener { clearEverything() }
        }
        clearAll = btn
        if (!rtl()) row.addView(btn, lp(WRAP, WRAP, spacing, 0))
        for (t in order) {
            val c = TaskCard(this, t, cardW, cardH, header, { open(t) }, { dismiss(it) })
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
    override fun onStop() { super.onStop(); if (!leaving) { leaving = true; finish() } }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
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

/** A task: icon + name pill above, the task's picture below. Swipe up (or middle-click) removes it. */
private class TaskCard(c: Context, val task: RTask, val cw: Int, val ch: Int, val headerH: Int,
                       val onOpen: () -> Unit, val onClose: (TaskCard) -> Unit) : LinearLayout(c) {
    private val thumb = Thumb(c, task, cw, ch)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        val pill = LinearLayout(c).also {
            it.orientation = HORIZONTAL; it.gravity = Gravity.CENTER_VERTICAL
            it.background = GradientDrawable().apply { cornerRadius = c.dp(22).toFloat(); setColor(0x66000000) }
            it.setPadding(c.dp(6), c.dp(4), c.dp(14), c.dp(4))
        }
        val icon = ImageView(c).also { it.setImageDrawable(task.icon) }
        pill.addView(icon, LayoutParams(c.dp(32), c.dp(32)))
        val title = TextView(c).also {
            it.text = task.label; it.setTextColor(Color.WHITE); it.textSize = 14f
            it.maxLines = 1; it.ellipsize = TextUtils.TruncateAt.END; it.setPadding(c.dp(8), 0, 0, 0)
        }
        pill.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, headerH - c.dp(8)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = c.dp(8) })
        addView(thumb, LayoutParams(cw, ch))
        isClickable = true
        isFocusable = true
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
private class Thumb(c: Context, val task: RTask, val cw: Int, val ch: Int) : View(c) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val radius = c.dp(24).toFloat()

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

    override fun onDraw(cv: Canvas) {
        val b = bmp
        if (b == null) {
            cv.drawColor(task.color)
            val s = Math.min(width, height) / 3
            val d = task.icon
            d.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            d.draw(cv)
            return
        }
        val sc = Math.max(width.toFloat() / b.width, height.toFloat() / b.height)
        val dw = b.width * sc; val dh = b.height * sc
        cv.drawBitmap(b, null, android.graphics.RectF(0f, 0f, dw, dh), paint)
    }
}
