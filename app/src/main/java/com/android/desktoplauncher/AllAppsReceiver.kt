package com.android.desktoplauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The target of the system's "all apps" action (what the Windows / Meta key triggers: PhoneWindowManager asks the accessibility
 * manager for GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, and the Quickstep launcher is the one that answers it).
 */
class AllAppsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val t = TaskbarService.instance
        if (t != null) t.toggleDrawer()
        else try { c.startActivity(Intent(c, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
