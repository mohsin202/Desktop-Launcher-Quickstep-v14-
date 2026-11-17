package com.android.desktoplauncher

import android.content.Context

/** Starts the taskbar (it lives in TouchInteractionService's process; no accessibility service is involved). */
object AutoStart {
    fun ensureTaskbarService(c: Context): Boolean = TaskbarCore.ensure(c) != null
}
