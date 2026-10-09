package com.screenguard.app.ui.block

import android.app.Dialog
import android.app.KeyguardManager
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.BaseActivity
import com.screenguard.app.MainActivity
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.PinDotsView

/**
 * Transparent dialog-style fallback activity shown when an app is blocked
 * (used when the overlay window cannot be displayed). Ported from the
 * usage-controller app and rebuilt with classic XML views and the
 * ScreenGuard theme tokens.
 */
class BlockActivity : BaseActivity() {

    companion object {
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_APP_NAME = "extra_app_name"
        private const val EXTRA_REASON = "extra_reason"
        private const val EXTRA_NEXT_AVAILABLE = "extra_next_available"
        private const val EXTRA_CONSUMED_MINUTES = "extra_consumed_minutes"
        private const val EXTRA_ALLOWED_MINUTES = "extra_allowed_minutes"

        fun createIntent(
            context: Context,
            packageName: String,
            appName: String,
            reason: String,
            nextAvailable: String?,
            consumedMinutes: Int,
            allowedMinutes: Int
        ): Intent {
            return Intent(context, BlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_NEXT_AVAILABLE, nextAvailable)
                putExtra(EXTRA_CONSUMED_MINUTES, consumedMinutes)
                putExtra(EXTRA_ALLOWED_MINUTES, allowedMinutes)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                goHome()
            }
        }
    }

    private var bypassDialog: Dialog? = null

    override fun useRuntimeTheme(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            goHome()
            return
        }

        try {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (e: Exception) {}

        setContentView(R.layout.activity_block)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: Str.get(R.string.block_default_app_name)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: Str.get(R.string.block_default_reason)
        val nextAvailable = intent.getStringExtra(EXTRA_NEXT_AVAILABLE)

        // Populate the block card
        findViewById<TextView>(R.id.blockAppName).text = appName
        findViewById<TextView>(R.id.blockReason).text = reason

        val appIconView = findViewById<ImageView>(R.id.blockAppIcon)
        val icon = AppInfoManager.getInstance(this).getAppIcon(pkg)
        if (icon != null) {
            appIconView.setImageDrawable(icon)
        } else {
            appIconView.setImageResource(R.drawable.ic_shield)
        }

        val nextAvailableBox = findViewById<View>(R.id.nextAvailableBox)
        if (nextAvailable.isNullOrBlank()) {
            nextAvailableBox.visibility = View.GONE
        } else {
            nextAvailableBox.visibility = View.VISIBLE
            findViewById<TextView>(R.id.nextAvailableValue).text = nextAvailable
        }

        findViewById<Button>(R.id.btnGoHome).setOnClickListener { goHome() }
        findViewById<Button>(R.id.btnBypass).setOnClickListener { showBypassDialog(pkg, appName) }
        findViewById<Button>(R.id.btnManage).setOnClickListener { openManagement() }

        // Tapping the dimmed background also goes home
        findViewById<View>(R.id.blockRoot).setOnClickListener { goHome() }
        findViewById<View>(R.id.blockCard).setOnClickListener { /* swallow clicks */ }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(homeIntent)
        } catch (e: Exception) {}
        finish()
    }

    private fun openManagement() {
        val mgmtIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(mgmtIntent)
        finish()
    }

    // ------------------------------------------------------------------
    // Two-step bypass dialog: duration -> PIN verification
    // ------------------------------------------------------------------

    private fun showBypassDialog(pkg: String, appName: String) {
        val selected = intArrayOf(15)
        var enteredPin = ""
        var stepIsDuration = true

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_block_bypass)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { }

        val titleView = dialog.findViewById<TextView>(R.id.bypassTitle)
        val durationStep = dialog.findViewById<View>(R.id.durationStep)
        val pinStep = dialog.findViewById<View>(R.id.pinStep)
        val pinSubtitleView = dialog.findViewById<TextView>(R.id.pinSubtitle)
        val badgeView = dialog.findViewById<TextView>(R.id.durationBadge)
        val slider = dialog.findViewById<SeekBar>(R.id.durationSlider)
        val errorView = dialog.findViewById<TextView>(R.id.pinError)
        val dots = dialog.findViewById<PinDotsView>(R.id.pinDots)

        fun formatted() = AppRestrictionsRepository.formatBypassDuration(selected[0])

        fun syncDuration() {
            badgeView.text = Str.get(R.string.overlay_selected_duration, formatted())
        }

        slider.max = 299
        slider.progress = 14
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                selected[0] = (progress + 1).coerceIn(1, 300)
                syncDuration()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val stepButtons = listOf(
            dialog.findViewById<Button>(R.id.btnMinus15) to -15,
            dialog.findViewById<Button>(R.id.btnMinus1) to -1,
            dialog.findViewById<Button>(R.id.btnPlus1) to 1,
            dialog.findViewById<Button>(R.id.btnPlus15) to 15
        )
        stepButtons.forEach { (btn, delta) ->
            btn.setOnClickListener {
                selected[0] = (selected[0] + delta).coerceIn(1, 300)
                slider.progress = selected[0] - 1
                syncDuration()
            }
        }

        val presets = listOf(
            dialog.findViewById<Button>(R.id.preset1) to 1,
            dialog.findViewById<Button>(R.id.preset5) to 5,
            dialog.findViewById<Button>(R.id.preset15) to 15,
            dialog.findViewById<Button>(R.id.preset30) to 30,
            dialog.findViewById<Button>(R.id.preset60) to 60,
            dialog.findViewById<Button>(R.id.preset120) to 120,
            dialog.findViewById<Button>(R.id.preset180) to 180,
            dialog.findViewById<Button>(R.id.preset300) to 300
        )
        presets.forEach { (btn, mins) ->
            btn.setOnClickListener {
                selected[0] = mins
                slider.progress = mins - 1
                syncDuration()
            }
        }

        fun applyBypass() {
            AppRestrictionsRepository.getInstance(this).setTemporaryBypass(pkg, selected[0])
            Toast.makeText(
                this,
                Str.get(R.string.overlay_bypass_success, appName, formatted()),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
            finish()
        }

        fun switchToPinStep() {
            if (!PinManager.hasPin(this)) {
                applyBypass()
                return
            }
            stepIsDuration = false
            titleView.text = Str.get(R.string.overlay_pin_step_title)
            durationStep.visibility = View.GONE
            pinStep.visibility = View.VISIBLE
            pinSubtitleView.text = Str.get(R.string.overlay_pin_subtitle, formatted())
            enteredPin = ""
            dots.reset()
            errorView.visibility = View.GONE
        }

        fun onPinDigit(digit: Char) {
            if (enteredPin.length >= 4) return
            enteredPin += digit
            dots.filled = enteredPin.length
            if (enteredPin.length == 4) {
                dots.state = PinDotsView.STATE_SUCCESS
                if (PinManager.verifyWithLockout(this, enteredPin)) {
                    applyBypass()
                } else {
                    val remaining = PinManager.getRemainingLockoutSeconds(this)
                    errorView.text = if (remaining > 0) {
                        Str.get(R.string.overlay_pin_locked_out, remaining)
                    } else {
                        Str.get(R.string.overlay_pin_wrong)
                    }
                    errorView.visibility = View.VISIBLE
                    enteredPin = ""
                    dots.state = PinDotsView.STATE_ERROR
                    handler.postDelayed({ if (!stepIsDuration) dots.reset() }, 700)
                }
            }
        }

        dialog.findViewById<TextView>(R.id.bypassDescription).text =
            Str.get(R.string.block_bypass_description, appName)

        dialog.findViewById<Button>(R.id.btnBackToDuration).setOnClickListener {
            stepIsDuration = true
            titleView.text = Str.get(R.string.overlay_bypass_button)
            pinStep.visibility = View.GONE
            durationStep.visibility = View.VISIBLE
        }
        dialog.findViewById<Button>(R.id.btnPinCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btnProceed).setOnClickListener { switchToPinStep() }

        val keypadButtons = listOf(
            R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
            R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9
        )
        keypadButtons.forEachIndexed { index, id ->
            dialog.findViewById<Button>(id).setOnClickListener { onPinDigit('0' + index) }
        }
        dialog.findViewById<ImageButton>(R.id.keyBackspace).setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
                dots.filled = enteredPin.length
            }
        }

        syncDuration()
        bypassDialog = dialog
        dialog.show()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStop() {
        super.onStop()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            goHome()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
        bypassDialog?.dismiss()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }
}
