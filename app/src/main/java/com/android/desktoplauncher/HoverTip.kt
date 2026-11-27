package com.android.desktoplauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Name label that appears when the mouse rests on an icon. (A view with View.tooltipText takes over the touches of its
 * parent list - the app drawer grid then stops opening apps - so the label is shown by hand.)
 * [above] = true: the label sits just above the view (taskbar buttons: 2 dp above the bar), false: just below it.
 */
object HoverTip {
    private val handler = Handler(Looper.getMainLooper())
    private var pw: PopupWindow? = null
    private var show: Runnable? = null

    private var current: View? = null

    fun hide() {
        show?.let { handler.removeCallbacks(it) }
        show = null
        try { pw?.dismiss() } catch (_: Throwable) { }
        pw = null
    }

    fun attach(v: View, text: String, above: Boolean = true) {
        v.setOnHoverListener { x, ev -> hover(x, ev, text, above); false }
    }

    /**
     * Called for every hover event of the icon. The label is (re)scheduled whenever the mouse is on an icon that has no
     * label yet - on enter and also on move - so it still appears when the bar was rebuilt under a resting mouse or when
     * the exit of the previous icon arrived late.
     */
    fun hover(v: View, ev: MotionEvent, text: String, above: Boolean) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (current === v && (show != null || pw != null)) return
                if (current !== v) hide()
                current = v
                val r = Runnable { display(v, text, above) }
                show = r
                handler.postDelayed(r, 400)
            }
            MotionEvent.ACTION_HOVER_EXIT -> if (current === v) { current = null; hide() }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> hide()
        }
    }

    private fun display(v: View, text: String, above: Boolean) {
        show = null
        if (text.isEmpty() || !v.isAttachedToWindow) return
        val c: Context = v.context
        val tv = TextView(c)
        tv.text = text
        tv.textSize = 13f
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.maxLines = 2
        tv.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
        tv.background = Colors.rounded(0xF2303030.toInt(), c.dp(8).toFloat())
        val dm = c.resources.displayMetrics
        tv.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels - c.dp(24), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val w = tv.measuredWidth
        val h = tv.measuredHeight
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val inWin = IntArray(2)
        v.getLocationInWindow(inWin)
        var x = loc[0] + v.width / 2 - w / 2
        x = x.coerceIn(c.dp(4), maxOf(c.dp(4), dm.widthPixels - w - c.dp(4)))
        val y = if (above) loc[1] - c.dp(2) - h else loc[1] + v.height + c.dp(2)
        try {
            val p = PopupWindow(tv, w, h, false)
            p.isTouchable = false
            p.isFocusable = false
            p.isClippingEnabled = false   // it sits above the bar, outside the bar's own window
            // A popup is positioned relative to its parent window (the taskbar window), so convert from screen coordinates.
            p.showAtLocation(v, Gravity.NO_GRAVITY, x - (loc[0] - inWin[0]), y.coerceAtLeast(0) - (loc[1] - inWin[1]))
            pw = p
            handler.postDelayed({ if (pw === p) { hide() } }, 4000)
        } catch (_: Throwable) { }
    }
}
