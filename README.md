# STEP Bible for Android

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-android-3DDC84.svg)](https://www.android.com)
[![API](https://img.shields.io/badge/minSdk-26-8A2BE2.svg)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9-7F52FF.svg)](app/build.gradle.kts)

Native Android wrapper for [STEP Bible](https://www.stepbible.org/) — embeds the full STEP Bible server (Jetty + JVM) inside the app and renders it through a tabbed WebView UI.

<p align="center">
  <img src="docs/screenshots/main-view.png" alt="Main view" width="200"/>
  <img src="docs/screenshots/tab-overview.png" alt="Tab overview" width="200"/>
  <img src="docs/screenshots/lookup-multi.png" alt="Multi-version lookup" width="200"/>
  <img src="docs/screenshots/dark-esv.png" alt="Dark theme" width="200"/>
</p>

## Features

- **Embedded STEP Bible server** — Bundles the STEP Bible Jetty server via JNI + JVM; works offline after initial setup
- **Tabbed browsing** — Open multiple passages simultaneously with a desktop-style tab bar
- **Tab overview** — Grid view of all open tabs with live WebView thumbnails
- **Share-to-lookup** — Select any Bible reference from another app and open it directly in STEP via "Look up in ESV" or "Look up in ESV, SBLG, THOT" share targets
- **Dark theme** — Automatic DayNight theme with WebView `FORCE_DARK` support
- **Session persistence** — Tabs and navigation state survive process death and rotation
- **Navigation controls** — Back/forward with long-press history popup, reload
- **File downloads** — Download via Android `DownloadManager`
- **Multi-architecture** — Ships JRE for both `arm64-v8a` and `x86_64`

## Architecture

```
┌──────────────────────────────────────┐
│  Kotlin App (MainActivity)           │
│  ┌────────────────────────────────┐  │
│  │  WebView UI (tabbed browser)   │  │
│  ├────────────────────────────────┤  │
│  │  C JNI Stub (step_jvm_stub)    │  │
│  ├────────────────────────────────┤  │
│  │  JVM (PojavLauncher JRE 17)    │  │
│  ├────────────────────────────────┤  │
│  │  Jetty Server (STEP Bible)     │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

The C JNI stub (`app/src/main/jni/step_jvm_stub.c`) loads `libjvm.so` from the bundled JRE, creates a JVM instance, and starts the STEP Bible Jetty server. The Kotlin frontend polls for server readiness, then loads the local server URL in a tabbed WebView.

## Build

### Prerequisites

- Linux (or macOS with modifications)
- ~4 GB free disk space (JDK, Android SDK, JRE, STEP data)

### Quick build

```bash
./build.sh build
```

This downloads **JDK 21**, **Android SDK**, **JRE 17** (PojavLauncher), the latest **STEP Bible .deb**, extracts everything, compiles the Java bootstrap, and produces a debug APK at `app/build/outputs/apk/debug/`.

### Phased build

```bash
./build.sh setup        # Install JDK, Android SDK, Gradle
./build.sh download     # Download STEP .deb and JRE tarballs
./build.sh extract      # Extract assets into app/src/main/assets/
./build.sh build        # Compile and package APK
```

### Run on emulator

```bash
./build.sh setup
./build.sh system-image  # Download Android 34 x86_64 system image
./build.sh build
./build.sh run           # Start emulator, install APK, launch app
```

### Clean

```bash
./build.sh clean
```

## Project structure

```
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/eratverbum/stepbible/
│   │   │   │   ├── MainActivity.kt          # Tabbed WebView browser UI
│   │   │   │   ├── StepServerService.kt     # Foreground service for server
│   │   │   │   ├── JVMStub.kt              # Native JVM loader binding
│   │   │   │   └── TarExtractor.kt         # First-launch asset extraction
│   │   │   ├── jni/
│   │   │   │   └── step_jvm_stub.c         # C JNI: loads libjvm, starts Jetty
│   │   │   ├── res/                        # Layouts, themes, drawables
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
├── step-bootstrap/                          # Java bootstrap for STEP server
├── build.sh                                 # One-click build script
├── docs/screenshots/                        # App screenshots
└── README.md
```

## License

This project is licensed under the **BSD 3-Clause License** — see [LICENSE](LICENSE).

STEP Bible software is [BSD 3-Clause](https://stepbibleguide.blogspot.com/p/copyrights-licences.html) licensed (© Tyndale House / STEPBible.org).

## Acknowledgments

- **[STEP Bible](https://www.stepbible.org/)** — The open-source Bible study platform by Tyndale House and STEPBible.org
- **[PojavLauncher](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)** — JRE builds for Android
- **[CrossWire Bible Society](https://www.crosswire.org/)** — JSword library and Bible modules
