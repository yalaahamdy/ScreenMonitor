# ScreenGuard (ScreenMonitor) — Transparent Parental Screen Monitoring

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="ScreenGuard App Icon" />
</p>

<p align="center">
  <b>Transparent, offline, ethical parental screen monitoring for Android 11+ (API 30–34).</b>
</p>

<p align="center">
  <a href="#key-features">Key Features</a> •
  <a href="#luxury-themes">Luxury Themes</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#releases">Releases</a> •
  <a href="#privacy--ethics">Privacy</a>
</p>

---

## What is ScreenGuard?

**ScreenGuard** is an open-source, transparent parental control tool designed to protect children on Android devices. It takes periodic, silent screenshots of the child's screen without annoying media projection consent prompts, keeping parents informed while respecting device transparency.

Monitoring is always announced by a permanent system notification and cannot be hidden or stopped silently. All screenshots remain strictly on-device in app-private storage.

---

## Key Features

- **⚡ Instant & Reliable Capture**: Built on Android Accessibility `takeScreenshot()` (API 30+) with zero prompt spam.
- **🕒 Free Custom Intervals**: Set any custom capture interval in minutes (1, 5, 10, 15, 60...) freely.
- **🌙 Smart Screen-Wake & Doze Detection**: Automatically detects screen activation (`ACTION_SCREEN_ON` & `ACTION_USER_PRESENT`) and triggers overdue captures immediately upon wake-up.
- **🚀 App-Open Snapping**: Snaps newly foregrounded apps so parents see what was just opened.
- **🎨 5 Luxury Palettes**: Five dark prestige themes selectable in-app:
  - **Midnight Prestige** (Champagne gold over midnight navy)
  - **Emerald Vault** (Royal emerald with golden accents)
  - **Rose Aristocrat** (Rose gold on bordeaux velvet)
  - **Onyx Platinum** (Polished platinum on dark onyx)
  - **Sapphire Crown** (Ice sapphire on deep royal blue)
- **🌍 In-App Language Switching**: Instant runtime switching between **System Default**, **English**, and **العربية (Arabic)** without needing to change system language.
- **🔒 Universal Parental PIN Gate**: The entire app (Dashboard, Settings, Gallery, and Setup) is locked behind a mandatory Parental PIN (Salted SHA-256). Any attempt to open the app or resume from background immediately presents the PIN gatekeeper with zero bypass capability.
- **🛡️ Real-Time Anti-Tamper Protection**: Prevents unauthorized stopping of permissions or accessibility service from Android System Settings when the session is locked.
- **⏳ Auto-Lock & Session Inactivity**: The app automatically re-locks whenever minimized or sent to the background, preventing children from accessing controls if the device is handed over.
- **🛡️ Anti-Uninstall Defense**: Device Administrator integration blocks unauthorized app removal.
- **📦 Intelligent Storage Quota**: Customizable max-storage quota (e.g. 50, 200, 1000 captures) with automatic rolling cleanup.
- **⚡ Fast JPEG Engine**: Crisp 90% quality JPEG compression saving in under 50ms and cutting disk usage by 85%.
- **📴 100% Offline & Private**: Zero internet permissions (`android.permission.INTERNET` is completely omitted). No data ever leaves the device.

---

## Setup Guide (Child's Device)

1. **Download & Install**: Grab `ScreenGuard-release.apk` from the [Releases](https://github.com/yalaahamdy/ScreenMonitor/releases) tab.
2. **Notification Permission**: Grant notifications so the permanent transparency notice is displayed.
3. **Set Parent PIN**: Choose a 4-digit secret PIN protecting the gallery and settings.
4. **Enable Accessibility Service**:
   - Navigate to *Settings → Accessibility → ScreenGuard Monitoring Service* and switch to **ON**.
   - *(Android 13+ note)*: If greyed out, go to *App Info → ⋮ (top right) → Allow restricted settings*.
5. **Activate Device Admin**: Grants anti-tamper and instantaneous lock capabilities.
6. **Exempt from Battery Optimization**: Prevents aggressive background kills.
7. **Done**: Monitoring begins immediately with a persistent status notification.

---

## Tech Stack & Architecture

- **Language**: Kotlin 1.9+, Java 17
- **Target SDK**: Android 34 (UpsideDownCake) | **Min SDK**: Android 30 (Android 11)
- **Capture Mechanism**: `AccessibilityService.takeScreenshot()` with `android:canTakeScreenshot="true"`.
- **Foreground Service**: `specialUse` foreground service ensuring non-killable status and watchdog alerts.
- **Theme Engine**: Dynamic `BaseActivity` with runtime attribute resolution (`attrs.xml` + `ThemeHelper`).
- **Localization Engine**: Dynamic configuration wrapping via `LocaleHelper`.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/yalaahamdy/ScreenMonitor.git
cd ScreenMonitor

# Build Release APK
./gradlew assembleRelease
```

The signed release APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

---

## Privacy & Ethics

ScreenGuard is engineered strictly for **parental monitoring of minors with full transparency**. It complies with transparency guidelines by never running covertly: a persistent notification is visible at all times while the service is active.

---

## License

ScreenGuard is released under the [MIT License](LICENSE).
