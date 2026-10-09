package com.screenguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.app.KeyguardManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.screenguard.app.MainActivity
import com.screenguard.app.PinManager
import com.screenguard.app.R
import com.screenguard.app.Str
import com.screenguard.app.data.repository.AppInfoManager
import com.screenguard.app.data.repository.AppRestrictionsRepository

/**
 * مدير النافذة العائمة فوق التطبيقات المحظورة
 * يدعم TYPE_ACCESSIBILITY_OVERLAY الذي يعمل فوراً وبدقة 100% دون الحاجة لأي إذن خاص
 * ويدعم TYPE_APPLICATION_OVERLAY في حال توفر صلاحية الظهور فوق التطبيقات
 */
object BlockOverlayManager {

    private var windowManager: WindowManager? = null
    private var overlayRootView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentShowingPackage: String? = null
        private set

    val isShowing: Boolean
        get() = overlayRootView != null

    /**
     * عرض بطاقة الحظر العائمة فوق التطبيق المحظور مباشرة
     */
    fun show(
        context: Context,
        packageName: String,
        appName: String,
        reason: String,
        nextAvailable: String?,
        windowBounds: Rect? = null,
        isPipMode: Boolean = false,
        onHomeAction: (() -> Unit)? = null,
        onBypassAction: ((Int) -> Unit)? = null
    ) {
        val isAccessibility = context is AccessibilityService

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (pm?.isInteractive == false || km?.isKeyguardLocked == true) {
            return
        }

        // إذا لم تكن خدمة وصول، نتحقق من إذن الظهور
        if (!isAccessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }

        mainHandler.post {
            try {
                if (pm?.isInteractive == false || km?.isKeyguardLocked == true) {
                    return@post
                }

                if (isShowing && currentShowingPackage == packageName) {
                    return@post
                }

                dismissInternal()

                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val appContext = context.applicationContext
                val appInfoManager = AppInfoManager(appContext)
                val appIcon = appInfoManager.getAppIcon(packageName)

                // 1. الحاوية الرئيسية معتمة بنسبة 100% لتغطية التطبيق بالكامل
                //    (لون كانفس ثيم منتصف الليل الفاخر نفسه المستخدم في شاشة الحجب الاحتياطية)
                val rootView = FrameLayout(context).apply {
                    setBackgroundColor(clr(context, R.color.overlay_canvas)) // تعتيم كامل 100% يحجب التطبيق المحظور خلفه
                    isClickable = true
                    isFocusable = true
                }

                // 2. كرت الحظر المركزي — سطح فاخر بحد ذهبي خفيف مطابق لـ bg_block_card
                val cardLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val pad = dpToPx(context, 24)
                    setPadding(pad, pad, pad, pad)

                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 24).toFloat()
                        setColor(clr(context, R.color.overlay_card))
                        setStroke(dpToPx(context, 1), clr(context, R.color.overlay_stroke))
                    }

                    isClickable = true
                    isFocusable = true
                }

                val cardParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    // هوامش ضيقة عمودياً/أفقياً لتناسب الشاشات الصغيرة ومنطقة تقسيم الشاشة
                    val marginH = dpToPx(context, 20)
                    val marginV = dpToPx(context, 24)
                    setMargins(marginH, marginV, marginH, marginV)
                }

                // شارة الحظر العلوية — قرمزي ناعم مشتق من overlay_danger (مطابق لشاشة الحجب الاحتياطية)
                val badgeContainer = FrameLayout(context).apply {
                    val size = dpToPx(context, 64)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dpToPx(context, 12)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(clr(context, R.color.overlay_danger_soft))
                    }
                }

                val badgeIcon = ImageView(context).apply {
                    val iconSize = dpToPx(context, 32)
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_block)
                    setColorFilter(clr(context, R.color.overlay_danger))
                }
                badgeContainer.addView(badgeIcon)
                cardLayout.addView(badgeContainer)

                // عنوان التنبيه بدون إيموجي
                val titleView = TextView(context).apply {
                    text = Str.get(R.string.overlay_title)
                    setTextColor(clr(context, R.color.overlay_danger))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    letterSpacing = 0.08f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 16))
                }
                cardLayout.addView(titleView)

                // أيقونة التطبيق المحظور
                val appIconView = ImageView(context).apply {
                    val iconSize = dpToPx(context, 58)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dpToPx(context, 10)
                    }
                    if (appIcon != null) {
                        setImageDrawable(appIcon)
                    } else {
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
                cardLayout.addView(appIconView)

                // اسم التطبيق المحظور
                val appNameView = TextView(context).apply {
                    text = appName
                    setTextColor(clr(context, R.color.overlay_text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 6))
                }
                if (isPipMode) {
                    val pipBadge = TextView(context).apply {
                        text = Str.get(R.string.overlay_pip_badge)
                        setTextColor(clr(context, R.color.overlay_accent))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        val vPad = dpToPx(context, 4)
                        val hPad = dpToPx(context, 12)
                        setPadding(hPad, vPad, hPad, vPad)
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(context, 8).toFloat()
                            setColor(clr(context, R.color.overlay_accent_container))
                            setStroke(dpToPx(context, 1), clr(context, R.color.overlay_accent_deep))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            bottomMargin = dpToPx(context, 8)
                        }
                    }
                    cardLayout.addView(pipBadge)
                }

                // سبب الحظر
                val reasonView = TextView(context).apply {
                    text = reason
                    setTextColor(clr(context, R.color.overlay_text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 16))
                }
                cardLayout.addView(reasonView)

                // صندوق موعد الإتاحة القادم إن وجد
                if (!nextAvailable.isNullOrBlank()) {
                    val nextLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val nextPad = dpToPx(context, 10)
                        setPadding(nextPad, nextPad, nextPad, nextPad)
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(context, 12).toFloat()
                            setColor(clr(context, R.color.overlay_surface_alt))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dpToPx(context, 18)
                        }
                    }

                    val nextLabel = TextView(context).apply {
                        text = Str.get(R.string.overlay_next_available)
                        setTextColor(clr(context, R.color.overlay_text_dim))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        gravity = Gravity.CENTER
                    }
                    val nextTime = TextView(context).apply {
                        text = nextAvailable
                        setTextColor(clr(context, R.color.overlay_accent))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setPadding(0, dpToPx(context, 2), 0, 0)
                    }
                    nextLayout.addView(nextLabel)
                    nextLayout.addView(nextTime)
                    cardLayout.addView(nextLayout)
                }

                // زر العودة للشاشة الرئيسية — زر ذهبي أساسي مطابق لنمط AppButton في التطبيق
                val homeButton = Button(context).apply {
                    text = Str.get(R.string.overlay_home)
                    setTextColor(clr(context, R.color.overlay_on_accent))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 14).toFloat()
                        setColor(clr(context, R.color.overlay_accent))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 52)
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                    setOnClickListener {
                        dismissInternal()
                        if (onHomeAction != null) {
                            onHomeAction.invoke()
                        } else {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(homeIntent)
                        }
                    }
                }
                cardLayout.addView(homeButton)

                // زر وميزة تخطي الحظر المؤقت المحمية برمز المرور
                val bypassContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }

                // زر إظهار خيارات التخطي المؤقت — زر نغمي بحد ذهبي مطابق لنمط AppTonalButton
                val bypassBtn = Button(context).apply {
                    text = Str.get(R.string.overlay_bypass_button)
                    setTextColor(clr(context, R.color.overlay_accent))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 14).toFloat()
                        setColor(clr(context, R.color.overlay_surface_alt))
                        setStroke(dpToPx(context, 1), clr(context, R.color.overlay_accent_hairline))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 50)
                    )
                }

                // لوحة التخطي المنظمة (الخطوة 1: ضبط المدة، الخطوة 2: تأكيد رمز المرور)
                val bypassPanel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    visibility = View.GONE
                    val pPad = dpToPx(context, 16)
                    setPadding(pPad, pPad, pPad, pPad)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 18).toFloat()
                        setColor(clr(context, R.color.overlay_surface_alt))
                        setStroke(dpToPx(context, 1), clr(context, R.color.overlay_outline))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                var selectedMinutes = 15

                // -------------------------------------------------------------
                // الخطوة الأولى: تحديد مدة التخطي (Duration Step)
                // -------------------------------------------------------------
                val durationStepLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val panelTitle = TextView(context).apply {
                    text = Str.get(R.string.overlay_bypass_button)
                    setTextColor(clr(context, R.color.overlay_accent))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 4))
                }
                durationStepLayout.addView(panelTitle)

                val panelSubtitle = TextView(context).apply {
                    text = Str.get(R.string.overlay_bypass_subtitle)
                    setTextColor(clr(context, R.color.overlay_text_faint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 10))
                }
                durationStepLayout.addView(panelSubtitle)

                // بطاقة عرض المدة المحددة بشكل بارز وأنيق
                val durationBadge = TextView(context).apply {
                    setTextColor(clr(context, R.color.overlay_accent_soft))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    val vPad = dpToPx(context, 6)
                    val hPad = dpToPx(context, 16)
                    setPadding(hPad, vPad, hPad, vPad)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 12).toFloat()
                        setColor(clr(context, R.color.overlay_accent_container))
                        setStroke(dpToPx(context, 1), clr(context, R.color.overlay_accent_deep))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }
                durationStepLayout.addView(durationBadge)

                // سلايدر السحب السريع (1 إلى 300 دقيقة)
                val seekBar = SeekBar(context).apply {
                    max = 299 // 0..299 -> 1..300
                    progress = 14 // 15 دقيقة افتراضيًا
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 32)
                    ).apply {
                        bottomMargin = dpToPx(context, 8)
                    }
                }
                durationStepLayout.addView(seekBar)

                // صف أزرار الضبط الدقيق (+ / -)
                val stepperLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }

                // شريط التمرير الأفقي للخيارات الشائعة
                val scrollPresets = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    // حشوة جانبية حتى لا تلتصق أول شريحة بحدود الشاشات الصغيرة
                    val hPad = dpToPx(context, 2)
                    setPadding(hPad, 0, hPad, 0)
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }
                val presetsLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                scrollPresets.addView(presetsLayout)

                val presetItems = listOf(
                    1 to Str.get(R.string.dur_short_1m),
                    5 to Str.get(R.string.dur_short_5m),
                    15 to Str.get(R.string.dur_short_15m),
                    30 to Str.get(R.string.dur_short_30m),
                    60 to Str.get(R.string.dur_short_1h),
                    120 to Str.get(R.string.dur_short_2h),
                    180 to Str.get(R.string.dur_short_3h),
                    300 to Str.get(R.string.dur_short_5h)
                )
                val presetButtons = mutableListOf<Pair<Int, Button>>()

                fun syncDurationUI(minutes: Int) {
                    selectedMinutes = minutes.coerceIn(1, 300)
                    val formatted = AppRestrictionsRepository.formatBypassDuration(selectedMinutes)
                    durationBadge.text = Str.get(R.string.overlay_selected_duration, formatted)

                    presetButtons.forEach { (mins, btn) ->
                        val isSel = (mins == selectedMinutes)
                        btn.background = GradientDrawable().apply {
                            cornerRadius = dpToPx(context, 8).toFloat()
                            // محدد: حاوية ذهبية بحد ذهبي (مطابق لـ bg_chip_selected)
                            // غير محدد: خلفية مفاتيح بحد ناعم
                            if (isSel) {
                                setColor(clr(context, R.color.overlay_accent_container))
                                setStroke(dpToPx(context, 1), clr(context, R.color.overlay_accent))
                            } else {
                                setColor(clr(context, R.color.overlay_key_bg))
                                setStroke(dpToPx(context, 1), clr(context, R.color.overlay_outline))
                            }
                        }
                        btn.setTextColor(
                            clr(
                                context,
                                if (isSel) R.color.overlay_accent else R.color.overlay_text_dim
                            )
                        )
                    }
                }

                // أزرار الضبط الدقيق
                val stepDeltas = listOf(
                    -15 to Str.get(R.string.dur_short_minus_15m),
                    -1 to Str.get(R.string.dur_short_minus_1m),
                    1 to Str.get(R.string.dur_short_plus_1m),
                    15 to Str.get(R.string.dur_short_plus_15m)
                )
                stepDeltas.forEach { (delta, label) ->
                    val stepBtn = Button(context).apply {
                        text = label
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(clr(context, R.color.overlay_text))
                        background = GradientDrawable().apply {
                            cornerRadius = dpToPx(context, 8).toFloat()
                            setColor(clr(context, R.color.overlay_key_bg))
                            setStroke(dpToPx(context, 1), clr(context, R.color.overlay_outline))
                        }
                        layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, 40), 1f).apply {
                            val m = dpToPx(context, 3)
                            setMargins(m, 0, m, 0)
                        }
                        setOnClickListener {
                            val newMins = (selectedMinutes + delta).coerceIn(1, 300)
                            seekBar.progress = newMins - 1
                            syncDurationUI(newMins)
                        }
                    }
                    stepperLayout.addView(stepBtn)
                }
                durationStepLayout.addView(stepperLayout)

                // تعبئة أزرار الخيارات السريعة
                presetItems.forEach { (mins, label) ->
                    val btn = Button(context).apply {
                        text = label
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dpToPx(context, 40)
                        ).apply {
                            val m = dpToPx(context, 3)
                            setMargins(m, 0, m, 0)
                        }
                        setOnClickListener {
                            seekBar.progress = mins - 1
                            syncDurationUI(mins)
                        }
                    }
                    presetButtons.add(mins to btn)
                    presetsLayout.addView(btn)
                }
                durationStepLayout.addView(scrollPresets)

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            syncDurationUI(progress + 1)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                syncDurationUI(15)

                // تنبيه الأمان
                val securityNotice = TextView(context).apply {
                    text = Str.get(R.string.overlay_bypass_security_note)
                    setTextColor(clr(context, R.color.overlay_text_faint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                    val nPad = dpToPx(context, 6)
                    setPadding(nPad, nPad, nPad, nPad)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 8).toFloat()
                        setColor(clr(context, R.color.overlay_canvas))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }
                durationStepLayout.addView(securityNotice)

                // صف أزرار الخطوة الأولى (إلغاء ومتابعة)
                val durationActionsLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 44)
                    )
                }

                val cancelDurationBtn = Button(context).apply {
                    text = Str.get(R.string.action_cancel)
                    setTextColor(clr(context, R.color.overlay_text_faint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 10).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener {
                        bypassPanel.visibility = View.GONE
                        bypassBtn.visibility = View.VISIBLE
                    }
                }

                val proceedToPinBtn = Button(context).apply {
                    text = Str.get(R.string.overlay_continue_to_pin)
                    setTextColor(clr(context, R.color.overlay_on_accent))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 10).toFloat()
                        setColor(clr(context, R.color.overlay_accent))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f)
                }

                durationActionsLayout.addView(cancelDurationBtn)
                durationActionsLayout.addView(proceedToPinBtn)
                durationStepLayout.addView(durationActionsLayout)

                // -------------------------------------------------------------
                // الخطوة الثانية: التحقق من رمز مرور التطبيق (PIN Verification Step)
                // -------------------------------------------------------------
                val pinStepLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val pinTitle = TextView(context).apply {
                    text = Str.get(R.string.overlay_pin_step_title)
                    setTextColor(clr(context, R.color.overlay_accent))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 4))
                }
                pinStepLayout.addView(pinTitle)

                val pinSubtitle = TextView(context).apply {
                    setTextColor(clr(context, R.color.overlay_text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(context, 10))
                }
                pinStepLayout.addView(pinSubtitle)

                // مؤشر الدوائر الأربعة لرمز المرور
                val dotsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 8)
                    }
                }
                val dotViews = (0..3).map {
                    View(context).apply {
                        val dotSize = dpToPx(context, 14)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            val m = dpToPx(context, 8)
                            setMargins(m, 0, m, 0)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(clr(context, R.color.overlay_outline))
                        }
                    }
                }
                dotViews.forEach { dotsContainer.addView(it) }
                pinStepLayout.addView(dotsContainer)

                // نص رسالة الخطأ — قرمزي ناعم بتباين 7:1 على البطاقة (بدلاً من #EF4444 بتباين 4.2:1)
                val pinErrorView = TextView(context).apply {
                    setTextColor(clr(context, R.color.overlay_danger))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    setPadding(0, 0, 0, dpToPx(context, 6))
                }
                pinStepLayout.addView(pinErrorView)

                // لوحة المفاتيح الرقمية المدمجة
                val keypadContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, 10)
                    }
                }

                var enteredPin = ""
                var isCheckingPin = false

                fun updateDots(isError: Boolean = false) {
                    dotViews.forEachIndexed { index, dot ->
                        val gd = dot.background as? GradientDrawable ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
                        if (isError) {
                            gd.setColor(clr(context, R.color.overlay_danger))
                        } else if (index < enteredPin.length) {
                            gd.setColor(clr(context, R.color.overlay_accent))
                        } else {
                            gd.setColor(clr(context, R.color.overlay_outline))
                        }
                        dot.background = gd
                    }
                }

                fun onDigitEntered(digit: String) {
                    if (isCheckingPin || enteredPin.length >= 4) return
                    pinErrorView.visibility = View.GONE
                    enteredPin += digit
                    updateDots()

                    if (enteredPin.length == 4) {
                        isCheckingPin = true
                        if (PinManager.verifyWithLockout(context, enteredPin)) {
                            // تم التحقق بنجاح وتأكيد رمز المرور
                            val repo = AppRestrictionsRepository.getInstance(context)
                            repo.setTemporaryBypass(packageName, selectedMinutes)
                            val formatted = AppRestrictionsRepository.formatBypassDuration(selectedMinutes)
                            Toast.makeText(
                                context,
                                Str.get(R.string.overlay_bypass_success, appName, formatted),
                                Toast.LENGTH_SHORT
                            ).show()
                            dismissInternal()
                            onBypassAction?.invoke(selectedMinutes)
                        } else {
                            // رمز المرور غير صحيح
                            val remainingSec = PinManager.getRemainingLockoutSeconds(context)
                            val errorMsg = if (remainingSec > 0) {
                                Str.get(R.string.overlay_pin_locked_out, remainingSec)
                            } else {
                                Str.get(R.string.overlay_pin_wrong)
                            }
                            pinErrorView.text = errorMsg
                            pinErrorView.visibility = View.VISIBLE
                            updateDots(isError = true)

                            mainHandler.postDelayed({
                                enteredPin = ""
                                updateDots(isError = false)
                                isCheckingPin = false
                            }, 450)
                        }
                    }
                }

                fun onBackspace() {
                    if (!isCheckingPin && enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                        pinErrorView.visibility = View.GONE
                        updateDots()
                    }
                }

                fun onClear() {
                    if (!isCheckingPin) {
                        enteredPin = ""
                        pinErrorView.visibility = View.GONE
                        updateDots()
                    }
                }

                val keypadRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(Str.get(R.string.keypad_clear), "0", Str.get(R.string.keypad_delete))
                )

                keypadRows.forEach { rowKeys ->
                    val rowLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dpToPx(context, 6)
                        }
                    }
                    rowKeys.forEach { key ->
                        val keyBtn = Button(context).apply {
                            text = key
                            setTextColor(
                                clr(
                                    context,
                                    if (key == Str.get(R.string.keypad_clear) || key == Str.get(R.string.keypad_delete))
                                        R.color.overlay_text_dim else R.color.overlay_text
                                )
                            )
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.length > 1) 12f else 16f)
                            typeface = Typeface.DEFAULT_BOLD
                            // مطابق لنمط KeypadDigit: خلفية sgKeyBg وحد sgOutlineStrong، بارتفاع 48dp
                            // لتلبية الحد الأدنى لهدف اللمس (48dp) من إرشادات Material
                            background = GradientDrawable().apply {
                                cornerRadius = dpToPx(context, 10).toFloat()
                                setColor(clr(context, R.color.overlay_key_bg))
                                setStroke(dpToPx(context, 1), clr(context, R.color.overlay_stroke))
                            }
                            layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, 48), 1f).apply {
                                val m = dpToPx(context, 3)
                                setMargins(m, 0, m, 0)
                            }
                            setOnClickListener {
                                when (key) {
                                    Str.get(R.string.keypad_delete) -> onBackspace()
                                    Str.get(R.string.keypad_clear) -> onClear()
                                    else -> onDigitEntered(key)
                                }
                            }
                        }
                        rowLayout.addView(keyBtn)
                    }
                    keypadContainer.addView(rowLayout)
                }
                pinStepLayout.addView(keypadContainer)

                // صف أزرار التنقل في شاشة التحقق
                val pinNavLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 40)
                    )
                }

                val backToDurationBtn = Button(context).apply {
                    text = Str.get(R.string.overlay_edit_duration)
                    setTextColor(clr(context, R.color.overlay_text_faint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 8).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener {
                        pinStepLayout.visibility = View.GONE
                        durationStepLayout.visibility = View.VISIBLE
                        enteredPin = ""
                        pinErrorView.visibility = View.GONE
                        updateDots()
                    }
                }

                val cancelPinBtn = Button(context).apply {
                    text = Str.get(R.string.action_cancel)
                    setTextColor(clr(context, R.color.overlay_text_faint))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 8).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener {
                        bypassPanel.visibility = View.GONE
                        bypassBtn.visibility = View.VISIBLE
                        pinStepLayout.visibility = View.GONE
                        durationStepLayout.visibility = View.VISIBLE
                        enteredPin = ""
                        pinErrorView.visibility = View.GONE
                        updateDots()
                    }
                }

                pinNavLayout.addView(backToDurationBtn)
                pinNavLayout.addView(cancelPinBtn)
                pinStepLayout.addView(pinNavLayout)

                // ربط الانتقال من المدة إلى رمز المرور
                proceedToPinBtn.setOnClickListener {
                    if (!PinManager.hasPin(context)) {
                        // في حال لم يقم بتعيين رمز مرور في التطبيق، التخطي مباشرة
                        val repo = AppRestrictionsRepository.getInstance(context)
                        repo.setTemporaryBypass(packageName, selectedMinutes)
                        val formatted = AppRestrictionsRepository.formatBypassDuration(selectedMinutes)
                        Toast.makeText(
                            context,
                            Str.get(R.string.overlay_bypass_success, appName, formatted),
                            Toast.LENGTH_SHORT
                        ).show()
                        dismissInternal()
                        onBypassAction?.invoke(selectedMinutes)
                    } else {
                        // الانتقال لخطوة إدخال رمز المرور وتحديث النص
                        val formatted = AppRestrictionsRepository.formatBypassDuration(selectedMinutes)
                        pinSubtitle.text = Str.get(R.string.overlay_pin_subtitle, formatted)
                        durationStepLayout.visibility = View.GONE
                        pinStepLayout.visibility = View.VISIBLE
                        enteredPin = ""
                        pinErrorView.visibility = View.GONE
                        updateDots()
                    }
                }

                bypassPanel.addView(durationStepLayout)
                bypassPanel.addView(pinStepLayout)

                bypassBtn.setOnClickListener {
                    bypassBtn.visibility = View.GONE
                    bypassPanel.visibility = View.VISIBLE
                    durationStepLayout.visibility = View.VISIBLE
                    pinStepLayout.visibility = View.GONE
                    syncDurationUI(selectedMinutes)
                }

                bypassContainer.addView(bypassBtn)
                bypassContainer.addView(bypassPanel)
                cardLayout.addView(bypassContainer)

                // زر إدارة القيود — زر محدد بحد مطابق لحد البطاقة
                val settingsButton = Button(context).apply {
                    text = Str.get(R.string.overlay_manage_restrictions)
                    setTextColor(clr(context, R.color.overlay_text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 14).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(context, 1), clr(context, R.color.overlay_stroke))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 48)
                    )
                    setOnClickListener {
                        dismissInternal()
                        val mainIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(mainIntent)
                    }
                }
                cardLayout.addView(settingsButton)

                // النقر على الخلفية المعتمة يغلق النافذة ويأخذ المستخدم للشاشة الرئيسية
                rootView.setOnClickListener {
                    dismissInternal()
                    if (onHomeAction != null) {
                        onHomeAction.invoke()
                    } else {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                    }
                }

                // اعتراض زر الرجوع (Back) لمنع تجاوز شاشة الحظر
                rootView.isFocusableInTouchMode = true
                rootView.requestFocus()
                rootView.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismissInternal()
                        if (onHomeAction != null) {
                            onHomeAction.invoke()
                        } else {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(homeIntent)
                        }
                        true
                    } else {
                        false
                    }
                }

                // وضع الكرت داخل ScrollView لضمان ظهوره كاملاً دون اقتصاص حتى في الشاشات الصغيرة أو المنقسمة.
                // إطار توسيط وسيط: مع fillViewport يتم مدّ الإطار (وليس البطاقة) لملء الشاشة،
                // فتبقى البطاقة بالحجم الطبيعي وموسّطة، وعند تجاوزها الشاشة تتمرر بدل قصّها.
                val centeringFrame = FrameLayout(context)
                centeringFrame.addView(cardLayout, cardParams)

                val scrollView = ScrollView(context).apply {
                    isFillViewport = true
                    isVerticalScrollBarEnabled = false
                }
                scrollView.addView(
                    centeringFrame,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                rootView.addView(
                    scrollView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                // اختيار نوع نافذة النظام الأمثل
                val windowType = if (isAccessibility) {
                    // نافذة الوصول: تعمل فورا وتظهر فوق أي تطبيق بدون الحاجة لإذن الظهور الخاص
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                // مطابقة حجم وموقع نافذة التطبيق المفتوح لتغطيته بالكامل (مع فرض ملء الشاشة إذا كان في وضع PiP)
                val metrics = context.resources.displayMetrics
                val isSplitScreen = !isPipMode && windowBounds != null &&
                        windowBounds.width() > 0 && windowBounds.height() > 0 &&
                        (windowBounds.height() < (metrics.heightPixels * 0.88f) ||
                         windowBounds.width() < (metrics.widthPixels * 0.88f))

                val params = if (isSplitScreen) {
                    WindowManager.LayoutParams(
                        windowBounds!!.width(),
                        windowBounds.height(),
                        windowBounds.left,
                        windowBounds.top,
                        windowType,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                } else {
                    WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        windowType,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                }

                wm.addView(rootView, params)
                overlayRootView = rootView
                currentShowingPackage = packageName
            } catch (e: Exception) {
                // إذا فشل إظهار النافذة العائمة والشاشة مغلقة أو مقفلة، نكتفي بالرجوع للرئيسية دون تشغيل شاشات قد توقظ الهاتف
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (pm?.isInteractive == false || km?.isKeyguardLocked == true) {
                    try {
                        onHomeAction?.invoke()
                    } catch (ignored: Exception) {}
                    return@post
                }

                // خطة أمان بديلة: إذا تعذر إضافة النافذة العائمة فوق هذا التطبيق، ننقل المستخدم للشاشة الرئيسية
                try {
                    if (onHomeAction != null) {
                        onHomeAction.invoke()
                    } else {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                    }
                } catch (ex: Exception) {}
            }
        }
    }

    /**
     * إغلاق النافذة العائمة
     */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissInternal()
        } else {
            mainHandler.post { dismissInternal() }
        }
    }

    private fun dismissInternal() {
        try {
            overlayRootView?.let { view ->
                windowManager?.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            try {
                overlayRootView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (ex: Exception) {}
        } finally {
            overlayRootView = null
            currentShowingPackage = null
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /** Resolve a pinned lock-surface color resource (Midnight Prestige tokens). */
    private fun clr(context: Context, res: Int): Int = context.getColor(res)
}
