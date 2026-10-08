package com.screenguard.app

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView


/**
 * PIN gate in front of the capture gallery. Four dots fill with gold as the
 * code is typed and submit automatically on the fourth digit.
 */
class PinLockActivity : BaseActivity() {

    private lateinit var dots: PinDotsView
    private lateinit var tvError: TextView
    private var entry = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_lock)

        dots = findViewById(R.id.pinDots)
        tvError = findViewById(R.id.tvError)

        val digitIds = intArrayOf(
            R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
            R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9
        )
        for (id in digitIds) {
            findViewById<Button>(id).setOnClickListener {
                if (entry.length < 4) {
                    entry.append((it as Button).text)
                    dots.filled = entry.length
                    if (entry.length == 4) verify()
                }
            }
        }
        findViewById<ImageButton>(R.id.keyBackspace).setOnClickListener {
            if (entry.isNotEmpty()) {
                entry.deleteCharAt(entry.length - 1)
                dots.filled = entry.length
                tvError.visibility = TextView.INVISIBLE
            }
        }
    }

    private fun verify() {
        val pin = entry.toString()
        if (PinManager.verify(this, pin)) {
            dots.state = PinDotsView.STATE_SUCCESS
            startActivity(Intent(this, GalleryActivity::class.java))
            finish()
        } else {
            dots.state = PinDotsView.STATE_ERROR
            tvError.visibility = TextView.VISIBLE
            vibrate()
            dots.postDelayed({
                entry.setLength(0)
                dots.reset()
                tvError.visibility = TextView.INVISIBLE
            }, 550)
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }
}
