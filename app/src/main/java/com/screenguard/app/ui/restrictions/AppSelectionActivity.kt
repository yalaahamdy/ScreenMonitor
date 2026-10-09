package com.screenguard.app.ui.restrictions

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.BaseActivity
import com.screenguard.app.R
import com.screenguard.app.data.model.AppCategory
import com.screenguard.app.data.model.AppRestriction

/**
 * Multi-select app picker with search, category chips and a live
 * selection strip. Used while building restriction groups.
 */
class AppSelectionActivity : BaseActivity() {

    companion object {
        const val EXTRA_SELECTED = "extra_selected"
        const val EXTRA_PRE_SELECTED = "extra_pre_selected"
    }

    data class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val category: AppCategory
    )

    private val allEntries = mutableListOf<Entry>()
    private val selected = linkedSetOf<String>()
    private lateinit var adapter: SelectionAdapter
    private lateinit var selectionStrip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        selected.addAll(
            intent.getStringArrayListExtra(EXTRA_PRE_SELECTED) ?: emptyList()
        )

        findViewById<View>(R.id.btnBack).setOnClickListener {
            deliverResult()
        }
        findViewById<View>(R.id.btnSaveSelection).setOnClickListener {
            deliverResult()
        }
        selectionStrip = findViewById(R.id.selectionStrip)
        updateStrip()

        adapter = SelectionAdapter { entry ->
            if (selected.contains(entry.packageName)) {
                selected.remove(entry.packageName)
            } else {
                selected.add(entry.packageName)
            }
            adapter.notifyDataSetChanged()
            updateStrip()
        }

        findViewById<RecyclerView>(R.id.appsRecycler).apply {
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
            adapter = this@AppSelectionActivity.adapter
        }

        loadApps()

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter()
            }
        })

        setupCategoryChips()
    }

    private fun setupCategoryChips() {
        val container = findViewById<LinearLayout>(R.id.categoryChips)
        val inflater = LayoutInflater.from(this)
        val categories = listOf(
            AppCategory.ALL, AppCategory.MOST_USED, AppCategory.SOCIAL,
            AppCategory.MEDIA, AppCategory.GAMES, AppCategory.OTHER
        )
        for (category in categories) {
            val chip = inflater.inflate(R.layout.item_day_chip, container, false) as TextView
            chip.text = getString(category.titleRes)
            chip.setTag(R.id.category_tag, category)
            chip.setOnClickListener {
                for (i in 0 until container.childCount) {
                    val c = container.getChildAt(i) as TextView
                    c.setBackgroundResource(R.drawable.bg_chip)
                    c.setTextColor(getColor(R.color.text_secondary))
                }
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(getColor(R.color.on_gold))
                adapter.filterCategory = chip.getTag(R.id.category_tag) as AppCategory
                applyFilter()
            }
            container.addView(chip)
            if (category == AppCategory.ALL) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(getColor(R.color.on_gold))
            }
        }
    }

    private fun loadApps() {
        val pm = packageManager
        val infoManager = AppInfoManager.getInstance(this)
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launchIntent, 0)

        val packages = linkedSetOf<String>()
        resolveInfos.forEach { packages.add(it.activityInfo.packageName) }
        packages.remove(packageName)

        val mostUsedOrder = try {
            infoManager.getInstalledAppsList().take(12).map { it }
        } catch (e: Exception) {
            emptyList()
        }

        for (pkg in packages) {
            val label = infoManager.getAppName(pkg)
            val icon = try {
                pm.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
            allEntries.add(Entry(pkg, label, icon, AppRestriction.guessAppCategory(pkg)))
        }
        allEntries.sortBy { it.label.lowercase() }
        applyFilter()
    }

    private fun applyFilter() {
        val query = findViewById<EditText>(R.id.searchInput).text?.toString()?.trim() ?: ""
        adapter.submitFiltered(allEntries, query)
    }

    private fun updateStrip() {
        selectionStrip.text = getString(R.string.selection_count, selected.size)
    }

    private fun deliverResult() {
        val data = Intent().apply {
            putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
        }
        setResult(RESULT_OK, data)
        finish()
    }

    inner class SelectionAdapter(
        private val onToggle: (Entry) -> Unit
    ) : RecyclerView.Adapter<SelectionAdapter.Holder>() {

        private val visible = mutableListOf<Entry>()
        var filterCategory: AppCategory = AppCategory.ALL

        fun submitFiltered(all: List<Entry>, query: String) {
            visible.clear()
            visible.addAll(
                all.filter { entry ->
                    val matchesCategory = when (filterCategory) {
                        AppCategory.ALL -> true
                        AppCategory.MOST_USED -> true // ordered by most used below
                        else -> entry.category == filterCategory
                    }
                    val matchesQuery = query.isEmpty() ||
                            entry.label.contains(query, ignoreCase = true) ||
                            entry.packageName.contains(query, ignoreCase = true)
                    matchesCategory && matchesQuery
                }
            )
            notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.selectAppIcon)
            val name: TextView = v.findViewById(R.id.selectAppName)
            val pkg: TextView = v.findViewById(R.id.selectAppPackage)
            val check: ImageView = v.findViewById(R.id.selectAppCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_selection, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = visible[position]
            holder.name.text = entry.label
            holder.pkg.text = entry.packageName
            if (entry.icon != null) holder.icon.setImageDrawable(entry.icon)
            else holder.icon.setImageResource(R.drawable.ic_home)

            val isSelected = selected.contains(entry.packageName)
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (isSelected) 1f else 0.92f

            holder.itemView.setOnClickListener { onToggle(entry) }
        }
    }
}
