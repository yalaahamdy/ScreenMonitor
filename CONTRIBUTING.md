# دليل المساهمة في ScreenMonitor (Contributing Guidelines)

شكراً لاهتمامك بالمساهمة في مشروع **ScreenMonitor**! نرحب بجميع المساهمات، سواء كانت إصلاحاً لخلل برمج، أو تحسيناً للأداء، أو إضافة ميزة جديدة.

---

## 🛠️ كيفية البدء (Getting Started)

1. **Fork المستودع**:
   قم بعمل Fork للمشروع إلى حسابك الشخصي على GitHub.

2. **استنساخ المستودع (Clone)**:
   ```bash
   git clone https://github.com/<your-username>/ScreenMonitor.git
   cd ScreenMonitor
   ```

3. **إنشاء فرع جديد (Branch)**:
   استخدم اسماً واضحاً وموجزاً يصف التعديل:
   ```bash
   git checkout -b feature/awesome-new-feature
   # أو
   git checkout -b fix/issue-description
   ```

---

## 📐 معايير كتابة الشيفرة (Code Standards)

- **Kotlin Idioms**: كتابة كود كوتلن نظيف ومتوافق مع معايير Google الرسمية لكوتلن.
- **Jetpack Compose**: استخدام مكونات Material 3 الرسمية وتجنب الرموز التعبيرية (Emojis) داخل واجهة المستخدم واستبدالها بأيقونات رسمية موجهة (Vector Icons).
- **Architecture**: الالتزام بنمط Clean Architecture وفصل المهام بين طبقة البيانات (`data`) وطبقة العرض (`ui`) وطبقة الخدمات (`service`).
- **Zero Internet**: عدم إضافة أي مكتبات خارجية تتطلب إذن الإنترنت أو تُرسل بيانات خارج الهاتف للحفاظ على خصوصية المشروع بنسبة 100%.

---

## 🧪 الاختبار والتحقق (Testing & Verification)

قبل إرسال التعديلات، تأكد من نجاح كافة الاختبارات وبناء التطبيق بدون أي تحذيرات أو أخطاء:

```bash
# تشغيل اختبارات الوحدة
./gradlew test

# التحقق من بناء الحزمة
./gradlew assembleDebug
```

---

## 📝 رسائل الالتزام (Commit Messages)

نتبع معيار **Conventional Commits**:
- `feat: add screen-off power saving support`
- `fix: prevent duplicate virtual display creation on Android 14`
- `docs: update README with installation instructions`
- `refactor: optimize photo viewer filmstrip performance`

---

## 🚀 إرسال طلب السحب (Pull Request)

1. ارفع الفرع الخاص بك إلى حسابك على GitHub:
   ```bash
   git push origin feature/awesome-new-feature
   ```
2. افتح Pull Request موجه إلى الفرع الرئيسي `main`.
3. املأ قالب الـ PR بالمعلومات المطلوبة ووصف التغييرات التي قمت بها.

شكراً لمساهمتك القيمة!
