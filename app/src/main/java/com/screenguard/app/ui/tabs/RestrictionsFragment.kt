package com.screenguard.app.ui.tabs

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.screenguard.app.CrashLogger
import com.screenguard.app.data.repository.AppRestrictionsRepository
import com.screenguard.app.R
import com.screenguard.app.ScreenGuardAccessibilityService
import com.screenguard.app.UiSafety
import com.screenguard.app.data.model.AppRestriction
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.service.BlockOverlayManager
import com.screenguard.app.ui.restrictions.AddEditRestrictionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Restrictions tab: control-center status card plus the list of blocking
 * rules with live per-app consumption, bypass countdowns and quick actions.
 */
class RestrictionsFragment : Fragment() {

    private lateinit var adapter: RestrictionsAdapter
    private val repository: AppRestrictionsRepository by lazy {
        AppRestrictionsRepository.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        inflater.inflate(R.layout.fragment_restrictions, container, false)
    } catch (t: Throwable) {
        // v3.1.2: never return null — a built error card instead of a blank tab
        CrashLogger.log(t, "RestrictionsFragment.inflate")
        UiSafety.errorCard(requireContext(), "RestrictionsFragment.inflate", t)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiSafety.guard("RestrictionsFragment.onViewCreated") { onViewCreatedSafe(view) }
    }

    private fun onViewCreatedSafe(view: View) {
        if (UiSafety.isErrorCard(view)) return
        adapter = RestrictionsAdapter(
            onToggle = { restriction, enabled ->
                repository.toggleRestriction(restriction.id, enabled)
            },
            onEdit = { restriction ->
                val intent = Intent(requireContext(), AddEditRestrictionActivity::class.java).apply {
                    putExtra(AddEditRestrictionActivity.EXTRA_RESTRICTION_ID, restriction.id)
                }
                startActivity(intent)
            },
            onDelete = { restriction ->
                confirmDelete(restriction)
            },
            onCancelBypass = { restriction ->
                restriction.allPackages.forEach { repository.clearTemporaryBypass(it) }
                refresh()
            }
        )

        view.findViewById<RecyclerView>(R.id.restrictionsList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RestrictionsFragment.adapter
            isNestedScrollingEnabled = false
        }

        view.findViewById<View>(R.id.fabAddRestriction).setOnClickListener {
            startActivity(Intent(requireContext(), AddEditRestrictionActivity::class.java))
        }
        view.findViewById<View>(R.id.rowAccessibilityStatus).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.findViewById<View>(R.id.rowOverlayStatus).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UiSafety.guard("RestrictionsFragment.onResume") { refresh() }
    }

    private fun confirmDelete(restriction: AppRestriction) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restriction_delete)
            .setMessage(getString(R.string.restriction_delete_confirm, restriction.appName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.deleteRestriction(restriction.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val view = view ?: return
        val ctx = requireContext()

        val accessibilityUp = ScreenGuardAccessibilityService.isRunning() ||
                ScreenGuardAccessibilityService.isEnabledInSettings(ctx)
        val overlayUp = try {
            Settings.canDrawOverlays(ctx)
        } catch (e: Exception) {
            false
        }

        view.findViewById<TextView>(R.id.tvAccessibilityStatus).text =
            getString(
                if (accessibilityUp) R.string.restrictions_accessibility_ok
                else R.string.restrictions_accessibility_missing
            )
        view.findViewById<TextView>(R.id.tvOverlayStatus).text =
            getString(
                if (overlayUp) R.string.restrictions_overlay_ok
                else R.string.restrictions_overlay_missing
            )

        val restrictions = UiSafety.guardElse(
            "RestrictionsFragment.loadRules", emptyList<AppRestriction>()
        ) { repository.getAllRestrictions() }
        val enabledCount = restrictions.count { it.isEnabled }
        view.findViewById<TextView>(R.id.restrictionsActiveCount).text =
            getString(R.string.restrictions_active_summary, enabledCount, restrictions.size)

        adapter.submit(restrictions)

        view.findViewById<RecyclerView>(R.id.restrictionsList).visibility =
            if (restrictions.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<LinearLayout>(R.id.restrictionsEmpty).visibility =
            if (restrictions.isEmpty()) View.VISIBLE else View.GONE

        // Start / stop the enforcement service as needed
        if (enabledCount > 0) {
            com.screenguard.app.service.AppBlockerService.start(ctx)
        }
    }
}

/** Adapter rendering each restriction rule with live evaluation. */
class RestrictionsAdapter(
    private val onToggle: (AppRestriction, Boolean) -> Unit,
    private val onEdit: (AppRestriction) -> Unit,
    private val onDelete: (AppRestriction) -> Unit,
    private val onCancelBypass: (AppRestriction) -> Unit
) : RecyclerView.Adapter<RestrictionsAdapter.Holder>() {

    private val items = mutableListOf<AppRestriction>()

    fun submit(list: List<AppRestriction>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.restrictionName)
        val badges: TextView = v.findViewById(R.id.restrictionBadges)
        val toggle: MaterialSwitch = v.findViewById(R.id.restrictionToggle)
        val appsContainer: LinearLayout = v.findViewById(R.id.restrictionAppsContainer)
        val bypassInfo: TextView = v.findViewById(R.id.restrictionBypassInfo)
        val btnClearBypass: View = v.findViewById(R.id.btnClearBypass)
        val btnEdit: View = v.findViewById(R.id.btnEditRestriction)
        val btnDelete: View = v.findViewById(R.id.btnDeleteRestriction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_restriction, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ctx = holder.itemView.context
        val restriction = items[position]

        val appName = if (restriction.allPackages.size == 1) {
            AppInfoManager.getInstance(ctx).getAppName(restriction.allPackages.first())
        } else {
            ctx.getString(R.string.restriction_group_name, restriction.allPackages.size)
        }
        holder.name.text = restriction.appName.ifBlank { appName }

        // Feature badges
        val badges = mutableListOf<String>()
        if (restriction.isTotalBlock) badges.add(ctx.getString(R.string.badge_total_block))
        if (restriction.hasUsageLimit) {
            badges.add(
                ctx.getString(
                    R.string.badge_limit,
                    restriction.limitDurationMinutes,
                    ctx.getString(restriction.limitPeriod.titleRes)
                )
            )
        }
        if (restriction.hasSchedule) badges.add(ctx.getString(R.string.badge_schedule))
        holder.badges.text = badges.joinToString(" · ")

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = restriction.isEnabled
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            onToggle(restriction, checked)
        }

        holder.btnEdit.setOnClickListener { onEdit(restriction) }
        holder.btnDelete.setOnClickListener { onDelete(restriction) }

        // Live evaluation per app
        val repo = AppRestrictionsRepository.getInstance(ctx)
        val appInfo = AppInfoManager.getInstance(ctx)
        holder.appsContainer.removeAllViews()
        val inflater = LayoutInflater.from(ctx)

        var blockedCount = 0
        var anyBypass = false

        for (pkg in restriction.allPackages) {
            val row = inflater.inflate(R.layout.item_restriction_app_row, holder.appsContainer, false)
            val icon = row.findViewById<ImageView>(R.id.miniAppIcon)
            val nameView = row.findViewById<TextView>(R.id.miniAppName)
            val progress = row.findViewById<ProgressBar>(R.id.miniAppProgress)
            val status = row.findViewById<TextView>(R.id.miniAppStatus)

            nameView.text = appInfo.getAppName(pkg)
            val appIcon: Drawable? = try {
                ctx.packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
            if (appIcon != null) icon.setImageDrawable(appIcon)

            val bypassed = repo.isPackageBypassed(pkg)
            if (bypassed) anyBypass = true
            val remainingSec = repo.getTemporaryBypassRemainingSeconds(pkg)

            val consumed = repo.calculateConsumedMinutes(ctx, restriction, pkg)
            val evaluation = repo.evaluateRestriction(restriction, consumed, Calendar.getInstance(), pkg)
            if (evaluation.isBlocked) blockedCount++

            if (restriction.hasUsageLimit && !restriction.isTotalBlock) {
                progress.progress = if (restriction.limitDurationMinutes > 0) {
                    (consumed * 100 / restriction.limitDurationMinutes).coerceIn(0, 100)
                } else 0
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }

            status.text = when {
                bypassed -> ctx.getString(
                    R.string.restriction_bypass_remaining,
                    AppRestrictionsRepository.formatBypassDuration(
                        (((remainingSec + 59) / 60).toInt().coerceAtLeast(1))
                    )
                )
                evaluation.isBlocked -> ctx.getString(R.string.restriction_status_blocked)
                restriction.hasUsageLimit -> ctx.getString(
                    R.string.restriction_status_usage, consumed, restriction.limitDurationMinutes
                )
                else -> ctx.getString(R.string.restriction_status_active)
            }
            status.setTextColor(
                ctx.getColor(
                    when {
                        bypassed -> R.color.warning
                        evaluation.isBlocked -> R.color.danger
                        else -> R.color.success
                    }
                )
            )

            holder.appsContainer.addView(row)
        }

        if (restriction.allPackages.size > 1) {
            holder.bypassInfo.visibility = View.VISIBLE
            holder.bypassInfo.text =
                ctx.getString(R.string.restriction_partial_summary, blockedCount, restriction.allPackages.size)
        } else {
            holder.bypassInfo.visibility = View.GONE
        }

        holder.btnClearBypass.visibility = if (anyBypass) View.VISIBLE else View.GONE
        holder.btnClearBypass.setOnClickListener { onCancelBypass(restriction) }
    }
}
