# 🛡️ Total Battle – Clan Chest Tracker

An intelligent, real-time Android overlay assistant designed for **Total Battle** players. This app automatically scans and logs clan member chest contributions from the "Gift chests" screen using on-device OCR and image processing.

---

## 🚀 Purpose
Manually tracking hundreds of clan chests is tedious. This app provides a "hands-off" experience:
- **Automatic Detection:** Recognizes the "Gift chests" list.
- **Silent Scanning:** Uses a non-intrusive floating overlay to count chests while you scroll.
- **Smart Deduplication:** Ensures no chest is counted twice, even during fast scrolling.
- **Cloud Sync:** Automatically sends logged data to a Next.js backend for clan activity tracking.

---

## 🛠️ Tech Stack
- **Language:** Kotlin & Jetpack Compose (UI)
- **Vision:** OpenCV (Preprocessing & Motion Detection)
- **OCR:** Google ML Kit Text Recognition (On-device)
- **Database:** Room (Local queuing & deduplication)
- **Networking:** Retrofit & OkHttp
- **OS Integration:** MediaProjection API (Screen Capture) & Foreground Services

---

## ⚙️ Getting Started

### Prerequisites
- Android Studio Iguana (or newer)
- Android Device running **Android 8.0 (API 26)** or higher
- [Backend] A running instance of the Chest Tracker Backend (Next.js)

### 1. Local Configuration
Create a `local.properties` file in the root directory and add your SDK path (usually handled by Android Studio). For production builds, add your signing credentials:

```properties
sdk.dir=/path/to/android/sdk

# Production Signing (Optional for local debug)
RELEASE_STORE_FILE=release-key.jks
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

### 2. Generate a Release Keystore
To build a production version for installation, you need a signed keystore. You can generate one using this command:

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkey -v \
  -keystore release-key.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias my-key-alias
```

---

## 📦 Building & Running

### Development (Debug)
Connect your device and run:
```bash
./gradlew installDebug
```

### Production (Release APK)
To generate an APK for direct installation:
```bash
./gradlew assembleRelease
```
The file will be at: `app/build/outputs/apk/release/app-release.apk`

---

## 🧠 How it Works (The Pipeline)
The app uses a high-performance vision pipeline to minimize battery impact:
1. **Frame Gate:** Skips unchanged frames to save CPU.
2. **Scroll Detector:** Increases processing frequency (FPS) only when movement is detected.
3. **Anchor Detection:** Uses OpenCV to find chest icons, creating precise crop regions for OCR.
4. **Hybrid Dedup:** A two-layer system (Spatial Hash + Persistent DB) ensures 100% accuracy during scrolling.

---

## 🤝 Contributing
1. Ensure `isMinifyEnabled = true` is tested before submitting PRs.
2. Follow the **Adaptive Processing** rules in the engineering docs to prevent thermal throttling.
