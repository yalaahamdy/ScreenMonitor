package com.screenguard.app

import android.app.admin.DevicePolicyManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.screenguard.app.data.repository.ImportMode
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.data.repository.BackupRepository
import com.screenguard.app.security.BootResilienceManager
import com.screenguard.app.ui.permission.UsageAccessHelper
import com.screenguard.app.UiSafety
import androidx.core.content.FileProvider
import java.io.File
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every monitoring behaviour, transparently adjustable behind the PIN. */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        // v3.1.1: even layout inflation is guarded — if the XML cannot be
        // inflated on this device the screen still opens with an error card
        // and a copyable report instead of crashing.
        if (!UiSafety.guard("SettingsActivity.inflate") {
                setContentView(R.layout.activity_settings)
            }) {
            setContentView(UiSafety.errorCard(this, "SettingsActivity.inflate", null))
            return
        }

        UiSafety.guard("SettingsActivity.header") {
            findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
            findViewById<View>(R.id.rowChangePin).setOnClickListener {
                showChangePinDialog()
            }
        }

        UiSafety.guard("SettingsActivity.permissions") { wirePermissionsSection() }
        UiSafety.guard("SettingsActivity.capture") { wireCaptureSection() }
        UiSafety.guard("SettingsActivity.lockTimeout") { wireLockTimeoutChips() }
        UiSafety.guard("SettingsActivity.protection") { wireProtectionSection() }
        UiSafety.guard("SettingsActivity.backup") { wireBackupSection() }
        UiSafety.guard("SettingsActivity.crashLog") { wireCrashLogRow() }
        UiSafety.guard("SettingsActivity.appearance") { wireAppearanceSelectors() }
    }

    override fun onResume() {
        super.onResume()
        UiSafety.guard("SettingsActivity.onResume") { onResumeSafe() }
    }

    private fun onResumeSafe() {
        findViewById<TextView>(R.id.tvVersion).text = BuildConfig.VERSION_NAME
        refreshAppearanceChecks()
        refreshPermissionsStatus()
        refreshCaptureInputs()
        renderDiagnostics()
        renderProtectionStatus()
        renderCrashLogRow()
        renderLockTimeoutChips()
    }

    // ---------- Permissions hub (v3.1) ----------

    private fun wirePermissionsSection() {
        findViewById<View>(R.id.rowPermUsageAccess).setOnClickListener {
            UsageAccessHelper.openUsageAccessSettings(this)
        }
        findViewById<View>(R.id.rowPermOverlay).setOnClickListener {
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
        findViewById<View>(R.id.rowPermNotifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                } catch (e: Exception) {
                    openAppNotificationSettings()
                }
            } else {
                openAppNotificationSettings()
            }
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {}
        }
    }

    private fun refreshPermissionsStatus() {
        val usageOk = UsageAccessHelper.hasUsageAccess(this)
        UiSafety.guard("SettingsActivity.permUsage") {
            findViewById<TextView>(R.id.tvPermUsageSub).text =
                getString(if (usageOk) R.string.perm_usage_granted else R.string.perm_usage_missing)
            findViewById<TextView>(R.id.tvPermUsageSub)
                .setTextColor(getColor(if (usageOk) R.color.success else R.color.warning))
        }

        val overlayOk = try {
            Settings.canDrawOverlays(this)
        } catch (e: Exception) {
            false
        }
        UiSafety.guard("SettingsActivity.permOverlay") {
            findViewById<TextView>(R.id.tvPermOverlaySub).text =
                getString(if (overlayOk) R.string.perm_overlay_granted else R.string.perm_overlay_missing)
            findViewById<TextView>(R.id.tvPermOverlaySub)
                .setTextColor(getColor(if (overlayOk) R.color.success else R.color.warning))
        }

        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        UiSafety.guard("SettingsActivity.permNotif") {
            findViewById<TextView>(R.id.tvPermNotifSub).text =
                getString(if (notifOk) R.string.perm_notifications_granted else R.string.perm_notifications_missing)
            findViewById<TextView>(R.id.tvPermNotifSub)
                .setTextColor(getColor(if (notifOk) R.color.success else R.color.warning))
        }
    }

    // ---------- Capture behaviour (v3.1) ----------

    private fun wireCaptureSection() {
        // swUsageCapture / swPeriodic get dedicated listeners below that
        // persist the value AND toggle the dependent input blocks.
        bindSwitch(R.id.swAppOpen) { Prefs.setCaptureOnAppOpen(this, it) }
        bindSwitch(R.id.swWake) { Prefs.setCaptureOnWake(this, it) }
        bindSwitch(R.id.swToast) { Prefs.setShowToast(this, it) }

        val swUsage = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swUsageCapture)
        swUsage.setOnCheckedChangeListener { _, checked ->
            Prefs.setCaptureOnUsage(this, checked)
            findViewById<View>(R.id.usageMinutesBlock).visibility =
                if (checked) View.VISIBLE else View.GONE
        }

        val swPeriodic = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swPeriodic)
        swPeriodic.setOnCheckedChangeListener { _, checked ->
            Prefs.setCapturePeriodic(this, checked)
            findViewById<View>(R.id.intervalBlock).visibility =
                if (checked) View.VISIBLE else View.GONE
        }

        val etUsage = findViewById<EditText>(R.id.etUsageMinutes)
        etUsage.addTextChangedListener(simpleWatcher {
            val minutes = it.toIntOrNull()
            if (minutes != null && minutes > 0) {
                Prefs.setUsageCaptureMinutes(this@SettingsActivity, minutes.coerceAtMost(720))
            }
        })
        etUsage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val minutes = Prefs.usageCaptureMinutes(this).coerceAtLeast(1)
                if (etUsage.text.toString().trim().toIntOrNull() == null) {
                    etUsage.setText(minutes.toString())
                }
            }
        }

        val etInterval = findViewById<EditText>(R.id.etInterval)
        etInterval.addTextChangedListener(simpleWatcher {
            val minutes = it.toIntOrNull()
            if (minutes != null && minutes > 0) {
                Prefs.setIntervalMinutes(this@SettingsActivity, minutes)
                ScreenGuardAccessibilityService.refreshSchedule()
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

        val etMaxScreenshots = findViewById<EditText>(R.id.etMaxScreenshots)
        etMaxScreenshots.addTextChangedListener(simpleWatcher {
            val max = it.toIntOrNull()
            if (max != null && max > 0) {
                Prefs.setMaxScreenshots(this@SettingsActivity, max)
                ScreenshotStore.enforceQuota(this@SettingsActivity)
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
    }

    private fun refreshCaptureInputs() {
        val usage = Prefs.usageCaptureMinutes(this).coerceAtLeast(1)
        val etUsage = findViewById<EditText>(R.id.etUsageMinutes)
        if (etUsage.text.toString().trim() != usage.toString()) {
            etUsage.setText(usage.toString())
        }
        findViewById<View>(R.id.usageMinutesBlock).visibility =
            if (Prefs.captureOnUsage(this)) View.VISIBLE else View.GONE

        val minutes = Prefs.intervalMinutes(this).coerceAtLeast(1)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        if (etInterval.text.toString().trim() != minutes.toString()) {
            etInterval.setText(minutes.toString())
        }
        findViewById<View>(R.id.intervalBlock).visibility =
            if (Prefs.capturePeriodic(this)) View.VISIBLE else View.GONE

        setSwitch(R.id.swUsageCapture, Prefs.captureOnUsage(this))
        setSwitch(R.id.swPeriodic, Prefs.capturePeriodic(this))
        setSwitch(R.id.swAppOpen, Prefs.captureOnAppOpen(this))
        setSwitch(R.id.swWake, Prefs.captureOnWake(this))
        setSwitch(R.id.swToast, Prefs.showToast(this))

        val max = Prefs.maxScreenshots(this).coerceAtLeast(1)
        val etMax = findViewById<EditText>(R.id.etMaxScreenshots)
        if (etMax.text.toString().trim() != max.toString()) {
            etMax.setText(max.toString())
        }
    }

    private fun bindSwitch(id: Int, getter: (SettingsActivity) -> Boolean, setter: (SettingsActivity, Boolean) -> Unit) {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)
        sw.isChecked = getter(this)
        sw.setOnCheckedChangeListener { _, checked -> setter(this, checked) }
    }

    private fun bindSwitch(id: Int, setter: (Boolean) -> Unit) {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)
        sw.setOnCheckedChangeListener { _, checked -> setter(checked) }
    }

    private fun setSwitch(id: Int, checked: Boolean) {
        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)
        sw.isChecked = checked
    }

    private fun simpleWatcher(block: (String) -> Unit): android.text.TextWatcher {
        return object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                block(s?.toString()?.trim().orEmpty())
            }
        }
    }

    // ---------- App-lock timeout chips (v3.1) ----------

    private fun wireLockTimeoutChips() {
        val chips = mapOf(
            R.id.btnLockTimeout0 to 0,
            R.id.btnLockTimeout30 to 30,
            R.id.btnLockTimeout60 to 60,
            R.id.btnLockTimeout300 to 300
        )
        chips.forEach { (id, seconds) ->
            findViewById<View>(id).setOnClickListener {
                Prefs.setLockTimeoutSeconds(seconds)
                renderLockTimeoutChips()
            }
        }
    }

    private fun renderLockTimeoutChips() {
        val current = Prefs.getLockTimeoutSeconds()
        val chips = mapOf(
            R.id.btnLockTimeout0 to 0,
            R.id.btnLockTimeout30 to 30,
            R.id.btnLockTimeout60 to 60,
            R.id.btnLockTimeout300 to 300
        )
        chips.forEach { (id, seconds) ->
            val chip = findViewById<TextView>(id)
            val selected = current == seconds
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(getColor(if (selected) R.color.on_gold else R.color.text_secondary))
        }
    }

    private fun renderProtectionStatus() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
        findViewById<TextView>(R.id.tvDeviceAdminSub).text =
            getString(
                if (admin) R.string.protection_admin_active else R.string.protection_device_admin_sub
            )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.tvBatterySub).text =
            getString(
                if (exempt) R.string.protection_battery_active else R.string.protection_battery_sub
            )

        findViewById<View>(R.id.rowOemAutostart).visibility =
            if (BootResilienceManager.isOemWithAggressiveBatteryManagement()) View.VISIBLE else View.GONE
    }

    // ---------- Crash log (v3.1) ----------

    private fun renderCrashLogRow() {
        val has = CrashLogger.hasLogs(this)
        findViewById<View>(R.id.rowCrashLog).visibility = if (has) View.VISIBLE else View.GONE
        findViewById<View>(R.id.crashLogDivider).visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun wireCrashLogRow() {
        findViewById<View>(R.id.rowCrashLog).setOnClickListener { showCrashLogDialog() }
    }

    private fun showCrashLogDialog() {
        val content = CrashLogger.readLogs(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val tv = TextView(this).apply {
            text = content.ifEmpty { getString(R.string.crash_log_empty) }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * resources.displayMetrics.density).toInt()
            )
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_title)
            .setView(container)
            .setPositiveButton(R.string.crash_log_copy) { _, _ ->
                copyToClipboard(content)
            }
            .setNeutralButton(R.string.crash_log_clear) { _, _ ->
                CrashLogger.clear(this)
                renderCrashLogRow()
                Toast.makeText(this, R.string.crash_log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenGuard", text))
            Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
    }

    // ---------- Appearance & language ----------

    private fun wireAppearanceSelectors() {
        // Interface mode: dark / light / follow-system (v3.2.0)
        val modes = mapOf(
            R.id.rowModeDark to ThemeHelper.MODE_DARK,
            R.id.rowModeLight to ThemeHelper.MODE_LIGHT,
            R.id.rowModeSystem to ThemeHelper.MODE_SYSTEM
        )
        modes.forEach { (rowId, mode) ->
            findViewById<View>(rowId).setOnClickListener {
                if (Prefs.themeMode(this) != mode) {
                    Prefs.setThemeMode(this, mode)
                    ThemeHelper.requestUiRefresh(this)
                    ThemeHelper.applyDayNight(this)
                    recreate()
                }
            }
        }

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
        val modeChecks = mapOf(
            R.id.checkModeDark to ThemeHelper.MODE_DARK,
            R.id.checkModeLight to ThemeHelper.MODE_LIGHT,
            R.id.checkModeSystem to ThemeHelper.MODE_SYSTEM
        )
        modeChecks.forEach { (checkId, mode) ->
            findViewById<View>(checkId).visibility =
                if (Prefs.themeMode(this) == mode) View.VISIBLE else View.GONE
        }

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

    // ---------- Advanced protection (merged engine) ----------

    private fun wireProtectionSection() {
        val adminIntent = {
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@SettingsActivity, AdminReceiver::class.java)
                )
            }
        }

        findViewById<View>(R.id.rowDeviceAdmin).setOnClickListener {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
            if (admin) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } else {
                startActivity(adminIntent())
            }
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAntiTamper)
            .isChecked = PinManager.isAntiTamperEnabled(this)
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAntiTamper)
            .setOnCheckedChangeListener { _, checked ->
                PinManager.setAntiTamperEnabled(this, checked)
            }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAntiUninstall)
            .isChecked = PinManager.isAntiUninstallEnabled(this)
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAntiUninstall)
            .setOnCheckedChangeListener { _, checked ->
                PinManager.setAntiUninstallEnabled(this, checked)
            }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swSafeMode)
            .isChecked = PinManager.isSafeModeProtectionEnabled(this)
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swSafeMode)
            .setOnCheckedChangeListener { _, checked ->
                PinManager.setSafeModeProtectionEnabled(this, checked)
            }

        findViewById<View>(R.id.rowSecurityQuestion).setOnClickListener {
            showUpdateSecurityQuestionDialog()
        }

        findViewById<View>(R.id.rowBatteryExempt).setOnClickListener {
            BootResilienceManager.requestIgnoreBatteryOptimizations(this)
        }

        findViewById<View>(R.id.rowOemAutostart).setOnClickListener {
            if (!BootResilienceManager.openOemAutostartSettings(this)) {
                Toast.makeText(this, R.string.protection_autostart_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateSecurityQuestionDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_security_question, null, false)
        val etPin = view.findViewById<EditText>(R.id.etConfirmPin)
        val etQuestion = view.findViewById<EditText>(R.id.etQuestion)
        val etAnswer = view.findViewById<EditText>(R.id.etAnswer)
        etQuestion.setText(PinManager.getSecurityQuestion(this))

        AlertDialog.Builder(this)
            .setTitle(R.string.protection_security_question)
            .setView(view)
            .setPositiveButton(R.string.act_save) { _, _ ->
                val ok = PinManager.updateSecurityQuestion(
                    this,
                    etPin.text?.toString().orEmpty(),
                    etQuestion.text?.toString().orEmpty(),
                    etAnswer.text?.toString().orEmpty()
                )
                Toast.makeText(
                    this,
                    if (ok) R.string.pin_changed else R.string.err_current_pin,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    // ---------- Backup & restore ----------

    private lateinit var backupRepo: BackupRepository

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) exportBackupTo(uri)
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importBackupFrom(uri)
    }

    private fun wireBackupSection() {
        backupRepo = BackupRepository(
            this,
            AppRestrictionsRepository.getInstance(this),
            PinManager
        )
        findViewById<View>(R.id.rowExportBackup).setOnClickListener {
            exportBackupLauncher.launch("screenguard_backup.json")
        }
        findViewById<View>(R.id.rowImportBackup).setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    private fun exportBackupTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    backupRepo.exportBackupJson(includeSecuritySettings = true)
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                Toast.makeText(this@SettingsActivity, R.string.backup_export_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.backup_error_invalid, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importBackupFrom(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                }
                val validation = withContext(Dispatchers.IO) {
                    backupRepo.validateBackupJson(json)
                }
                if (!validation.isValid || validation.metadata == null) {
                    Toast.makeText(
                        this@SettingsActivity,
                        validation.errorMessage ?: getString(R.string.backup_error_invalid),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.backup_import)
                    .setMessage(
                        getString(
                            R.string.backup_import_confirm,
                            validation.metadata.restrictionsCount,
                            validation.metadata.exportedAtFormatted
                        )
                    )
                    .setPositiveButton(R.string.import_mode_merge_title) { _, _ ->
                        val result = backupRepo.importBackup(validation, ImportMode.MERGE, true)
                        Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                    .setNeutralButton(R.string.import_mode_replace_title) { _, _ ->
                        val result = backupRepo.importBackup(validation, ImportMode.REPLACE_ALL, true)
                        Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(R.string.act_cancel, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.backup_error_invalid, Toast.LENGTH_LONG).show()
            }
        }
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
