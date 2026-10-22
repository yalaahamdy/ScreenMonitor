package com.screenguard.app.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.app.BaseActivity
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.model.DownloadRecord
import com.screenguard.app.data.model.DownloadStatus
import com.screenguard.app.data.repository.DownloadLogRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * شاشة عرض وتتبع سجل التحميلات والتنزيلات على الجهاز
 * تتيح لولي الأمر مراجعة كافة الملفات المحملة والتنزيلات التي تم حظرها
 */
class DownloadHistoryActivity : BaseActivity() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var emptyView: View
    private lateinit var chipAll: TextView
    private lateinit var chipBlocked: TextView
    private lateinit var chipAllowed: TextView

    private val adapter = DownloadRecordAdapter { record ->
        showRecordDetails(record)
    }

    private var currentFilter: DownloadFilter = DownloadFilter.ALL
    private var allRecords: List<DownloadRecord> = emptyList()

    enum class DownloadFilter {
        ALL, BLOCKED, ALLOWED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_history)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearHistory).setOnClickListener { confirmClearHistory() }

        rvDownloads = findViewById(R.id.rvDownloads)
        emptyView = findViewById(R.id.emptyView)
        chipAll = findViewById(R.id.chipFilterAll)
        chipBlocked = findViewById(R.id.chipFilterBlocked)
        chipAllowed = findViewById(R.id.chipFilterAllowed)

        rvDownloads.layoutManager = LinearLayoutManager(this)
        rvDownloads.adapter = adapter

        setupFilterChips()

        lifecycleScope.launch {
            DownloadLogRepository.getInstance(this@DownloadHistoryActivity).recordsFlow.collectLatest { list ->
                allRecords = list
                applyFilter()
            }
        }
    }

    private fun setupFilterChips() {
        fun sync() {
            chipAll.setBackgroundResource(if (currentFilter == DownloadFilter.ALL) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chipBlocked.setBackgroundResource(if (currentFilter == DownloadFilter.BLOCKED) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chipAllowed.setBackgroundResource(if (currentFilter == DownloadFilter.ALLOWED) R.drawable.bg_chip_selected else R.drawable.bg_chip)

            applyFilter()
        }

        chipAll.setOnClickListener { currentFilter = DownloadFilter.ALL; sync() }
        chipBlocked.setOnClickListener { currentFilter = DownloadFilter.BLOCKED; sync() }
        chipAllowed.setOnClickListener { currentFilter = DownloadFilter.ALLOWED; sync() }

        sync()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            DownloadFilter.ALL -> allRecords
            DownloadFilter.BLOCKED -> allRecords.filter { it.status != DownloadStatus.ALLOWED }
            DownloadFilter.ALLOWED -> allRecords.filter { it.status == DownloadStatus.ALLOWED }
        }

        adapter.submitList(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvDownloads.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_history_clear)
            .setMessage(R.string.download_history_clear_confirm)
            .setPositiveButton(R.string.act_delete) { _, _ ->
                DownloadLogRepository.getInstance(this).clearAll()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun showRecordDetails(record: DownloadRecord) {
        val message = StringBuilder()
            .append("التطبيق / App: ")
            .append(record.sourceAppName.ifBlank { record.sourcePackage })
            .append("\n\n").append(getString(R.string.download_history_title)).append(": ")
            .append(record.fileName)
            .append("\n\n").append("الحجم: ").append(record.getFormattedSize())
            .append("\n\n").append("الوقت: ").append(record.getFormattedDate())
            .append("\n\n").append("الحالة: ").append(Str.get(record.status.titleRes))
            .toString()

        AlertDialog.Builder(this)
            .setTitle(record.fileName)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.act_delete) { _, _ ->
                DownloadLogRepository.getInstance(this).deleteRecord(record.id)
            }
            .show()
    }

    class DownloadRecordAdapter(
        private val onClick: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DownloadRecordAdapter.ViewHolder>() {

        private val items = mutableListOf<DownloadRecord>()

        fun submitList(newItems: List<DownloadRecord>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onClick)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
            private val tvSourceApp: TextView = itemView.findViewById(R.id.tvSourceApp)
            private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
            private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
            private val ivStatusIcon: ImageView = itemView.findViewById(R.id.ivStatusIcon)

            fun bind(record: DownloadRecord, onClick: (DownloadRecord) -> Unit) {
                val ctx = itemView.context
                tvFileName.text = record.fileName
                tvSourceApp.text = record.sourceAppName.ifBlank { record.sourcePackage }
                tvFileSize.text = record.getFormattedSize()
                tvTimestamp.text = record.getFormattedDate()

                when (record.status) {
                    DownloadStatus.BLOCKED_DELETED -> {
                        tvStatusBadge.text = Str.get(R.string.download_status_blocked)
                        tvStatusBadge.setTextColor(ctx.getColor(R.color.danger))
                        ivStatusIcon.setImageResource(R.drawable.ic_block)
                        ivStatusIcon.setColorFilter(ctx.getColor(R.color.danger))
                    }
                    DownloadStatus.INTERCEPTED -> {
                        tvStatusBadge.text = Str.get(R.string.download_status_intercepted)
                        tvStatusBadge.setTextColor(ctx.getColor(R.color.gold))
                        ivStatusIcon.setImageResource(R.drawable.ic_shield)
                        ivStatusIcon.setColorFilter(ctx.getColor(R.color.gold))
                    }
                    DownloadStatus.ALLOWED -> {
                        tvStatusBadge.text = Str.get(R.string.download_status_allowed)
                        tvStatusBadge.setTextColor(ctx.getColor(R.color.success))
                        ivStatusIcon.setImageResource(R.drawable.ic_check)
                        ivStatusIcon.setColorFilter(ctx.getColor(R.color.success))
                    }
                }

                itemView.setOnClickListener { onClick(record) }
            }
        }
    }
}
