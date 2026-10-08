package com.screenguard.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

import java.io.File

/** Immersive full-screen viewer with swipe navigation, in Midnight Prestige chrome. */
class ViewerActivity : BaseActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var tvPosition: TextView
    private var files: List<File> = emptyList()

    private val pageAdapter = object : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_viewer_page, parent, false)
            return PageHolder(v)
        }

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(files[position])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        files = ScreenshotStore.all(this)
        if (files.isEmpty()) {
            finish()
            return
        }

        pager = findViewById(R.id.pager)
        tvPosition = findViewById(R.id.tvPosition)
        pager.adapter = pageAdapter
        val start = intent.getIntExtra("position", 0).coerceIn(0, files.size - 1)
        pager.setCurrentItem(start, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePosition()
        })

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteCurrent() }
        updatePosition()
    }

    private fun updatePosition() {
        if (files.isEmpty()) return
        tvPosition.text = getString(
            R.string.viewer_position, pager.currentItem + 1, files.size
        )
    }

    private fun deleteCurrent() {
        if (files.isEmpty()) return
        val index = pager.currentItem
        val file = files[index]
        if (ScreenshotStore.delete(this, file)) {
            Toast.makeText(this, R.string.viewer_deleted, Toast.LENGTH_SHORT).show()
            files = ScreenshotStore.all(this)
            pageAdapter.notifyItemRemoved(index)
            if (files.isEmpty()) {
                finish()
                return
            }
            updatePosition()
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iv: ImageView = view.findViewById(R.id.ivPage)

        fun bind(file: File) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            val metrics = iv.resources.displayMetrics
            val targetW = metrics.widthPixels
            val targetH = metrics.heightPixels
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW ||
                bounds.outHeight / (sample * 2) >= targetH
            ) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp: Bitmap? = BitmapFactory.decodeFile(file.absolutePath, opts)
            iv.setImageBitmap(bmp)
        }
    }
}
