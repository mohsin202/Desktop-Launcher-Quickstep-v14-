package com.android.desktoplauncher

import android.content.Context
import android.provider.Settings

/** The system's navigation mode (Settings > System > Gestures > System navigation). */
object NavMode {
    const val THREE_BUTTON = 0
    const val GESTURE = 2

    /** Setting that changes when the user switches the mode. */
    const val KEY = "navigation_mode"

    fun current(c: Context): Int = try { Settings.Secure.getInt(c.contentResolver, KEY, THREE_BUTTON) } catch (_: Throwable) { THREE_BUTTON }

    /** Gesture navigation: the bar stays, but without Back / Home / Recents buttons; they are swipes on the bar and at the screen edges. */
    fun gesture(c: Context): Boolean = current(c) == GESTURE
}
