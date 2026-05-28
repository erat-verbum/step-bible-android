# AGENTS.md

Development guide for the STEP Bible Android project.

## Workspace Rules

**Use only directories local to this repository.** All build artifacts, caches, SDKs, and temporary files must live inside the project tree (e.g. `./tmp/`, `build-cache/`). Never reference or create files in system-wide or user-global directories like `~/.android/`, `~/Android/`, or `/tmp/`. If a tool requires a path (e.g. `ANDROID_HOME`, `ANDROID_SDK_ROOT`), point it to a local directory.

## Build System

The project uses `build.sh` with sequential phases. Each phase depends on the previous one.

```bash
# Full setup (first time only):
./build.sh setup

# Or run phases individually:
./build.sh clean      # Clear all caches and assets
./build.sh download   # Download STEP deb + JRE tarballs
./build.sh extract    # Extract STEP server + JREs into app assets
./build.sh build      # Compile APK via Gradle
```

### Phase details

| Phase | What it does |
|-------|-------------|
| `clean` | Deletes `build-cache/`, `app/src/main/assets/jre/`, `app/src/main/assets/step/` |
| `download` | Downloads STEP `.deb` from dev.stepbible.org and JRE 17 tarballs from PojavLauncher GitHub |
| `extract` | Extracts STEP server JARs, compiles bootstrap launcher, extracts JREs for all architectures (aarch64, x86_64) into `app/src/main/assets/jre/$abi/` |
| `build` | Runs `./gradlew assembleDebug` |

### After `clean`, always re-run `download` + `extract` before `build`

The `clean` phase deletes the JRE assets. If you build without re-extracting, the APK will not contain a JRE and the app will crash on launch with:

```
JVMStub : dlopen libjvm failed: dlopen failed: library ".../files/jre/lib/server/libjvm.so" not found
```

## Gradle Build

```bash
export ANDROID_HOME="/path/to/android-sdk"
export JAVA_HOME="/path/to/jdk-21"
echo "sdk.dir=$ANDROID_HOME" > local.properties
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug
```

Produces per-architecture APKs in `app/build/outputs/apk/debug/`:
- `app-arm64-v8a-debug.apk`
- `app-x86_64-debug.apk`

## Emulator Setup

### Install required SDK components

```bash
SDK="build-cache/android-sdk"

# cmdline-tools (already included via build.sh)
# emulator
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --install "emulator"

# system images (must match AVD)
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
  "system-images;android-34;google_apis;x86_64"

# Create AVD
echo "no" | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
  -n step_test -k "system-images;android-34;google_apis;x86_64" -d "pixel"
```

### Start emulator with GUI (not headless)

```bash
SDK="build-cache/android-sdk"
export ANDROID_SDK_ROOT="$SDK"
nohup "$SDK/emulator/emulator" -avd step_test -no-audio -gpu swiftshader_indirect &
# Wait for boot
"$SDK/platform-tools/adb" wait-for-device
```

### Install and launch

```bash
SDK="build-cache/android-sdk"
"$SDK/platform-tools/adb" install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
"$SDK/platform-tools/adb" shell monkey -p com.eratverbum.stepbible \
  -c android.intent.category.LAUNCHER 1
```

**Important:** Match APK architecture to emulator ABI:
- `getprop ro.product.cpu.abi` → `x86_64` → use `app-x86_64-debug.apk`
- `getprop ro.product.cpu.abi` → `arm64-v8a` → use `app-arm64-v8a-debug.apk`

## Physical Device Deployment

Enable USB debugging on the phone (Developer Options → USB debugging), then connect via USB.

```bash
SDK="build-cache/android-sdk"

# If the device doesn't appear in `adb devices`, restart the server:
"$SDK/platform-tools/adb" kill-server
"$SDK/platform-tools/adb" start-server
"$SDK/platform-tools/adb" devices

# Check device ABI:
"$SDK/platform-tools/adb" shell getprop ro.product.cpu.abi

# Install and launch (use correct APK for device ABI):
"$SDK/platform-tools/adb" install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
"$SDK/platform-tools/adb" shell monkey -p com.eratverbum.stepbible \
  -c android.intent.category.LAUNCHER 1
```

### Force re-extraction on device

```bash
"$SDK/platform-tools/adb" shell "run-as com.eratverbum.stepbible rm files/.extraction-complete"
"$SDK/platform-tools/adb" shell "run-as com.eratverbum.stepbible rm files/.app-version"
"$SDK/platform-tools/adb" shell am force-stop com.eratverbum.stepbible
# Then relaunch
```

## First-Launch Extraction

On first launch (or after version update), the app extracts:
1. **JRE** from `assets/jre/$abi/` → `files/jre/`
2. **STEP data** from `assets/step.tar` → `files/step/`

Marker files:
- `files/.extraction-complete` — signals extraction is done
- `files/.app-version` — stores version code to trigger re-extraction on update

### Force re-extraction

If you rebuild with different assets, delete the markers:

```bash
adb shell "run-as com.eratverbum.stepbible rm files/.extraction-complete"
adb shell "run-as com.eratverbum.stepbible rm files/.app-version"
adb shell am force-stop com.eratverbum.stepbible
# Then relaunch
```

## Architecture

- `MainActivity.kt` — Android app: WebView, tab management, JVM/server lifecycle
- `app/src/main/jni/` — Native JNI code (C++ via CMake) for JVMStub
- `step-bootstrap/` — Java bootstrap launcher compiled into `step_bootstrap.jar`
- `build.sh` — Build system orchestrating download, extraction, and compilation

### Runtime flow

1. `MainActivity` starts STEP JVM server on port 8989
2. WebView loads `http://127.0.0.1:8989`
3. STEP server serves web app from `files/step/step-web/`

## Common Issues

| Problem | Cause | Fix |
|---------|-------|-----|
| `libjvm.so not found` | JRE not in APK assets | Re-run `./build.sh extract && ./build.sh build` |
| App crashes with wrong ABI | Mismatched APK/emulator arch | Install correct per-arch APK |
| `libjvm.so is for EM_X86_64 instead of EM_AARCH64` | Wrong APK on wrong emulator | Match APK arch to `getprop ro.product.cpu.abi` |
| Extraction skipped on rebuild | Old marker file persists | Delete `.extraction-complete` and `.app-version` |
| No emulator window | Started with `-no-window` | Restart without that flag (use `-gpu swiftshader_indirect`) |
| STEP server won't start | Missing STEP data in assets | Run `./build.sh extract` then rebuild |

## Releasing

Releases are automated via GitHub Actions. Pushing a tag matching `[0-9]+.[0-9]+.[0-9]+.[0-9]+` triggers the workflow in `.github/workflows/build-release.yml`.

### Tag format

```
STEP_MAJOR.STEP_MINOR.STEP_PATCH.ANDROID_PATCH
```

Example: `26.5.2.1` means STEP version 26.5.2, Android release 1.

The first three numbers determine which STEP `.deb` is downloaded from `dev.stepbible.org`. The fourth number is the Android app release iteration.

### How to release

```bash
# 1. Ensure all changes are committed
git status  # should be clean

# 2. Push commits to main
git push origin main

# 3. Tag the release (using the current STEP version)
git tag 26.5.2.1
git push origin 26.5.2.1
```

The CI workflow will:
1. Download the specified STEP `.deb` version
2. Extract STEP server + JREs into APK assets
3. Build signed release APKs (arm64-v8a + x86_64)
4. Create a GitHub Release with the APKs attached

### What the workflow does NOT do

- It does **not** run `clean` — it uses a build cache

### Local release builds

To build a signed release APK locally:

```bash
export KEYSTORE_PATH=/path/to/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./build.sh build release
```

Produces per-architecture APKs in `app/build/outputs/apk/release/`. Without signing env vars, it falls back to the debug keystore.
