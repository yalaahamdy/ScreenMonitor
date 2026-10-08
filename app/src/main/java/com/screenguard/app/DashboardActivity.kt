package com.screenguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

import androidx.core.content.ContextCompat

/**
 * Parent command center: live monitoring status, protection shields,
 * library shortcuts and the immediate screen-lock action.
 */
class DashboardActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.rowAdmin).setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@DashboardActivity, AdminReceiver::class.java)
                )
            }
            startActivity(intent)
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<View>(R.id.rowGallery).setOnClickListener {
            startActivity(Intent(this, PinLockActivity::class.java))
        }
        findViewById<View>(R.id.rowSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnLockNow).setOnClickListener { confirmLockNow() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun adminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refresh() {
        val serviceUp = ScreenGuardAccessibilityService.isRunning() ||
                ScreenGuardAccessibilityService.isEnabledInSettings(this)
        val admin = adminActive()
        val battery = batteryExempt()

        // Header state chip
        val chip = findViewById<TextView>(R.id.tvHeaderState)
        if (serviceUp) {
            chip.text = getString(R.string.dash_state_active)
            chip.setBackgroundResource(R.drawable.bg_pill_done)
            chip.setTextColor(ContextCompat.getColor(this, R.color.success_text))
        } else {
            chip.text = getString(R.string.dash_state_inactive)
            chip.setBackgroundResource(R.drawable.bg_pill_danger)
            chip.setTextColor(ContextCompat.getColor(this, R.color.danger))
        }

        // Counters
        val count = ScreenshotStore.count(this)
        findViewById<TextView>(R.id.tvStatCount).text = count.toString()
        findViewById<TextView>(R.id.rowGallerySub).text =
            getString(R.string.row_gallery_sub, count)

        val last = Prefs.lastCapture(this)
        findViewById<TextView>(R.id.tvStatLast).text =
            if (last == 0L) getString(R.string.never) else relative(last)

        // Key card summary
        val active = listOf(serviceUp, admin, battery).count { it }
        findViewById<TextView>(R.id.tvKeySub).text =
            if (active == 3) getString(R.string.key_sub_all)
            else getString(R.string.key_sub_partial, active)

        // Protection badges
        setBadge(R.id.badgeAccessibility, serviceUp)
        setBadge(R.id.badgeAdmin, admin)
        setBadge(R.id.badgeBattery, battery)
    }

    private fun setBadge(id: Int, done: Boolean) {
        val badge = findViewById<TextView>(id)
        badge.text = getString(if (done) R.string.badge_done else R.string.badge_pending)
        badge.setBackgroundResource(
            if (done) R.drawable.bg_pill_done else R.drawable.bg_pill_pending
        )
        badge.setTextColor(
            if (done) ContextCompat.getColor(this, R.color.success_text)
            else themedColor(R.attr.sgAccentSoft)
        )
    }

    private fun relative(then: Long): String {
        val diff = System.currentTimeMillis() - then
        val min = diff / 60000L
        return when {
            min < 1 -> getString(R.string.just_now)
            min < 60 -> getString(R.string.minutes_ago, min)
            min < 1440 -> getString(R.string.hours_ago, min / 60)
            else -> getString(R.string.days_ago, min / 1440)
        }
    }

    private fun confirmLockNow() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_confirm_title)
            .setMessage(R.string.lock_confirm_text)
            .setPositiveButton(R.string.act_lock) { _, _ ->
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
                    dpm.lockNow()
                } else {
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(
                                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                ComponentName(this@DashboardActivity, AdminReceiver::class.java)
                            )
                        }
                    )
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }
}
