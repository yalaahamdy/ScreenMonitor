<p align="center">
  <img src="docs/assets/icon.jpg" width="120" height="120" style="border-radius: 24px;" alt="ScreenMonitor Icon" />
</p>

<h1 align="center">ScreenMonitor</h1>

<p align="center">
  <strong>تطبيق أندرويد حديث، خفيف واحترافي لمراقبة شاشة الهاتف دورياً وحفظ اللقطات محلياً بأعلى درجات الأمان والخصوصية.</strong><br>
  <em>A modern, lightweight, privacy-first Android application for periodic screen capture and local monitoring.</em>
</p>

<p align="center">
  <a href="https://github.com/yalaahamdy/ScreenMonitor/releases/latest"><img src="https://img.shields.io/badge/Release-v1.1.0-brightgreen.svg" alt="Latest Release" /></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android_7.0%2B_(API_24%2B)-3DDC84?logo=android&logoColor=white" alt="Platform" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Privacy-100%25_Offline-success.svg" alt="Privacy" />
</p>

---

## 📖 جدول المحتويات (Table of Contents)
- [نظرة عامة (Overview)](#-نظرة-عامة-overview)
- [المميزات الرئيسية (Key Features)](#-المميزات-الرئيسية-key-features)
- [البنية المعمارية والتقنيات (Architecture & Stack)](#-البنية-المعمارية-والتقنيات-architecture--stack)
- [التحميل والتثبيت (Download & Installation)](#-التحميل-والتثبيت-download--installation)
- [البناء من المصدر (Building from Source)](#-البناء-من-المصدر-building-from-source)
- [الأذونات المستخدمة (Permissions)](#-الأذونات-المستخدمة-permissions)
- [الأمان والخصوصية (Security & Privacy)](#-الأمان-والخصوصية-security--privacy)
- [المساهمة والترخيص (Contributing & License)](#-المساهمة-والترخيص-contributing--license)

---

## 🌟 نظرة عامة (Overview)

صُمم **ScreenMonitor** ليكون أداة مراقبة وتسجيل دوري لشاشة الهاتف تتسم بأعلى درجات البساطة والموثوقية؛ حيث يمنحك التطبيق سجلاً بصرياً تفصيلياً لما يحدث على الهاتف وفق فترات زمنية تختارها بحرية، مع حماية كاملة برمز سري (PIN) وتخزين محلي مشفر ومستقل تماماً بدون أي اتصال بالإنترنت أو خوادم خارجية.

---

## ✨ المميزات الرئيسية (Key Features)

### 📸 التقاط دوري ذكي ومستقر
- تحديد فاصل زمني مرن بين كل لقطة شاشة وأخرى (10 ثوانٍ، 30 ثانية، دقيقة، 5 دقائق، إلخ).
- استخدام واجهة `MediaProjection` الرسمية مع خدمة واجهة أمامية (`Foreground Service`) لضمان استقرار العمل في الخلفية.

### 🌙 ذكاء استشعار حالة الشاشة (Zero Battery Waste)
- **منع الالتقاط أثناء إطفاء الشاشة**: يرصد التطبيق حالة الهاتف تلقائياً (`ACTION_SCREEN_OFF` و `PowerManager.isInteractive`).
- يتوقف المعالج عن الالتقاط فور قفل الشاشة أو نومها لحفظ شحن البطارية ومنع امتلاء الذاكرة بصور سوداء أو مكررة.
- استئناف ذكي وفوري مع أول إطار جديد فور إعادة تشغيل الشاشة.

### 🔒 حماية أمنية مشددة (PIN Protection)
- حماية الدخول برمز مرور رقمي مشفر بخوارزمية **Salted SHA-256**.
- نظام احترازي ضد التخمين: قفل زمني تدريجي بعد 5 محاولات إدخال خاطئة.
- قفل تلقائي عند مغادرة التطبيق أو إطفاء الشاشة لحماية الخصوصية.

### 🛡️ سجل التدقيق ورصد سحب الأذونات (Security Precaution Audit)
- إذا حاول أي شخص تعطيل المراقبة أو سحب إذن الشاشة من شريط إشعارات النظام، يرصد التطبيق ذلك فوراً ويسجل الواقعة كحدث أمني رسمي (`PERMISSION_REVOKED`) مع التوقيت الدقيق لإعلام المشرف.

### 🖼️ معرض صور متطور وفائق السلاسة
- تنظيم تلقائي للقطات حسب التواريخ (اليوم، أمس، الأسبوع الماضي).
- معاين صور سينمائي بأشرطة عائمة شفافة (`Frosted Glass`).
- دعم إيماءة **السحب للأسفل للإغلاق (`Swipe-Down to Dismiss`)** المماثلة لتطبيقات الصور العالمية.
- تكبير متعدد الدرجات بالنقر المزدوج والقرص (`Pinch-to-zoom`).
- شريط مصغرات شريطي سفلي سريع (`Filmstrip Navigation`) ونافذة تفاصيل الصورة المتقدمة (الأبعاد، الحجم، المسار، التاريخ).

### 🧹 إدارة التخزين والتنظيف التلقائي
- إمكانية تحديد سياسة الاحتفاظ باللقطات (يوم واحد، 3 أيام، أسبوع، شهر، أو يدوي).
- حذف دوري وتلقائي للصور القديمة المنتهية الصلاحية مع فحص المساحة المتبقية على وحدة التخزين.

---

## 🏗️ البنية المعمارية والتقنيات (Architecture & Stack)

| المكون | التقنية المستخدمة |
| :--- | :--- |
| **لغة البرمجة** | Kotlin 2.0+ (100%) |
| **واجهة المستخدم** | Jetpack Compose + Material Design 3 |
| **النمط المعماري** | Clean Architecture + MVVM + Unidirectional Data Flow |
| **التزامن والخلفية** | Kotlin Coroutines & StateFlow |
| **التقاط الشاشة** | Android MediaProjection API + VirtualDisplay + ImageReader |
| **خدمة الخلفية** | Foreground Service (`type: mediaProjection`) |
| **الأمان والتشفير** | PBKDF2 / Salted SHA-256 + Encrypted Local Storage |
| **الحد الأدنى للنظام** | Android 7.0 (API Level 24+) |
| **النظام المستهدف** | Android 15+ (API Level 36) |

---

## 📲 التحميل والتثبيت (Download & Installation)

يمكنك تحميل ملف الـ APK الجاهز والموقع مباشرة من صفحة الإصدارات:

👉 **[تحميل أحدث إصدار: ScreenMonitor v1.0.0](https://github.com/yalaahamdy/ScreenMonitor/releases/latest)**

### التثبيت عبر سطر أوامر ADB:
```bash
adb install -r ScreenMonitor.apk
```

---

## 🛠️ البناء من المصدر (Building from Source)

### المتطلبات الأساسية:
- **Android Studio Ladybug (2024.2.1)** أو أحدث.
- **JDK 17** أو أحدث.
- **Android SDK** مع تثبيت Build-Tools 34+.

### خطوات البناء:
```bash
# استنساخ المستودع
git clone https://github.com/yalaahamdy/ScreenMonitor.git
cd ScreenMonitor

# تشغيل اختبارات الوحدة للتأكد من سلامة الشيفرة
./gradlew test

# بناء حزمة التطبيق (Debug APK)
./gradlew assembleDebug
```
ستجد ملف الـ APK الناتج في المسار:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📋 الأذونات المستخدمة (Permissions)

يلتزم التطبيق بمبدأ الحد الأدنى من الأذونات (`Principle of Least Privilege`):

| الإذن | السبب |
| :--- | :--- |
| `FOREGROUND_SERVICE` | لتشغيل خدمة الالتقاط المستمرة في الخلفية دون أن يقتلها النظام. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | إذن أندرويد 14+ الإلزامي لخدمات التقاط وتسجيل الشاشة. |
| `POST_NOTIFICATIONS` | لعرض إشعار الواجهة الأمامية الإلزامي وإتاحة زر الإيقاف السريع. |

> [!NOTE]
> لا يطلب التطبيق إذن الإنترنت (`android.permission.INTERNET`) نهائياً، مما يضمن تقنياً استحالة تسريب أي بيانات إلى خارج الهاتف.

---

## 🛡️ الأمان والخصوصية (Security & Privacy)

- **100% بدون اتصال (Completely Offline)**: لا خوادم، لا تحليلات، لا إعلانات، ولا تتبع سلوك المستخدم.
- **عزل التخزين**: تُحفظ لقطات الشاشة داخل المجلد الآمن المعزول للتطبيق (`context.filesDir`)، ولا تظهر في معارض الصور العامة أو للتطبيقات الأخرى دون إذن صريح.
- **حماية الرمز السري**: لا يُحفظ رمز المرور كنص صريح إطلاقاً، بل يُشفر مع Salt فريد.

---

## 🤝 المساهمة والترخيص (Contributing & License)

نرحب بكافة المساهمات والاقتراحات لتطوير المشروع!
- للمساهمة بالكود، يرجى مراجعة [CONTRIBUTING.md](CONTRIBUTING.md).
- للإبلاغ عن مشكلة أمنية، يرجى قراءة [SECURITY.md](SECURITY.md).
- يلتزم هذا المشروع بميثاق السلوك [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

هذا المشروع مرخص بموجب رخصة **Apache 2.0** - راجع ملف [LICENSE](LICENSE) لمزيد من التفاصيل.

---

<p align="center">
  صُنع بإتقان وعناية بواسطة <strong><a href="https://github.com/yalaahamdy">yalaahamdy</a></strong>
</p>
