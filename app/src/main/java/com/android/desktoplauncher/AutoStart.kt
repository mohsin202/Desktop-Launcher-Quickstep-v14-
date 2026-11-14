package com.android.desktoplauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * As the system launcher (Quickstep) the app holds WRITE_SECURE_SETTINGS, so it can switch its own taskbar service on in
 * Settings > Accessibility itself. It is called when the process starts, when SystemUI binds TouchInteractionService (at boot)
 * and when the home screen opens. Does nothing without the permission or when the user turned "start automatically" off.
 */
object AutoStart {
    fun ensureTaskbarService(c: Context): Boolean {
        if (TaskbarService.instance != null) return true
        if (!Prefs.bool(c, Prefs.K_AUTO_A11Y, true)) return false
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val me = ComponentName(c, TaskbarService::class.java).flattenToString()
            val cr = c.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = cur.split(':').filter { it.isNotBlank() }
            if (list.none { it.equals(me, true) || it.equals(ComponentName.unflattenFromString(me)?.flattenToShortString(), true) }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("DesktopQuickstep", "taskbar service switched on")
            true
        } catch (t: Throwable) {
            Log.w("DesktopQuickstep", "could not switch the taskbar service on: $t")
            false
        }
    }
}
