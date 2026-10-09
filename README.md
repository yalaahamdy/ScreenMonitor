# ScreenGuard (ScreenMonitor) v3.2.0 — Unified Parental Control & Screen Protection 🛡️

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="ScreenGuard App Icon" />
</p>

<p align="center">
  <b>The all-in-one transparent, offline parental screen monitoring & app usage controller for Android 11+ (API 30–34).</b>
</p>

<p align="center">
  <a href="#whats-new-in-v320">What's New in v3.2</a> •
  <a href="#core-features">Core Features</a> •
  <a href="#luxury-themes">Luxury Themes</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#releases">Releases</a> •
  <a href="#privacy--ethics">Privacy</a>
</p>

---

## 🌟 What is ScreenGuard v3.2.0?

**ScreenGuard v3.2.0** is the ultimate unified parental control application, seamlessly merging **ScreenMonitor** (intelligent stealth screen capture, luxury themes, parental PIN gate) with **Muraqib / App-Usage-Tracker-Controller** (screen-time analytics, data usage statistics, and real-time app restriction engine) into a single, cohesive, high-performance app.

Everything runs 100% on-device with **zero internet permissions**, completely respecting user privacy.

---

## 🆕 What's New in v3.2.0 (Merged Suite)

### 1. 🧭 Unified 4-Tab Bottom Navigation (`MainTabsActivity`)
- **🏠 Home**: Quick overview of device protection shields, active screen time, recent captures, and one-tap lock.
- **📊 Analytics**: Hourly 24-column interactive usage bar chart, time-of-day distribution (Morning, Afternoon, Evening, Night), app launch counts, and comparative statistics.
- **🌐 Data Usage**: Full breakdown of Wi-Fi vs. Mobile data consumption per app and system total.
- **🚫 Restrictions**: Real-time app blocker, daily/weekly usage limits, group quotas, and scheduled time windows.

### 2. ⚡ Two-Tier App Restriction & Blocker Engine
- **Instant Blocking**: Zero-delay blocking via Accessibility Service + 1.2s polling fallback service.
- **Custom Usage Quotas**: Daily/weekly usage allowances per app or shared group quotas.
- **Allowed Schedules**: Configure active days and multiple allowed time windows.
- **Split-Screen Shield & PiP Interception**: Precise covering of blocked apps in multi-window or picture-in-picture mode.
- **PIN-Protected Temporary Bypass**: 1–300 minutes temporary grace with automatic expiration surviving reboots.

### 3. 🎯 Active Screen Usage-Duration Capture Engine
- Captures are triggered by actual active foreground screen usage (default: 10 active minutes) rather than arbitrary clock time.
- Optional secondary triggers: screen wake, app open, periodic timer.
- Zero battery waste during deep sleep (Doze Mode).
- High-speed 90% quality JPEG engine with automatic rolling storage quota (auto-prune).

### 4. 🔒 Universal Security & Anti-Tamper Defense
- **Mandatory App-Entry PIN Gate**: App launch and background resume immediately require the 4-digit salted SHA-256 PIN.
- **Security Question Recovery**: Safe PIN recovery with security question and brute-force lockout (5 failed attempts = 30s lockout).
- **Auto-Lock on Background**: Immediate session lock upon minimizing or leaving the app.
- **Anti-Tamper Shield**: Intercepts attempts to disable Accessibility or Device Admin from system settings when locked.
- **Safe Mode Audit & Clock-Rollback Detection**: Detects attempts to bypass restrictions by rebooting into safe mode or changing system clock.

### 5. 💾 JSON Backup & Restore
- Full export and import of all restrictions and app settings verified with SHA-256 checksums.

---

## 🎨 5 Luxury Palettes

ScreenGuard includes five distinct prestige themes selectable at runtime:
- 🌌 **Midnight Prestige**: Champagne gold on deep midnight navy
- 🌲 **Emerald Vault**: Royal emerald with golden accents
- 🍷 **Rose Aristocrat**: Rose gold on bordeaux velvet
- 🪙 **Onyx Platinum**: Polished platinum on dark onyx
- 👑 **Sapphire Crown**: Ice sapphire on deep royal blue

---

## 🌍 Complete Bilingual Support (English & Arabic)

- Full runtime in-app language switching between **English**, **العربية (Arabic)**, and **System Default**.
- Comprehensive Right-to-Left (RTL) layout optimization across all tabs, dialogs, and charts.

---

## 📲 Quick Setup (Child's Device)

1. **Install APK**: Download `ScreenGuard-v3.2.0.apk` from the [Releases](https://github.com/yalaahamdy/ScreenMonitor/releases) tab.
2. **Notification Permission**: Displays the permanent parental transparency banner.
3. **Set Parent PIN**: Choose a secret 4-digit PIN with a recovery security question.
4. **Enable Accessibility Service**: Powers screen capture, instant blocking, and anti-tamper shields.
5. **Activate Device Admin**: Prevents unauthorized app uninstallation and enables instant screen lock.
6. **Battery Optimization Exemption**: Ensures uninterrupted monitoring without OS kills.
7. **Usage Access Permission**: Enables screen-time analytics and usage limits.

---

## 🏗️ Architecture & Tech Stack

- **Target SDK**: Android 34 | **Min SDK**: Android 30 (Android 11+)
- **Languages**: Kotlin 1.9+, Java 17
- **UI Framework**: Native XML Views, Custom Canvas Charts (`HourlyUsageBarChartView`, `TimeOfDayDistributionView`), Bottom Navigation + ViewPager2 / Fragments.
- **Security**: Salted SHA-256 with dual legacy migration support.
- **Permissions**: Zero internet permission (`android.permission.INTERNET` is omitted).

---

## 🔨 Building from Source

```bash
# Clone the repository
git clone https://github.com/yalaahamdy/ScreenMonitor.git
cd ScreenMonitor

# Build Release APK
./gradlew assembleRelease
# On Windows:
.\gradlew.bat assembleRelease
```

Signed release APK output:
`app/build/outputs/apk/release/app-release.apk`

---

## ⚖️ License & Ethics

- ScreenGuard code is licensed under the **MIT License**.
- App-Usage-Tracker-Controller components under **Apache License 2.0**.
- Strictly designed for **transparent, ethical parental guidance of minors**.
