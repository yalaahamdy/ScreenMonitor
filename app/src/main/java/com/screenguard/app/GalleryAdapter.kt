package com.screenguard.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Grid adapter for the capture library with a small LRU thumbnail cache. */
class GalleryAdapter(
    private var files: List<File>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 60
    }

    fun submit(items: List<File>) {
        files = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        holder.bind(file, position, onClick)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val iv: ImageView = view.findViewById(R.id.ivTile)

        fun bind(file: File, position: Int, onClick: (Int) -> Unit) {
            iv.setImageBitmap(thumbnail(file))
            itemView.setOnClickListener { onClick(position) }
        }
    }

    private fun thumbnail(file: File): Bitmap? {
        synchronized(cache) { cache[file.absolutePath] }?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val target = 256
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target &&
            bounds.outHeight / (sample * 2) >= target
        ) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
        if (bmp != null) {
            synchronized(cache) { cache[file.absolutePath] = bmp }
        }
        return bmp
    }
}
