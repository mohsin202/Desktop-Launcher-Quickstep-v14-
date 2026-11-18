package com.android.desktoplauncher

import android.graphics.Insets
import android.os.Binder
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager

/**
 * What makes the taskbar a real navigation bar instead of an overlay: a window of type TYPE_NAVIGATION_BAR_PANEL that
 * *provides* the navigation-bar / tappable-element / mandatory-gesture insets (the same thing the Quickstep taskbar
 * does in TaskbarInsetsController). Apps then lay out above it, so nothing hides behind the bar.
 *
 * These are hidden platform APIs (not in the public SDK), so they are reached by reflection, and the window type /
 * providedInsets need signature permissions (INTERNAL_SYSTEM_WINDOW, STATUS_BAR_SERVICE). Every call is wrapped: when the
 * system refuses, the caller falls back to a plain overlay and the bar still works, only without reserving space.
 */
object NavWindow {
    private const val TAG = "DesktopQuickstep"
    private const val TYPE_INPUT_METHOD = 2011
    private const val TYPE_VOICE_INTERACTION = 2031
    private const val FLAG_SLIPPERY = 0x20000000
    private const val FLAG_SPLIT_TOUCH = 0x00800000
    private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040
    private val owner = Binder()

    /** True once a nav-bar window was refused, so we stop trying until the service restarts. */
    @Volatile var refused = false

    /** WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL */
    const val TYPE = 2024

    /** Sets the flags the stock taskbar window uses. */
    fun prepare(lp: WindowManager.LayoutParams) {
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or FLAG_SLIPPERY or FLAG_SPLIT_TOUCH
        try {
            val lpc = WindowManager.LayoutParams::class.java
            lpc.getField("receiveInsetsIgnoringZOrder").setBoolean(lp, true)
            val pf = lpc.getField("privateFlags")
            pf.setInt(lp, pf.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        } catch (t: Throwable) { Log.w(TAG, "nav window flags: $t") }
    }

    /**
     * @param side 0 = bottom, 1 = left, 2 = right edge
     * @param thickPx height (bottom) or width (side) of the bar
     * @param imePx space the bar keeps for the keyboard (taskbar_ime_size = 48dp in the stock launcher)
     */
    fun provideInsets(lp: WindowManager.LayoutParams, side: Int, thickPx: Int, imePx: Int): Boolean = try {
        val size = when (side) { 1 -> Insets.of(thickPx, 0, 0, 0); 2 -> Insets.of(0, 0, thickPx, 0); else -> Insets.of(0, 0, 0, thickPx) }
        val ime = when (side) { 1 -> Insets.of(imePx, 0, 0, 0); 2 -> Insets.of(0, 0, imePx, 0); else -> Insets.of(0, 0, 0, imePx) }
        val pc = Class.forName("android.view.InsetsFrameProvider")
        val oc = Class.forName("android.view.InsetsFrameProvider\$InsetsSizeOverride")
        val ctor = pc.getConstructor(Any::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val octor = oc.getConstructor(Int::class.javaPrimitiveType, Insets::class.java)
        fun provider(type: Int): Any {
            val p = ctor.newInstance(owner, 0, type)
            pc.getMethod("setInsetsSize", Insets::class.java).invoke(p, size)
            if (type == WindowInsets.Type.navigationBars()) {
                // no translucent contrast scrim behind the bar (the system's own navigation bar does the same when it needs none):
                // InsetsSource.FLAG_SUPPRESS_SCRIM = 1, so the apps / wallpaper show clearly through the transparent part of the band
                try { pc.getMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(p, 1, 1) }
                catch (t: Throwable) { Log.w(TAG, "suppress scrim: $t") }
            }
            val arr = java.lang.reflect.Array.newInstance(oc, 2)
            java.lang.reflect.Array.set(arr, 0, octor.newInstance(TYPE_INPUT_METHOD, ime))
            java.lang.reflect.Array.set(arr, 1, octor.newInstance(TYPE_VOICE_INTERACTION, size))
            pc.getMethod("setInsetsSizeOverrides", arr.javaClass).invoke(p, arr)
            return p
        }
        val all = java.lang.reflect.Array.newInstance(pc, 3)
        java.lang.reflect.Array.set(all, 0, provider(WindowInsets.Type.navigationBars()))
        java.lang.reflect.Array.set(all, 1, provider(WindowInsets.Type.tappableElement()))
        java.lang.reflect.Array.set(all, 2, provider(WindowInsets.Type.mandatorySystemGestures()))
        WindowManager.LayoutParams::class.java.getField("providedInsets").set(lp, all)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "providedInsets not available: $t")
        false
    }
}
