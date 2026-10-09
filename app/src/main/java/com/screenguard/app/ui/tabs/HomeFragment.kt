package com.screenguard.app.ui.tabs

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.screenguard.app.AdminReceiver
import com.screenguard.app.BuildConfig
import com.screenguard.app.CrashLogger
import com.screenguard.app.GalleryActivity
import com.screenguard.app.Prefs
import com.screenguard.app.R
import com.screenguard.app.ScreenshotStore
import com.screenguard.app.ScreenGuardAccessibilityService
import com.screenguard.app.SettingsActivity
import com.screenguard.app.UiSafety
import java.util.Locale

/**
 * Home tab: live monitoring status, protection shields, library shortcuts
 * and the immediate screen-lock action. Ported from DashboardActivity.
 */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        inflater.inflate(R.layout.fragment_home, container, false)
    } catch (t: Throwable) {
        // v3.1.2: never return null — a built error card instead of a blank tab
        CrashLogger.log(t, "HomeFragment.inflate")
        UiSafety.errorCard(requireContext(), "HomeFragment.inflate", t)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiSafety.guard("HomeFragment.onViewCreated") { onViewCreatedSafe(view) }
    }

    private fun onViewCreatedSafe(view: View) {
        if (UiSafety.isErrorCard(view)) return
        view.findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.findViewById<View>(R.id.rowAdmin).setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(requireContext(), AdminReceiver::class.java)
                )
            }
            startActivity(intent)
        }
        view.findViewById<View>(R.id.rowBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        view.findViewById<View>(R.id.rowGallery).setOnClickListener {
            startActivity(Intent(requireContext(), GalleryActivity::class.java))
        }
        view.findViewById<View>(R.id.rowSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnLockNow).setOnClickListener { confirmLockNow() }

        // v3.1.1: crash report banner + visible version stamp
        UiSafety.guard("HomeFragment.crashBanner") {
            view.findViewById<TextView>(R.id.tvHomeVersion).text =
                "v" + BuildConfig.VERSION_NAME
            val banner = view.findViewById<View>(R.id.crashBanner)
            val hasLogs = CrashLogger.hasLogs(requireContext())
            banner.visibility = if (hasLogs) View.VISIBLE else View.GONE
            if (hasLogs) {
                banner.setOnClickListener { showCrashReportDialog() }
            }
        }
    }

    private fun showCrashReportDialog() {
        val ctx = requireContext()
        val content = CrashLogger.readLogs(ctx)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.crash_banner_title)
            .setMessage(content.ifEmpty { ctx.getString(R.string.crash_log_empty) })
            .setPositiveButton(R.string.crash_log_copy) { _, _ ->
                UiSafety.copyToClipboard(ctx, content)
                android.widget.Toast.makeText(ctx, R.string.crash_log_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.crash_log_clear) { _, _ ->
                CrashLogger.clear(ctx)
                view?.findViewById<View>(R.id.crashBanner)?.visibility = View.GONE
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        UiSafety.guard("HomeFragment.onResume") { refresh() }
    }

    private fun adminActive(): Boolean {
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(requireContext(), AdminReceiver::class.java))
    }

    private fun batteryExempt(): Boolean {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun refresh() {
        val view = view ?: return
        val ctx = requireContext()
        val serviceUp = ScreenGuardAccessibilityService.isRunning() ||
                ScreenGuardAccessibilityService.isEnabledInSettings(ctx)
        val admin = UiSafety.guardElse("HomeFragment.admin", false) { adminActive() }
        val battery = UiSafety.guardElse("HomeFragment.battery", false) { batteryExempt() }

        // Header state chip
        val chip = view.findViewById<TextView>(R.id.tvHeaderState)
        if (serviceUp) {
            chip.text = getString(R.string.dash_state_active)
            chip.setBackgroundResource(R.drawable.bg_pill_done)
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.success_text))
        } else {
            chip.text = getString(R.string.dash_state_inactive)
            chip.setBackgroundResource(R.drawable.bg_pill_danger)
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
        }

        // Counters
        val count = ScreenshotStore.count(ctx)
        view.findViewById<TextView>(R.id.tvStatCount).text = count.toString()
        view.findViewById<TextView>(R.id.rowGallerySub).text =
            getString(R.string.row_gallery_sub, count)

        val last = Prefs.lastCapture(ctx)
        view.findViewById<TextView>(R.id.tvStatLast).text =
            if (last == 0L) getString(R.string.never) else relative(last)

        // Key card summary
        val active = listOf(serviceUp, admin, battery).count { it }
        view.findViewById<TextView>(R.id.tvKeySub).text =
            if (active == 3) getString(R.string.key_sub_all)
            else getString(R.string.key_sub_partial, active)

        // Protection badges
        setBadge(view, R.id.badgeAccessibility, serviceUp)
        setBadge(view, R.id.badgeAdmin, admin)
        setBadge(view, R.id.badgeBattery, battery)
    }

    private fun setBadge(view: View, id: Int, done: Boolean) {
        val badge = view.findViewById<TextView>(id)
        badge.text = getString(if (done) R.string.badge_done else R.string.badge_pending)
        badge.setBackgroundResource(
            if (done) R.drawable.bg_pill_done else R.drawable.bg_pill_pending
        )
        badge.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (done) R.color.success_text else R.color.text_tertiary
            )
        )
    }

    private fun relative(timeMs: Long): String {
        val diffMin = ((System.currentTimeMillis() - timeMs) / 60000L).coerceAtLeast(0)
        return when {
            diffMin < 1 -> getString(R.string.just_now)
            diffMin < 60 -> getString(R.string.minutes_ago, diffMin)
            diffMin < 1440 -> {
                val h = diffMin / 60
                getString(R.string.hours_ago, h)
            }
            else -> {
                val fmt = java.text.SimpleDateFormat("dd MMM", Locale.getDefault())
                fmt.format(java.util.Date(timeMs))
            }
        }
    }

    private fun confirmLockNow() {
        val view = view ?: return
        if (!adminActive()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lock_now_title)
                .setMessage(R.string.lock_now_needs_admin)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            ComponentName(requireContext(), AdminReceiver::class.java)
                        )
                    }
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lock_now_title)
            .setMessage(R.string.lock_now_confirm)
            .setPositiveButton(R.string.lock_now_action) { _, _ ->
                val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
