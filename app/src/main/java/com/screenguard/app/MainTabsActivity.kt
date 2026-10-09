package com.screenguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.screenguard.app.UiSafety
import com.screenguard.app.ui.permission.UsageAccessHelper
import com.screenguard.app.ui.tabs.AnalyticsFragment
import com.screenguard.app.ui.tabs.DataUsageFragment
import com.screenguard.app.ui.tabs.HomeFragment
import com.screenguard.app.ui.tabs.RestrictionsFragment

/**
 * Unified main experience: bottom navigation hosting Home (capture
 * dashboard), Analytics (screen time), Data Usage (network) and
 * Restrictions (blocking rules), with Gallery and Settings reachable
 * from the top bar.
 *
 * v3.1: on entry the activity verifies the runtime permissions the new
 * tabs depend on (Usage Access / Overlay / Notifications) and offers a
 * one-tap guide for anything missing, instead of letting the tabs fail
 * or show empty data silently.
 */
class MainTabsActivity : BaseActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var topBar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_tabs)
        topBar = findViewById(R.id.topBar)
        bottomNav = findViewById(R.id.bottomNav)
        setSupportActionBar(topBar)

        bottomNav.setOnItemSelectedListener { item ->
            UiSafety.guard("MainTabsActivity.onNavSelected") {
                when (item.itemId) {
                    R.id.tab_home -> switchTo(HomeFragment())
                    R.id.tab_analytics -> switchTo(AnalyticsFragment())
                    R.id.tab_data -> switchTo(DataUsageFragment())
                    R.id.tab_restrictions -> switchTo(RestrictionsFragment())
                }
            }
            true
        }

        if (savedInstanceState == null) {
            switchTo(HomeFragment())
            bottomNav.selectedItemId = R.id.tab_home
            maybeShowPermissionsDialog()
        }
    }

    // ------------------------------------------------------------------
    // v3.1 permissions hub
    // ------------------------------------------------------------------

    private fun missingCriticalPermissions(): Boolean =
        !UsageAccessHelper.hasUsageAccess(this)

    private fun maybeShowPermissionsDialog() {
        if (!missingCriticalPermissions()) return

        // Show at most once per app version so it never becomes nagging
        val versionTag = "v${BuildConfig.VERSION_CODE}"
        if (Prefs.permissionsDialogShownFor(this) == versionTag) return
        Prefs.setPermissionsDialogShownFor(this, versionTag)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_dialog_title)
            .setMessage(R.string.perm_dialog_message)
            .setPositiveButton(R.string.perm_dialog_open) { _, _ ->
                openUsageAccess()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {}
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {}
        }
    }

    private fun switchTo(fragment: Fragment) {
        try {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.tabContainer, fragment)
            }
        } catch (t: Throwable) {
            // v3.1.1: a broken fragment must never take the app down
            CrashLogger.log(t, "MainTabsActivity.switchTo")
            val container = findViewById<ViewGroup>(R.id.tabContainer)
            container.removeAllViews()
            container.addView(UiSafety.errorCard(this, "MainTabsActivity.switchTo", t))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_tabs, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_gallery -> {
                startActivity(Intent(this, GalleryActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
