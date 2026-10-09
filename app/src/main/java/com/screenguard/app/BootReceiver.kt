package com.screenguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.screenguard.app.security.BootResilienceManager
import com.screenguard.app.security.SafeModeManager

/**
 * Merged boot receiver:
 *  - ScreenGuard: re-initializes prefs and resets app-open tracking so the
 *    first app opened after boot is captured.
 *  - Restrictions engine: runs the safe-mode boot audit and restores the
 *    enforcement service / active bypass timers once the device is unlocked.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val bootActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (!bootActions.contains(action)) return

        Prefs.init(context)
        Prefs.setLastCapturedApp(context, null)
        Log.i("ScreenGuardBoot", "Boot event ($action) — monitoring will resume automatically")

        // Direct-Boot-aware: wait for user unlock before file-backed work
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val isUserUnlocked = userManager?.isUserUnlocked ?: true
        if (!isUserUnlocked) return

        try {
            SafeModeManager.performBootAudit(context)
        } catch (e: Exception) {
            Log.w("ScreenGuardBoot", "Boot audit failed", e)
        }

        try {
            BootResilienceManager.restoreServicesOnBoot(context)
        } catch (e: Exception) {
            Log.w("ScreenGuardBoot", "Service restore failed", e)
        }
    }
}
