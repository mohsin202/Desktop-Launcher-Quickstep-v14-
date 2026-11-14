package com.android.desktoplauncher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.util.Log

/** One entry of the Recents list. [id] is the task id (persistent id) the system uses. */
class RTask(val id: Int, val pkg: String, val label: String, val icon: Drawable, val color: Int, val intent: Intent?)

/**
 * The system's Recents list and what the Overview screen does with it: the same calls the stock Quickstep launcher makes
 * (ActivityManager.getRecentTasks, getTaskSnapshot, startActivityFromRecents, removeTask). The hidden ones are reached by
 * reflection and need the privileged permissions REAL_GET_TASKS, READ_FRAME_BUFFER, START_TASKS_FROM_RECENTS and REMOVE_TASKS.
 */
object Recents {
    private const val TAG = "DesktopQuickstep"

    @Suppress("DEPRECATION")
    fun load(c: Context): List<RTask> {
        val out = ArrayList<RTask>()
        try {
            val am = c.getSystemService(ActivityManager::class.java) ?: return out
            val pm = c.packageManager
            val seen = HashSet<Int>()
            for (ti in am.getRecentTasks(40, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                val id = ti.persistentId
                if (id < 0 || !seen.add(id)) continue
                val intent = ti.baseIntent ?: continue
                if (intent.hasCategory(Intent.CATEGORY_HOME)) continue
                if ((intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0) continue
                val comp: ComponentName = intent.component ?: ti.origActivity ?: continue
                if (comp.packageName == c.packageName && comp.className.endsWith("RecentsActivity")) continue
                val td = ti.taskDescription
                val label = try { pm.getActivityInfo(comp, 0).loadLabel(pm).toString() } catch (_: Throwable) { td?.label ?: comp.packageName }
                val icon = try { Icons.app(c, comp) } catch (_: Throwable) { pm.defaultActivityIcon }
                var col = try { td?.primaryColor ?: 0 } catch (_: Throwable) { 0 }
                if (col == 0) col = Color.rgb(48, 52, 58)
                out.add(RTask(id, comp.packageName, label, icon, col or 0xFF000000.toInt(), intent))
            }
        } catch (t: Throwable) { Log.w(TAG, "recent tasks: $t") }
        return out
    }

    private fun atms(): Any? = try {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    } catch (_: Throwable) { null }

    /** The task's last picture, or null (no permission / nothing stored / the app hides its content). Call off the main thread. */
    fun snapshot(id: Int): Bitmap? = try {
        val s = atms() ?: return null
        val snap = s.javaClass.getMethod("getTaskSnapshot", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(s, id, false) ?: return null
        val hb = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer ?: return null
        val cs = snap.javaClass.getMethod("getColorSpace").invoke(snap) as? ColorSpace
        val hw = Bitmap.wrapHardwareBuffer(hb, cs) ?: return null
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        if (soft != null && soft.width > 900) Bitmap.createScaledBitmap(soft, 900, (soft.height * 900f / soft.width).toInt().coerceAtLeast(1), true).also { if (it !== soft) soft.recycle() } else soft
    } catch (t: Throwable) { Log.w(TAG, "snapshot $id: $t"); null }

    /** Brings the task back (what tapping a card in the stock Overview does). */
    fun open(c: Context, t: RTask) {
        try {
            val s = atms()
            if (s != null) {
                val r = s.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java).invoke(s, t.id, null as Bundle?) as? Int ?: 0
                if (r >= 0) return
            }
        } catch (th: Throwable) { Log.w(TAG, "startActivityFromRecents: $th") }
        try {
            c.getSystemService(ActivityManager::class.java)?.moveTaskToFront(t.id, 0); return
        } catch (th: Throwable) { Log.w(TAG, "moveTaskToFront: $th") }
        try { c.startActivity(Intent(t.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }

    fun remove(id: Int) = SysTasks.remove(id)
}
