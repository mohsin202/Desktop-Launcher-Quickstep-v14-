# Desktop Launcher Quickstep

Desktop Launcher as the Quickstep launcher of BassOS / LineageOS 21 (Android 14). Package `com.android.launcher3`
(replaces TrebuchetQuickStep), minSdk/targetSdk 34, priv-app, no system UID.

Done so far
- Phase 1: new project; screenshot-based taskbar colour removed.
- Phase 2: `TouchInteractionService` (QUICKSTEP_SERVICE) + AIDL (`IOverviewProxy`, `ISystemUiProxy`, from LineageOS 21).
  `SysUi` holds the connection (`SysUi.back()`, system state, nav-bar region ...).

- Phase 3: the taskbar is a TYPE_NAVIGATION_BAR_PANEL window that provides nav-bar insets (56dp default), so apps sit above it.
  If the system refuses the window (missing signature permissions) it falls back to the old accessibility overlay.
  Taskbar + Back/Home/Recents are always on, at every width. No dock mode. Below 600dp the bar stays on the device's own
  bottom edge when rotated (side bar in landscape) with upright icons. Back goes through SystemUI (`SysUi.back()`).

Still to do: phase 4 (gestures, MONITOR_INPUT), phase 5 (Overview / RecentsActivity).

Install: put the APK in /system_ext/priv-app/<name>/ in place of TrebuchetQuickStep and replace
/system_ext/etc/permissions/com.android.launcher3.xml with rom/com.android.launcher3.xml.
