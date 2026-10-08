package com.screenguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.Date

/** Every monitoring behaviour, transparently adjustable behind the PIN. */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowChangePin).setOnClickListener {
            showChangePinDialog()
        }

        val etInterval = findViewById<EditText>(R.id.etInterval)
        etInterval.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val input = s?.toString()?.trim().orEmpty()
                val minutes = input.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    Prefs.setIntervalMinutes(this@SettingsActivity, minutes)
                    ScreenGuardAccessibilityService.refreshSchedule()
                }
            }
        })
        etInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val minutes = Prefs.intervalMinutes(this).coerceAtLeast(1)
                if (etInterval.text.toString().trim().toIntOrNull() == null) {
                    etInterval.setText(minutes.toString())
                }
            }
        }

        val swAppOpen = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAppOpen)
        swAppOpen.setOnCheckedChangeListener { _, checked ->
            Prefs.setCaptureOnAppOpen(this, checked)
        }

        val swToast = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swToast)
        swToast.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowToast(this, checked)
        }

        val etMaxScreenshots = findViewById<EditText>(R.id.etMaxScreenshots)
        etMaxScreenshots.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val input = s?.toString()?.trim().orEmpty()
                val max = input.toIntOrNull()
                if (max != null && max > 0) {
                    Prefs.setMaxScreenshots(this@SettingsActivity, max)
                    ScreenshotStore.enforceQuota(this@SettingsActivity)
                }
            }
        })
        etMaxScreenshots.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val max = Prefs.maxScreenshots(this).coerceAtLeast(1)
                if (etMaxScreenshots.text.toString().trim().toIntOrNull() == null) {
                    etMaxScreenshots.setText(max.toString())
                }
            }
        }

        wireAppearanceSelectors()
    }

    override fun onResume() {
        super.onResume()
        val minutes = Prefs.intervalMinutes(this).coerceAtLeast(1)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        if (etInterval.text.toString().trim() != minutes.toString()) {
            etInterval.setText(minutes.toString())
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAppOpen)
            .isChecked = Prefs.captureOnAppOpen(this)
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swToast)
            .isChecked = Prefs.showToast(this)

        val max = Prefs.maxScreenshots(this).coerceAtLeast(1)
        val etMax = findViewById<EditText>(R.id.etMaxScreenshots)
        if (etMax.text.toString().trim() != max.toString()) {
            etMax.setText(max.toString())
        }

        findViewById<TextView>(R.id.tvVersion).text = BuildConfig.VERSION_NAME
        refreshAppearanceChecks()
        renderDiagnostics()
    }

    // ---------- Appearance & language ----------

    private fun wireAppearanceSelectors() {
        val themes = mapOf(
            R.id.rowThemeMidnight to ThemeHelper.THEME_MIDNIGHT,
            R.id.rowThemeEmerald to ThemeHelper.THEME_EMERALD,
            R.id.rowThemeRose to ThemeHelper.THEME_ROSE,
            R.id.rowThemeOnyx to ThemeHelper.THEME_ONYX,
            R.id.rowThemeSapphire to ThemeHelper.THEME_SAPPHIRE
        )
        themes.forEach { (rowId, theme) ->
            findViewById<View>(rowId).setOnClickListener {
                if (Prefs.theme(this) != theme) {
                    Prefs.setTheme(this, theme)
                    ThemeHelper.requestUiRefresh(this)
                    recreate()
                }
            }
        }

        val languages = mapOf(
            R.id.rowLangSystem to LocaleHelper.LANG_SYSTEM,
            R.id.rowLangEnglish to LocaleHelper.LANG_ENGLISH,
            R.id.rowLangArabic to LocaleHelper.LANG_ARABIC
        )
        languages.forEach { (rowId, lang) ->
            findViewById<View>(rowId).setOnClickListener {
                if (Prefs.language(this) != lang) {
                    Prefs.setLanguage(this, lang)
                    ThemeHelper.requestUiRefresh(this)
                    recreate()
                }
            }
        }
    }

    private fun refreshAppearanceChecks() {
        val themeChecks = mapOf(
            R.id.checkThemeMidnight to ThemeHelper.THEME_MIDNIGHT,
            R.id.checkThemeEmerald to ThemeHelper.THEME_EMERALD,
            R.id.checkThemeRose to ThemeHelper.THEME_ROSE,
            R.id.checkThemeOnyx to ThemeHelper.THEME_ONYX,
            R.id.checkThemeSapphire to ThemeHelper.THEME_SAPPHIRE
        )
        themeChecks.forEach { (checkId, theme) ->
            findViewById<View>(checkId).visibility =
                if (Prefs.theme(this) == theme) View.VISIBLE else View.GONE
        }

        val langChecks = mapOf(
            R.id.checkLangSystem to LocaleHelper.LANG_SYSTEM,
            R.id.checkLangEnglish to LocaleHelper.LANG_ENGLISH,
            R.id.checkLangArabic to LocaleHelper.LANG_ARABIC
        )
        langChecks.forEach { (checkId, lang) ->
            findViewById<View>(checkId).visibility =
                if (Prefs.language(this) == lang) View.VISIBLE else View.GONE
        }
    }

    private fun renderDiagnostics() {
        val serviceUp = ScreenGuardAccessibilityService.isRunning() ||
                ScreenGuardAccessibilityService.isEnabledInSettings(this)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)

        val last = Prefs.lastCapture(this)
        val lastStr = if (last == 0L) getString(R.string.never)
        else DateFormat.getMediumDateFormat(this)
            .format(Date(last)) + " " + DateFormat.getTimeFormat(this).format(Date(last))

        val dir = ScreenshotStore.dir(this)
        val usedMb = "%.1f".format(dir.listFiles()?.sumOf { it.length() }?.div(1024f * 1024f) ?: 0f)

        findViewById<TextView>(R.id.tvDiagnostics).text = listOf(
            getString(R.string.diag_status),
            "  ${getString(R.string.diag_accessibility)}:  ${if (serviceUp) getString(R.string.diag_active) else getString(R.string.diag_off)}",
            "  ${getString(R.string.diag_device_admin)}:  ${if (admin) getString(R.string.diag_active) else getString(R.string.diag_off)}",
            "  ${getString(R.string.diag_battery)}:  ${if (battery) getString(R.string.diag_granted) else getString(R.string.diag_missing)}",
            "",
            getString(R.string.diag_library),
            "  ${getString(R.string.diag_captures)}:  ${ScreenshotStore.count(this)}",
            "  ${getString(R.string.diag_disk)}:  $usedMb MB",
            "  ${getString(R.string.diag_folder)}:  ${dir.absolutePath}",
            "  ${getString(R.string.diag_last)}:  $lastStr",
            "",
            getString(R.string.diag_system),
            "  ${getString(R.string.diag_app_version)}:  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "  ${getString(R.string.diag_android)}:  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ).joinToString("\n")
    }

    private fun showChangePinDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_change_pin, null, false)
        val etCurrent = view.findViewById<EditText>(R.id.etCurrent)
        val etNew = view.findViewById<EditText>(R.id.etNew)
        val etConfirm = view.findViewById<EditText>(R.id.etConfirm)
        listOf(etCurrent, etNew, etConfirm).forEach {
            it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_change_pin_title)
            .setView(view)
            .setPositiveButton(R.string.act_save) { _, _ ->
                val current = etCurrent.text?.toString().orEmpty()
                val newPin = etNew.text?.toString().orEmpty()
                val confirm = etConfirm.text?.toString().orEmpty()
                when {
                    !PinManager.verify(this, current) ->
                        Toast.makeText(this, R.string.err_current_pin, Toast.LENGTH_SHORT).show()
                    newPin.length != 4 || !newPin.all { c -> c.isDigit() } ->
                        Toast.makeText(this, R.string.err_pin_length, Toast.LENGTH_SHORT).show()
                    newPin != confirm ->
                        Toast.makeText(this, R.string.err_pin_mismatch, Toast.LENGTH_SHORT).show()
                    else -> {
                        PinManager.changePin(this, current, newPin)
                        Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }
}
