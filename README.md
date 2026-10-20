# PDF Voice Reader — Android app

Malayalam & English PDF reader with on-device OCR and read-aloud that
**keeps reading with the screen off** (Android's own speech engine runs
inside a background service with a "Reading" notification).

## Get the APK — choose one

### A. Let GitHub build it (no software to install)
1. Sign in at github.com and create a new **empty** repository, e.g. `pdf-voice-reader`.
2. On the repository page choose **Add file → Upload files** and drag in
   everything inside this folder (on a computer; keep the folder structure).
   Also upload the hidden `.github` folder — it holds the build recipe.
3. Commit. Open the **Actions** tab: "Build Android APK" starts by itself
   and takes about 5–8 minutes.
4. Open the finished run, scroll to **Artifacts** and download
   `PDF-Voice-Reader-APK` (a zip containing `app-debug.apk`).

### B. Android Studio on Windows
1. Install Node.js 22 and Android Studio (current version).
2. In this folder run `npm install` then `npx cap sync android`.
3. Open the `android` folder in Android Studio, wait for Gradle to finish,
   then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK is in `android/app/build/outputs/apk/debug/`.

## Install on the phone
Copy `app-debug.apk` to the phone and open it. Android asks to allow
installs from that source (Files or Chrome) — allow it once. On first
Read aloud, allow notifications so the Pause/Stop controls show.

For Malayalam speech: Settings → search "Text-to-speech output" →
Speech Services by Google → Install voice data → Malayalam (India).

## What's inside
- `www/index.html` — the reader (PDF engine, Malayalam/English OCR models,
  and the app all in one file; works offline).
- `android/app/src/main/java/app/pdfvoicereader/malayalam/`
  - `ReaderEngine.java` — Android text-to-speech and the reading queue
  - `ReaderService.java` — background service, notification, wake lock
  - `ReaderTtsPlugin.java` — bridge between the page and the engine
- `.github/workflows/build-apk.yml` — the GitHub build recipe.

To change the reader page, replace `www/index.html` and build again.
