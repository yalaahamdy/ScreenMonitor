package com.screenguard.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

import androidx.core.content.ContextCompat

/**
 * Setup wizard: five transparent steps that must be understood and granted
 * by the parent before monitoring starts.
 */
class MainActivity : BaseActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            // Explicit app launch: lock session immediately
            PinManager.lockSession()
        }

        if (PinManager.hasPin(this)) {
            if (!PinManager.isSessionUnlocked()) {
                val lockIntent = Intent(this, PinLockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(lockIntent)
                finish()
                return
            } else if (Prefs.isSetupComplete(this)) {
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btnPin).setOnClickListener {
            if (PinManager.hasPin(this)) {
                showChangePinDialog()
            } else {
                showSetPinDialog()
            }
        }
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@MainActivity, AdminReceiver::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_explanation)
                )
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnFinish).setOnClickListener {
            Prefs.setSetupComplete(this, true)
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun notifGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun adminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refresh() {
        setStep(
            R.id.badgeNotif, R.id.btnNotif, notifGranted()
        )
        setStep(
            R.id.badgePin, R.id.btnPin, PinManager.hasPin(this)
        )
        setStep(
            R.id.badgeAcc, R.id.btnAcc,
            ScreenGuardAccessibilityService.isRunning() ||
                    ScreenGuardAccessibilityService.isEnabledInSettings(this)
        )
        setStep(R.id.badgeAdmin, R.id.btnAdmin, adminActive())
        setStep(R.id.badgeBattery, R.id.btnBattery, batteryExempt())
    }

    private fun setStep(badgeId: Int, btnId: Int, done: Boolean) {
        val badge = findViewById<TextView>(badgeId)
        val button = findViewById<Button>(btnId)
        if (done) {
            badge.setBackgroundResource(R.drawable.bg_pill_done)
            badge.setTextColor(ContextCompat.getColor(this, R.color.success_text))
            badge.text = getString(R.string.badge_done)
            button.visibility = android.view.View.GONE
        } else {
            badge.setBackgroundResource(R.drawable.bg_pill_pending)
            badge.setTextColor(themedColor(R.attr.sgAccentSoft))
            badge.text = getString(R.string.badge_pending)
            button.visibility = android.view.View.VISIBLE
        }
    }

    private fun showSetPinDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_set_pin, null, false)
        val etPin = view.findViewById<EditText>(R.id.etPin)
        val etConfirm = view.findViewById<EditText>(R.id.etConfirm)
        etPin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_set_pin_title)
            .setView(view)
            .setPositiveButton(R.string.act_save) { _, _ ->
                val pin = etPin.text?.toString().orEmpty()
                val confirm = etConfirm.text?.toString().orEmpty()
                when {
                    pin.length != 4 || !pin.all { c -> c.isDigit() } ->
                        Toast.makeText(this, R.string.err_pin_length, Toast.LENGTH_SHORT).show()
                    pin != confirm ->
                        Toast.makeText(this, R.string.err_pin_mismatch, Toast.LENGTH_SHORT).show()
                    else -> {
                        PinManager.setPin(this, pin)
                        Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
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
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }
}
