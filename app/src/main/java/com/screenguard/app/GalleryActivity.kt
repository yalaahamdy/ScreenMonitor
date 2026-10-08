package com.screenguard.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Midnight gallery: every capture is one tap away, guarded by the PIN. */
class GalleryActivity : BaseActivity() {

    private lateinit var adapter: GalleryAdapter
    private lateinit var tvCount: TextView
    private lateinit var emptyState: View
    private lateinit var rv: RecyclerView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        tvCount = findViewById(R.id.tvCount)
        emptyState = findViewById(R.id.emptyState)
        rv = findViewById(R.id.rvGrid)
        rv.layoutManager = GridLayoutManager(this, 3)

        adapter = GalleryAdapter(files) { position -> openViewer(position) }
        rv.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDeleteAll).setOnClickListener { confirmDeleteAll() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        files = ScreenshotStore.all(this)
        adapter.submit(files)
        tvCount.text = getString(R.string.gallery_count, files.size)
        emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openViewer(position: Int) {
        startActivity(
            android.content.Intent(this, ViewerActivity::class.java)
                .putExtra("position", position)
        )
    }

    private fun confirmDeleteAll() {
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_text, files.size))
            .setPositiveButton(R.string.act_delete) { _, _ ->
                val n = ScreenshotStore.deleteAll(this)
                Toast.makeText(
                    this, getString(R.string.deleted_toast, n), Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }
}
