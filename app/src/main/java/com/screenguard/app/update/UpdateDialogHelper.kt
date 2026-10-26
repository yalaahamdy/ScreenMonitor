package com.screenguard.app.update

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.screenguard.app.BuildConfig
import com.screenguard.app.PinManager
import com.screenguard.app.R
import kotlinx.coroutines.launch

/**
 * مساعد واجهة المستخدم لفحص وعرض وتحميل التحديثات التلقائية واليدوية
 */
object UpdateDialogHelper {

    fun checkAndPromptIfAvailable(activity: AppCompatActivity, manualCheck: Boolean) {
        if (!manualCheck) {
            if (!PinManager.isAutoUpdateEnabled(activity)) return
            if (!AppUpdateManager.shouldRunAutoCheck(activity)) return
        } else {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }

        activity.lifecycleScope.launch {
            val result = AppUpdateManager.checkForUpdate()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            result.onSuccess { releaseInfo ->
                if (releaseInfo != null) {
                    showUpdateAvailableDialog(activity, releaseInfo)
                } else if (manualCheck) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { err ->
                if (manualCheck) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_check_failed, err.localizedMessage ?: "Error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(activity: AppCompatActivity, releaseInfo: ReleaseInfo) {
        val notes = releaseInfo.releaseNotes.ifBlank { releaseInfo.releaseName }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, releaseInfo.tagName))
            .setMessage(activity.getString(R.string.update_available_msg, releaseInfo.tagName, notes))
            .setPositiveButton(R.string.update_btn_now) { _, _ ->
                startDownloadAndInstall(activity, releaseInfo)
            }
            .setNegativeButton(R.string.update_btn_later, null)
            .show()
    }

    private fun startDownloadAndInstall(activity: AppCompatActivity, releaseInfo: ReleaseInfo) {
        val pad = (22 * activity.resources.displayMetrics.density).toInt()
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val tvProgress = TextView(activity).apply {
            text = activity.getString(R.string.update_downloading_progress, 0)
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(progressBar)
            addView(tvProgress)
        }

        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, releaseInfo.tagName))
            .setView(container)
            .setCancelable(false)
            .create()
        progressDialog.show()

        activity.lifecycleScope.launch {
            val downloadResult = AppUpdateManager.downloadUpdateApk(activity, releaseInfo) { pct ->
                progressBar.progress = pct
                tvProgress.text = activity.getString(R.string.update_downloading_progress, pct)
            }
            if (progressDialog.isShowing) {
                try { progressDialog.dismiss() } catch (_: Exception) {}
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch

            downloadResult.onSuccess { apkFile ->
                try {
                    AppUpdateManager.installDownloadedApk(activity, apkFile)
                } catch (e: Exception) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_download_failed, e.localizedMessage ?: "Install error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { err ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_download_failed, err.localizedMessage ?: "Network error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
