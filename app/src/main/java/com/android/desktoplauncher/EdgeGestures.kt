package com.android.desktoplauncher

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent

/**
 * Back gesture from the left / right screen edge, like the stock launcher: put a finger (or press the mouse button with the
 * pointer against the edge of the screen) and drag inwards. Reads every touch / mouse event through an input monitor
 * (MONITOR_INPUT) and, when the gesture is recognised, takes the pointer away from the app and sends Back.
 */
class EdgeGestures(private val c: Context, private val onBack: () -> Unit) {
    companion object {
        @Volatile var status = "off"
    }

    private var thread: HandlerThread? = null
    private var monitor: Any? = null
    private var receiver: InputEventReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    val running get() = receiver != null

    fun start() {
        if (receiver != null) return
        try {
            val im = c.getSystemService(InputManager::class.java)
            val m = im.javaClass.getMethod("monitorGestureInput", String::class.java, Int::class.javaPrimitiveType).invoke(im, "desktop-edge-back", 0)
                ?: run { status = "no monitor"; return }
            val ch = m.javaClass.getMethod("getInputChannel").invoke(m) as InputChannel
            val t = HandlerThread("edgeGestures").also { it.start() }
            thread = t; monitor = m
            receiver = Recv(ch, t.looper)
            status = "running"
        } catch (e: Throwable) {
            status = "failed: " + (e.cause ?: e).toString().take(120)
            Log.w("DesktopTaskbar", "edge gestures: $e")
            stop()
        }
    }

    fun stop() {
        try { receiver?.dispose() } catch (_: Throwable) {}
        receiver = null
        try { monitor?.let { it.javaClass.getMethod("dispose").invoke(it) } } catch (_: Throwable) {}
        monitor = null
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
        if (status == "running") status = "off"
    }

    private inner class Recv(ch: InputChannel, looper: Looper) : InputEventReceiver(ch, looper) {
        private var side = 0          // 0 none, -1 left edge, 1 right edge
        private var startX = 0f
        private var startY = 0f

        override fun onInputEvent(event: InputEvent) {
            try { if (event is MotionEvent) handle(event) } catch (_: Throwable) {}
            finally { finishInputEvent(event, false) }
        }

        private fun handle(e: MotionEvent) {
            val dm = c.resources.displayMetrics
            val d = dm.density
            val w = dm.widthPixels; val h = dm.heightPixels
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    side = 0
                    val mouse = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                    val edge = if (mouse) 3f else 20f * d     // a mouse pointer stops at the very edge pixel
                    val x = e.x; val y = e.y
                    if (y > 24 * d && y < h - 72 * d) {
                        if (x <= edge) side = -1 else if (x >= w - edge) side = 1
                    }
                    startX = x; startY = y
                }
                MotionEvent.ACTION_MOVE -> if (side != 0) {
                    if (e.pointerCount > 1) { side = 0; return }
                    val dx = if (side < 0) e.x - startX else startX - e.x
                    val dy = Math.abs(e.y - startY)
                    if (dx >= 56 * d && dy <= dx * 1.5f) {
                        side = 0
                        try { monitor?.let { it.javaClass.getMethod("pilferPointers").invoke(it) } } catch (_: Throwable) {}
                        main.post(onBack)
                    } else if (dy > 90 * d) side = 0
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> side = 0
            }
        }
    }
}
