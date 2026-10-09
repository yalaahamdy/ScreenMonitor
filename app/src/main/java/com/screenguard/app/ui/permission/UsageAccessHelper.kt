package com.screenguard.app.ui.permission

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.fragment.app.Fragment

/**
 * Helper for the PACKAGE_USAGE_STATS app-ops permission required by the
 * analytics and restrictions engines. The permission cannot be granted
 * programmatically; the user must enable it from the dedicated system screen.
 */
object UsageAccessHelper {

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Open the pkg-scoped usage-access screen; falls back to the generic screen. */
    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Final fallback
        }
    }

    /** Launch the settings screen and let [Fragment.onResume] re-check the state. */
    fun promptUsageAccess(fragment: Fragment) {
        openUsageAccessSettings(fragment.requireContext())
    }
}
