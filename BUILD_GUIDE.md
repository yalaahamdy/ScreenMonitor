# Build Guide — ScreenGuard v3.0 (Merged)

## Requirements
- JDK 17 (Temurin recommended)
- Android SDK: `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`
- Gradle 8.6 is auto-downloaded by the wrapper

## Command line

Linux / macOS:
```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Windows (PowerShell):
```powershell
$env:ANDROID_HOME = "C:\Android\Sdk"
.\gradlew.bat assembleRelease
```

## Android Studio
1. File ▸ Open ▸ select this folder
2. Let Gradle sync finish
3. Run ▶ or Build ▸ Generate Signed Bundle / APK

## Signing
Release is signed with `keystore/release.keystore` (alias `screenguard`,
password `screenguard123`). Replace with your own keystore before publishing:

```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("../keystore/release.keystore")
        storePassword = "YOUR_STORE_PASSWORD"
        keyAlias = "your_alias"
        keyPassword = "YOUR_KEY_PASSWORD"
    }
}
```

## Troubleshooting
| Problem | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=/path/to/android-sdk` |
| `jlink does not exist` | You have a JRE only — install a full JDK 17 and set `org.gradle.java.home` in `gradle.properties` |
| Out-of-memory during build | Raise `org.gradle.jvmargs` in `gradle.properties` |
