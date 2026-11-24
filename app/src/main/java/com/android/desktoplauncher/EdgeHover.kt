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
 * Notices the mouse pointer resting on the bottom edge of the screen (an input monitor, MONITOR_INPUT, sees the mouse
 * everywhere, also in full-screen apps where the strip window gets no hover). It only watches: events go on to the apps.
 */
class EdgeHover(private val c: Context, private val onEdge: () -> Unit) {
    companion object {
        @Volatile var status = "off"
        @Volatile var info = ""
    }
    private var seen = 0; private var fired = 0

    private var thread: HandlerThread? = null
    private var monitor: Any? = null
    private var receiver: InputEventReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private val fire = Runnable { fired++; info = "mouse events $seen, last y ${lastY.toInt()} of $lastH, taskbar asked $fired x"; onEdge() }
    @Volatile private var lastY = 0f
    @Volatile private var lastH = 0

    fun start() {
        if (receiver != null) return
        try {
            val im = c.getSystemService(InputManager::class.java)
            val m = im.javaClass.getMethod("monitorGestureInput", String::class.java, Int::class.javaPrimitiveType).invoke(im, "desktop-edge-hover", 0)
                ?: run { status = "no monitor"; return }
            val ch = m.javaClass.getMethod("getInputChannel").invoke(m) as InputChannel
            val t = HandlerThread("edgeHover").also { it.start() }
            thread = t; monitor = m
            receiver = Recv(ch, t.looper)
            status = "running"
        } catch (e: Throwable) {
            status = "failed: " + (e.cause ?: e).toString().take(120)
            Log.w("DesktopTaskbar", "edge hover: $e")
            stop()
        }
    }

    fun stop() {
        main.removeCallbacks(fire)
        try { receiver?.dispose() } catch (_: Throwable) {}
        receiver = null
        try { monitor?.let { it.javaClass.getMethod("dispose").invoke(it) } } catch (_: Throwable) {}
        monitor = null
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
        if (status == "running") status = "off"
    }

    private inner class Recv(ch: InputChannel, looper: Looper) : InputEventReceiver(ch, looper) {
        private var armed = false

        override fun onInputEvent(event: InputEvent) {
            try { if (event is MotionEvent) handle(event) } catch (_: Throwable) {}
            finally { finishInputEvent(event, false) }
        }

        private fun handle(e: MotionEvent) {
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return
            val h = c.resources.displayMetrics.heightPixels
            seen++; lastY = e.y; lastH = h
            if (seen % 40 == 1) info = "mouse events $seen, last y ${e.y.toInt()} of $h, taskbar asked $fired x"
            val atEdge = e.y >= h - 2
            if (atEdge && !armed) { armed = true; main.postDelayed(fire, 120) }
            else if (!atEdge && armed) { armed = false; main.removeCallbacks(fire) }
        }
    }
}
