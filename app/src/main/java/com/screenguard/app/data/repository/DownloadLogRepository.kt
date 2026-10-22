package com.screenguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.screenguard.app.data.model.DownloadRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * مستودع لتسجيل وحفظ وتتبع جميع عمليات التنزيل والتحميل على الجهاز
 */
class DownloadLogRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _recordsFlow = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val recordsFlow: StateFlow<List<DownloadRecord>> = _recordsFlow.asStateFlow()

    companion object {
        private const val PREFS_NAME = "screenguard_downloads_prefs"
        private const val KEY_DOWNLOADS_JSON = "downloads_json"
        private const val MAX_RECORDS = 300

        @Volatile
        private var instance: DownloadLogRepository? = null

        fun getInstance(context: Context): DownloadLogRepository {
            return instance ?: synchronized(this) {
                instance ?: DownloadLogRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadRecords()
    }

    @Synchronized
    fun loadRecords(): List<DownloadRecord> {
        val jsonString = prefs.getString(KEY_DOWNLOADS_JSON, null) ?: "[]"
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<DownloadRecord>()
            for (i in 0 until jsonArray.length()) {
                jsonArray.optJSONObject(i)?.let {
                    list.add(DownloadRecord.fromJsonObject(it))
                }
            }
            // Sort by latest first
            list.sortByDescending { it.timestamp }
            _recordsFlow.value = list
            list
        } catch (e: Exception) {
            _recordsFlow.value = emptyList()
            emptyList()
        }
    }

    @Synchronized
    private fun persist(list: List<DownloadRecord>) {
        val cappedList = if (list.size > MAX_RECORDS) list.take(MAX_RECORDS) else list
        val jsonArray = JSONArray()
        cappedList.forEach { jsonArray.put(it.toJsonObject()) }
        prefs.edit().putString(KEY_DOWNLOADS_JSON, jsonArray.toString()).apply()
        _recordsFlow.value = cappedList
    }

    @Synchronized
    fun logRecord(record: DownloadRecord) {
        val current = loadRecords().toMutableList()
        // Deduplicate if identical path/name logged within 2 seconds
        val duplicateIndex = current.indexOfFirst {
            it.fileName == record.fileName &&
            it.fileSize == record.fileSize &&
            Math.abs(it.timestamp - record.timestamp) < 2000L
        }
        if (duplicateIndex >= 0) {
            // Update the existing record if status was upgraded (e.g. to BLOCKED_DELETED)
            current[duplicateIndex] = record
        } else {
            current.add(0, record)
        }
        persist(current)
    }

    @Synchronized
    fun deleteRecord(id: String) {
        val current = loadRecords().filter { it.id != id }
        persist(current)
    }

    @Synchronized
    fun clearAll() {
        persist(emptyList())
    }

    fun getAllRecords(): List<DownloadRecord> = loadRecords()
}
