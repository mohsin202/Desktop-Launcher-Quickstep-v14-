package com.android.desktoplauncher

import android.app.Service
import android.content.Intent
import android.graphics.Region
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.android.systemui.shared.recents.IOverviewProxy
import com.android.systemui.shared.recents.ISystemUiProxy

/** Bits of the state number SystemUI sends (QuickStepContract.SYSUI_STATE_*, Android 14). */
object SysUiState {
    const val SCREEN_PINNING = 1 shl 0
    const val NAV_BAR_HIDDEN = 1 shl 1
    const val NOTIFICATION_PANEL_EXPANDED = 1 shl 2
    const val BOUNCER_SHOWING = 1 shl 3
    const val KEYGUARD_SHOWING = 1 shl 6
    const val OVERVIEW_DISABLED = 1 shl 7
    const val HOME_DISABLED = 1 shl 8
    const val SEARCH_DISABLED = 1 shl 10
    const val QUICK_SETTINGS_EXPANDED = 1 shl 11
    const val DIALOG_SHOWING = 1 shl 15
    const val IME_SHOWING = 1 shl 18
    const val IME_SWITCHER_SHOWING = 1 shl 20
    const val DEVICE_DOZING = 1 shl 21
    const val BACK_DISABLED = 1 shl 22
}

/**
 * The connection to SystemUI. TouchInteractionService fills it in when SystemUI binds; the taskbar buttons use it
 * (Back goes through SystemUI so it behaves exactly like the stock navigation bar). Every call is safe when SystemUI
 * is not connected (it just does nothing and returns false).
 */
object SysUi {
    private const val TAG = "DesktopQuickstep"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var proxy: ISystemUiProxy? = null; private set
    @Volatile var state = 0; private set
    @Volatile var navRegion: Region? = null; private set
    @Volatile var darkIntensity = 0f; private set
    @Volatile var assistantAvailable = false; private set

    /** Called on the main thread whenever something above changed. */
    val listeners = ArrayList<() -> Unit>()

    val connected get() = proxy != null
    fun has(flag: Int) = (state and flag) != 0

    internal fun set(p: ISystemUiProxy?) { proxy = p; changed() }
    internal fun setState(s: Int) { if (s != state) { state = s; changed() } }
    internal fun setRegion(r: Region?) { navRegion = r; changed() }
    internal fun setDark(f: Float) { darkIntensity = f; changed() }
    internal fun setAssistant(a: Boolean) { assistantAvailable = a }
    private fun changed() { main.post { for (l in ArrayList(listeners)) try { l() } catch (e: Exception) { Log.e(TAG, "listener", e) } } }

    private inline fun call(name: String, f: (ISystemUiProxy) -> Unit): Boolean {
        val p = proxy ?: return false
        return try { f(p); true } catch (e: Exception) { Log.e(TAG, "$name failed", e); false }
    }

    fun back() = call("onBackPressed") { it.onBackPressed() }
    fun expandNotifications() = call("expandNotificationPanel") { it.expandNotificationPanel() }
    fun toggleNotifications() = call("toggleNotificationPanel") { it.toggleNotificationPanel() }
    fun startScreenPinning(taskId: Int) = call("startScreenPinning") { it.startScreenPinning(taskId) }
    fun stopScreenPinning() = call("stopScreenPinning") { it.stopScreenPinning() }
    fun imeSwitcherPressed() = call("onImeSwitcherPressed") { it.onImeSwitcherPressed() }
    fun accessibilityButton(display: Int) = call("notifyAccessibilityButtonClicked") { it.notifyAccessibilityButtonClicked(display) }
    fun accessibilityButtonLong() = call("notifyAccessibilityButtonLongClicked") { it.notifyAccessibilityButtonLongClicked() }
    fun setHomeRotationEnabled(on: Boolean) = call("setHomeRotationEnabled") { it.setHomeRotationEnabled(on) }
    fun prioritizedRotation(rotation: Int) = call("notifyPrioritizedRotation") { it.notifyPrioritizedRotation(rotation) }
    fun taskbarEnabled(on: Boolean) = call("setTaskbarEnabled") { it.setTaskbarEnabled(on) }
    fun taskbarStatus(visible: Boolean, stashed: Boolean) = call("notifyTaskbarStatus") { it.notifyTaskbarStatus(visible, stashed) }
    fun taskbarAutohideSuspend(suspend: Boolean) = call("notifyTaskbarAutohideSuspend") { it.notifyTaskbarAutohideSuspend(suspend) }
    fun key(code: Int) = call("onKeyEvent") { it.onKeyEvent(code) }
}

/**
 * The Quickstep service. SystemUI finds it through the QUICKSTEP_SERVICE action (the package is the one named in the
 * ROM's config_recentsComponentName) and binds to it with the STATUS_BAR_SERVICE permission. In onInitialize it gives
 * us its ISystemUiProxy.
 */
class TouchInteractionService : Service() {
    private val binder = object : IOverviewProxy.Stub() {
        override fun onInitialize(params: Bundle?) {
            val b = params?.getBinder(KEY_SYSUI_PROXY)
            SysUi.set(if (b != null) ISystemUiProxy.Stub.asInterface(b) else null)
            Log.i(TAG, "SystemUI connected, proxy=${b != null}")
        }
        override fun onActiveNavBarRegionChanges(activeRegion: Region?) { SysUi.setRegion(activeRegion) }
        override fun onSystemUiStateChanged(stateFlags: Int) { SysUi.setState(stateFlags) }
        override fun onNavButtonsDarkIntensityChanged(darkIntensity: Float) { SysUi.setDark(darkIntensity) }
        override fun onAssistantAvailable(available: Boolean, longPressHomeEnabled: Boolean) { SysUi.setAssistant(available) }
        // Not used yet (they belong to Overview / gestures / assistant; the next phases). Kept so SystemUI gets an answer.
        override fun onOverviewToggle() { OverviewHooks.toggle?.invoke() }
        override fun onOverviewShown(triggeredFromAltTab: Boolean) { OverviewHooks.shown?.invoke(triggeredFromAltTab) }
        override fun onOverviewHidden(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) { OverviewHooks.hidden?.invoke(triggeredFromAltTab) }
        override fun onAssistantVisibilityChanged(visibility: Float) {}
        override fun onAssistantOverrideInvoked(invocationType: Int) {}
        override fun onRotationProposal(rotation: Int, isValid: Boolean) {}
        override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {}
        override fun onSystemBarAttributesChanged(displayId: Int, behavior: Int) {}
        override fun onNavigationBarLumaSamplingEnabled(displayId: Int, enable: Boolean) {}
        override fun enterStageSplitFromRunningApp(leftOrTop: Boolean) {}
        override fun onTaskbarToggled() { OverviewHooks.taskbarToggled?.invoke() }
    }

    override fun onCreate() {
        super.onCreate()
        // SystemUI binds this service at boot: a good moment to switch the taskbar on, without anyone opening Settings
        AutoStart.ensureTaskbarService(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        SysUi.set(null)
        return false
    }

    companion object {
        private const val TAG = "DesktopQuickstep"
        /** QuickStepContract.KEY_EXTRA_SYSUI_PROXY */
        private const val KEY_SYSUI_PROXY = "extra_sysui_proxy"
    }
}

/** Callbacks the Overview screen (phase 5) will hook into. Empty for now. */
object OverviewHooks {
    var toggle: (() -> Unit)? = null
    var shown: ((Boolean) -> Unit)? = null
    var hidden: ((Boolean) -> Unit)? = null
    var taskbarToggled: (() -> Unit)? = null
}
