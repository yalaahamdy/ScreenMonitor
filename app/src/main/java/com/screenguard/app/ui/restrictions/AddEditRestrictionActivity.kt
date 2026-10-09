package com.screenguard.app.ui.restrictions

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.materialswitch.MaterialSwitch
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.BaseActivity
import com.screenguard.app.R
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.model.GroupLimitType
import com.screenguard.app.data.model.LimitPeriod
import com.screenguard.app.data.model.TimeWindow

/**
 * Full-screen editor for a usage restriction: target apps, total block,
 * usage limits (daily/weekly, per-app or shared) and schedule windows.
 */
class AddEditRestrictionActivity : BaseActivity() {

    companion object {
        const val EXTRA_RESTRICTION_ID = "extra_restriction_id"
    }

    private var editingId: String? = null
    private val selectedPackages = linkedSetOf<String>()
    private val timeWindows = mutableListOf<TimeWindow>()
    private val activeDays = mutableSetOf(1, 2, 3, 4, 5, 6, 7)
    private var limitMinutes = 30

    private lateinit var nameInput: TextView
    private lateinit var appsSummary: TextView
    private lateinit var totalBlockSwitch: MaterialSwitch
    private lateinit var limitSwitch: MaterialSwitch
    private lateinit var limitSection: View
    private lateinit var limitBadge: TextView
    private lateinit var limitSlider: SeekBar
    private lateinit var scheduleSwitch: MaterialSwitch
    private lateinit var scheduleSection: View
    private lateinit var windowsContainer: LinearLayout
    private lateinit var groupEachChip: TextView
    private lateinit var groupSharedChip: TextView
    private lateinit var periodDailyChip: TextView
    private lateinit var periodWeeklyChip: TextView
    private val dayChips = mutableListOf<TextView>()

    private val groupLimitType: GroupLimitType
        get() = if (groupSharedChip.isSelected) GroupLimitType.SHARED_SUM else GroupLimitType.EACH_APP

    private val limitPeriod: LimitPeriod
        get() = if (periodWeeklyChip.isSelected) LimitPeriod.WEEKLY else LimitPeriod.DAILY

    private val pickApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val list = result.data?.getStringArrayListExtra(AppSelectionActivity.EXTRA_SELECTED)
                ?: return@registerForActivityResult
            selectedPackages.clear()
            selectedPackages.addAll(list)
            updateAppsSummary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_restriction)

        editingId = intent.getStringExtra(EXTRA_RESTRICTION_ID)
        bindViews()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.rowSelectApps).setOnClickListener { openAppSelection() }
        findViewById<View>(R.id.btnAddWindow).setOnClickListener { showTimeWindowPicker(null) }

        limitSlider.max = 719
        limitSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                limitMinutes = (progress + 1).coerceAtLeast(1)
                updateLimitBadge()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<View>(R.id.btnLimitMinus).setOnClickListener {
            limitMinutes = (limitMinutes - 5).coerceAtLeast(1)
            limitSlider.progress = limitMinutes - 1
            updateLimitBadge()
        }
        findViewById<View>(R.id.btnLimitPlus).setOnClickListener {
            limitMinutes = (limitMinutes + 5).coerceAtMost(720)
            limitSlider.progress = limitMinutes - 1
            updateLimitBadge()
        }

        totalBlockSwitch.setOnCheckedChangeListener { _, checked ->
            limitSwitch.isEnabled = !checked
            limitSection.visibility = if (checked || !limitSwitch.isChecked) View.GONE else View.VISIBLE
        }
        limitSwitch.setOnCheckedChangeListener { _, checked ->
            limitSection.visibility = if (checked) View.VISIBLE else View.GONE
        }
        scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            scheduleSection.visibility = if (checked) View.VISIBLE else View.GONE
        }

        setupGroupChips()
        setupDayChips()

        editingId?.let { loadExisting(it) }
        updateAppsSummary()
        updateLimitBadge()
    }

    private fun bindViews() {
        nameInput = findViewById(R.id.restrictionNameInput)
        appsSummary = findViewById(R.id.appsSummary)
        totalBlockSwitch = findViewById(R.id.totalBlockSwitch)
        limitSwitch = findViewById(R.id.limitSwitch)
        limitSection = findViewById(R.id.limitSection)
        limitBadge = findViewById(R.id.limitBadge)
        limitSlider = findViewById(R.id.limitSlider)
        scheduleSwitch = findViewById(R.id.scheduleSwitch)
        scheduleSection = findViewById(R.id.scheduleSection)
        windowsContainer = findViewById(R.id.windowsContainer)
        groupEachChip = findViewById(R.id.groupEachChip)
        groupSharedChip = findViewById(R.id.groupSharedChip)
        periodDailyChip = findViewById(R.id.periodDailyChip)
        periodWeeklyChip = findViewById(R.id.periodWeeklyChip)
    }

    private fun setupGroupChips() {
        fun sync() {
            groupEachChip.setBackgroundResource(
                if (groupEachChip.isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            groupSharedChip.setBackgroundResource(
                if (groupSharedChip.isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
        }
        groupEachChip.setOnClickListener { groupEachChip.isSelected = true; groupSharedChip.isSelected = false; sync() }
        groupSharedChip.setOnClickListener { groupSharedChip.isSelected = true; groupEachChip.isSelected = false; sync() }
        groupEachChip.isSelected = true
        sync()
    }

    private fun setupDayChips() {
        val container = findViewById<LinearLayout>(R.id.daysRow)
        val days = listOf(
            1 to R.string.day_sunday_short,
            2 to R.string.day_monday_short,
            3 to R.string.day_tuesday_short,
            4 to R.string.day_wednesday_short,
            5 to R.string.day_thursday_short,
            6 to R.string.day_friday_short,
            7 to R.string.day_saturday_short
        )
        days.forEach { (day, label) ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_day_chip, container, false) as TextView
            chip.text = getString(label)
            chip.setOnClickListener {
                if (activeDays.contains(day)) {
                    activeDays.remove(day)
                } else {
                    activeDays.add(day)
                }
                syncDayChip(chip, activeDays.contains(day))
            }
            syncDayChip(chip, true)
            container.addView(chip)
            dayChips.add(chip)
        }
    }

    private fun syncDayChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip.setTextColor(getColor(if (selected) R.color.on_gold else R.color.text_secondary))
    }

    private fun loadExisting(id: String) {
        val restriction = AppRestrictionsRepository.getInstance(this).getAllRestrictions()
            .firstOrNull { it.id == id } ?: return
        nameInput.text = restriction.appName
        selectedPackages.addAll(restriction.allPackages)
        totalBlockSwitch.isChecked = restriction.isTotalBlock
        limitSwitch.isChecked = restriction.hasUsageLimit
        limitMinutes = restriction.limitDurationMinutes
        limitSlider.progress = (limitMinutes - 1).coerceAtMost(719)
        if (restriction.groupLimitType == GroupLimitType.SHARED_SUM) {
            groupSharedChip.isSelected = true; groupEachChip.isSelected = false
        }
        if (restriction.limitPeriod == LimitPeriod.WEEKLY) {
            periodWeeklyChip.isSelected = true; periodDailyChip.isSelected = false
        }
        scheduleSwitch.isChecked = restriction.hasSchedule
        timeWindows.addAll(restriction.timeWindows)
        activeDays.clear(); activeDays.addAll(restriction.activeDays)
        dayChips.forEachIndexed { index, chip -> syncDayChip(chip, activeDays.contains(index + 1)) }
        renderWindows()
    }

    private fun updateAppsSummary() {
        appsSummary.text = if (selectedPackages.isEmpty()) {
            getString(R.string.restriction_pick_apps_hint)
        } else {
            getString(R.string.restriction_apps_selected, selectedPackages.size)
        }
    }

    private fun updateLimitBadge() {
        limitBadge.text = getString(R.string.restriction_limit_badge, limitMinutes)
    }

    private fun openAppSelection() {
        val intent = Intent(this, AppSelectionActivity::class.java).apply {
            putStringArrayListExtra(AppSelectionActivity.EXTRA_PRE_SELECTED, ArrayList(selectedPackages))
        }
        pickApps.launch(intent)
    }

    private fun showTimeWindowPicker(existing: TimeWindow?) {
        val startHour = existing?.startHour ?: 16
        val startMinute = existing?.startMinute ?: 0

        TimePickerDialog(this, { _, sh, sm ->
            TimePickerDialog(this, { _, eh, em ->
                val window = TimeWindow(sh, sm, eh, em)
                if (existing != null) {
                    val idx = timeWindows.indexOf(existing)
                    if (idx >= 0) timeWindows[idx] = window
                } else {
                    timeWindows.add(window)
                }
                renderWindows()
            }, existing?.endHour ?: 22, existing?.endMinute ?: 0, true).show()
        }, startHour, startMinute, true).show()
    }

    private fun renderWindows() {
        windowsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (window in timeWindows) {
            val row = inflater.inflate(R.layout.item_time_window, windowsContainer, false)
            row.findViewById<TextView>(R.id.windowRange).text = window.displayRange
            row.findViewById<View>(R.id.btnRemoveWindow).setOnClickListener {
                timeWindows.remove(window)
                renderWindows()
            }
            windowsContainer.addView(row)
        }
        findViewById<View>(R.id.noWindowsHint).visibility =
            if (timeWindows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun save() {
        val name = nameInput.text.toString().trim()

        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.err_pick_apps, Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.err_name_required, Toast.LENGTH_SHORT).show()
            return
        }

        val repository = AppRestrictionsRepository.getInstance(this)
        val appName = if (selectedPackages.size == 1) {
            com.screenguard.app.data.repository.AppInfoManager.getInstance(this)
                .getAppName(selectedPackages.first())
        } else {
            getString(R.string.restriction_group_name, selectedPackages.size)
        }

        val restriction = AppRestriction(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            packageName = selectedPackages.first(),
            appName = name,
            targetPackages = selectedPackages.toList(),
            isEnabled = true,
            isTotalBlock = totalBlockSwitch.isChecked,
            hasUsageLimit = limitSwitch.isChecked && !totalBlockSwitch.isChecked,
            limitDurationMinutes = limitMinutes,
            limitPeriod = limitPeriod,
            groupLimitType = groupLimitType,
            hasSchedule = scheduleSwitch.isChecked,
            timeWindows = timeWindows.toList(),
            activeDays = if (activeDays.isEmpty()) (1..7).toSet() else activeDays.toSet()
        )

        repository.saveRestriction(restriction)
        com.screenguard.app.service.AppBlockerService.start(this)
        Toast.makeText(this, R.string.restriction_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
