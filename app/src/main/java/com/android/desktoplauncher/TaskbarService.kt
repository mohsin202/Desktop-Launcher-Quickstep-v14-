package com.android.desktoplauncher

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper. The taskbar itself is [TaskbarCore], started by TouchInteractionService. When this accessibility service
 * is switched on it passes window/IME events and key events to it and lets it take screenshots (bar colour) and perform
 * global actions (split screen, screenshot, lock, power menu). Everything else works without it.
 */
class TaskbarService : AccessibilityService() {
    companion object {
        @Volatile var instance: TaskbarService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TaskbarCore.ensure(this)?.a11yChanged()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) { TaskbarCore.instance?.onAccessibilityEvent(e) }
    override fun onKeyEvent(e: KeyEvent): Boolean = TaskbarCore.instance?.onKeyEvent(e) ?: false
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        TaskbarCore.instance?.a11yChanged()
        super.onDestroy()
    }
}
