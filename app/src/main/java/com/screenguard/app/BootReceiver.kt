package com.screenguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After a reboot the system re-binds the enabled accessibility service on its
 * own, which immediately restores the foreground monitoring notification.
 * This receiver only resets app-open tracking so the first app opened after
 * boot is captured again.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Prefs.init(context)
            Prefs.setLastCapturedApp(context, null)
            Log.i("ScreenGuardBoot", "Boot completed — monitoring will resume automatically")
        }
    }
}
