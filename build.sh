#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

cleanup() {
    local exit_code=$?
    rm -f "$SCRIPT_DIR/build-cache/downloads/"*.partial 2>/dev/null
    if [[ -n "${EMULATOR_PID:-}" ]] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        kill "$EMULATOR_PID" 2>/dev/null || true
    fi
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

: "${STEP_DEB_URL:=""}"
: "${BUILD_TYPE:="Debug"}"
: "${JRE_VERSION:="17.0.19"}"
: "${GRADLE_VERSION:="9.5.1"}"
: "${POJAV_JRE_RELEASE:="jre17-ec28559"}"
: "${POJAV_JRE_BASE:="https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/${POJAV_JRE_RELEASE}"}"
declare -A JRE_URLS
JRE_URLS[aarch64]="${POJAV_JRE_BASE}/jre17-arm64-20210825-release.tar.xz"
JRE_URLS[x86_64]="${POJAV_JRE_BASE}/jre17-x86_64-20210825-release.tar.xz"

CACHE_DIR="$SCRIPT_DIR/build-cache"
GRADLE_USER_HOME="$CACHE_DIR/gradle-home"
export GRADLE_USER_HOME
DOWNLOADS_DIR="$CACHE_DIR/downloads"
STEP_EXTRACT_DIR="$CACHE_DIR/step-extracted"
JRE_EXTRACT_DIR="$CACHE_DIR/jre-extracted"
JDK_DIR="$CACHE_DIR/jdk"
SDK_DIR="$CACHE_DIR/android-sdk"
GRADLE_DIR="$CACHE_DIR/gradle"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

JRE_ARCHS=("aarch64" "x86_64")
JRE_ABI_MAP_aarch64="arm64-v8a"
JRE_ABI_MAP_x86_64="x86_64"

info()  { echo "  -> $*"; }
die()   { echo "ERROR: $*" >&2; exit 1; }

clean() {
    rm -rf "$CACHE_DIR" "$ASSETS_DIR/jre" "$ASSETS_DIR/step" "$ASSETS_DIR/step.tar.gz"
    rm -f "$SCRIPT_DIR/.extracted" "$SCRIPT_DIR/.build-vars"
}

detect_step_version() {
    if [[ -n "$STEP_DEB_URL" ]]; then
        STEP_DEB="$DOWNLOADS_DIR/$(basename "$STEP_DEB_URL")"
        return
    fi
    info "Detecting latest STEP version from dev.stepbible.org..."
    local page
    page=$(curl -sL "https://dev.stepbible.org/downloads/")
    local latest
    latest=$(echo "$page" | grep -oP 'stepbible_\d+_\d+_\d+\.deb' | sort -t_ -k2 -V | tail -1)
    [[ -z "$latest" ]] && die "Could not detect STEP version"
    [[ ! "$latest" =~ ^stepbible_[0-9]+_[0-9]+_[0-9]+\.deb$ ]] && die "Invalid STEP version format: $latest"
    STEP_DEB_URL="https://dev.stepbible.org/downloads/$latest"
    STEP_DEB="$DOWNLOADS_DIR/$latest"
    info "Latest STEP: $latest"
}

download() {
    local url="$1" dest="$2"
    if [[ -f "$dest" ]]; then
        info "Already downloaded: $(basename "$dest")"
        return
    fi
    mkdir -p "$(dirname "$dest")"
    info "Downloading $(basename "$dest")..."
    curl -fSL --proto =https --tlsv1.2 "$url" -o "$dest.partial" || die "Download failed: $url"
    mv "$dest.partial" "$dest"
}

extract_deb() {
    local deb="$1" target="$2"
    info "Extracting $(basename "$deb")..."
    mkdir -p "$target"
    local tmpDir
    tmpDir=$(mktemp -d)
    (
        cd "$tmpDir"
        ar x "$deb"
        if [[ -f data.tar.xz ]]; then
            tar xf data.tar.xz
        elif [[ -f data.tar.bz2 ]]; then
            tar xf data.tar.bz2
        elif [[ -f data.tar.gz ]]; then
            tar xf data.tar.gz
        elif [[ -f data.tar.zst ]]; then
            tar --use-compress-program=zstd -xf data.tar.zst 2>/dev/null || zstd -dc data.tar.zst | tar xf -
        else
            ls -la "$tmpDir"
            die "Unknown data archive format in $deb"
        fi
        cp -a ./* "$target/"
    )
    rm -rf "$tmpDir"
}

setup_jdk() {
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/javac" ]]; then
        if "$JAVA_HOME/bin/javac" --version 2>&1 | grep -q "^javac 21"; then
            info "Using JDK 21 from JAVA_HOME: $JAVA_HOME"
            return
        fi
    fi
    local jdk_home
    jdk_home=$(find "$JDK_DIR" -maxdepth 1 -type d -name "jdk-21*" 2>/dev/null | head -1 || true)
    if [[ -n "$jdk_home" ]] && [[ -x "$jdk_home/bin/java" ]]; then
        export JAVA_HOME="$jdk_home"
        info "JDK 21 already at $jdk_home"
        return
    fi
    info "Downloading JDK 21..."
    mkdir -p "$JDK_DIR"
    curl -SL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz" \
      -o "$JDK_DIR/jdk21.tar.gz" || die "Failed to download JDK 21"
    tar -xzf "$JDK_DIR/jdk21.tar.gz" -C "$JDK_DIR/"
    jdk_home=$(find "$JDK_DIR" -maxdepth 1 -type d -name "jdk-21*" 2>/dev/null | head -1 || true)
    [[ -z "$jdk_home" ]] && die "JDK 21 extraction failed"
    export JAVA_HOME="$jdk_home"
    info "JDK 21 ready at $jdk_home"
}

setup_android_sdk() {
    local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$SDK_DIR}}"
    export ANDROID_HOME="$sdk_dir"

    if [[ -x "$sdk_dir/platform-tools/adb" ]] && [[ -d "$sdk_dir/platforms/android-34" ]]; then
        info "Android SDK already set up at $sdk_dir"
        return
    fi

    info "Setting up Android SDK at $sdk_dir..."
    mkdir -p "$sdk_dir"

    if [[ ! -f "$DOWNLOADS_DIR/cmdline-tools.zip" ]]; then
        local url="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        download "$url" "$DOWNLOADS_DIR/cmdline-tools.zip"
    fi
    unzip -qo "$DOWNLOADS_DIR/cmdline-tools.zip" -d "$sdk_dir/" 2>/dev/null

    # Handle cmdline-tools placement (varies by version)
    if [[ ! -f "$sdk_dir/cmdline-tools/bin/sdkmanager" ]]; then
        mkdir -p "$sdk_dir/cmdline-tools/latest"
        mv "$sdk_dir/cmdline-tools/bin" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
        mv "$sdk_dir/cmdline-tools/lib" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
    fi

    local sdkmanager
    sdkmanager=$(find "$sdk_dir/cmdline-tools" -name "sdkmanager" -type f 2>/dev/null | head -1 || true)
    [[ -z "$sdkmanager" ]] && die "sdkmanager not found after extraction"

    chmod +x "$sdkmanager"
    echo "y" | "$sdkmanager" --sdk_root="$sdk_dir" \
        "platforms;android-34" "build-tools;34.0.0" "platform-tools" \
        "ndk;27.0.12077973" | grep -v "^\[=\|Warning:" || true
    local _sdk_exit=${PIPESTATUS[0]}
    [[ $_sdk_exit -ne 0 ]] && die "sdkmanager installation failed (exit $_sdk_exit)"

    echo "sdk.dir=$sdk_dir" > "$SCRIPT_DIR/local.properties"
    info "Android SDK ready at $sdk_dir"
}

setup_gradle() {
    if [[ -x "$SCRIPT_DIR/gradlew" ]]; then return; fi
    info "Setting up Gradle wrapper..."
    if command -v gradle &>/dev/null; then
        gradle wrapper --gradle-version "$GRADLE_VERSION"
    else
        local gradle_zip="$GRADLE_DIR/gradle-${GRADLE_VERSION}-bin.zip"
        mkdir -p "$GRADLE_DIR"
        if [[ ! -f "$gradle_zip" ]]; then
            local gradle_url="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
            curl -fSL "$gradle_url" -o "$gradle_zip" || die "Failed to download Gradle"
        fi
        unzip -qo "$gradle_zip" -d "$GRADLE_DIR/" 2>/dev/null
        local gradle_home="$GRADLE_DIR/gradle-${GRADLE_VERSION}"
        "$gradle_home/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"
    fi
}

phase_download() {
    info "=== Phase: Download ==="
    detect_step_version

    mkdir -p "$DOWNLOADS_DIR"
    download "$STEP_DEB_URL" "$STEP_DEB"

    for arch in "${JRE_ARCHS[@]}"; do
        download "${JRE_URLS[$arch]}" "$DOWNLOADS_DIR/jre17-${arch}.tar.xz"
    done

    printf 'STEP_DEB=%q\n' "$STEP_DEB" > "$SCRIPT_DIR/.build-vars"
    printf 'STEP_VERSION=%q\n' "$(echo "$STEP_DEB" | grep -oP '\d+_\d+_\d+' | head -1)" >> "$SCRIPT_DIR/.build-vars"
    info "Download complete"
}

phase_extract() {
    info "=== Phase: Extract ==="
    [[ -f "$SCRIPT_DIR/.build-vars" ]] || phase_download
    source "$SCRIPT_DIR/.build-vars"

    rm -rf "$ASSETS_DIR/jre" "$ASSETS_DIR/step"

    # --- Extract STEP ---
    if [[ ! -d "$STEP_EXTRACT_DIR" ]]; then
        extract_deb "$STEP_DEB" "$STEP_EXTRACT_DIR"
    fi

    info "Copying STEP files..."
    mkdir -p "$ASSETS_DIR/step"
    local step_root
    step_root=$(find "$STEP_EXTRACT_DIR" -maxdepth 5 -name "step-server-*.jar" -type f 2>/dev/null | head -1 || true)
    [[ -z "$step_root" ]] && die "Could not find STEP server JAR in extracted package"
    step_root="$(dirname "$step_root")"
    info "STEP root: $step_root"

    cp -a "$step_root"/* "$ASSETS_DIR/step/"
    rm -rf "$ASSETS_DIR/step/jre" "$ASSETS_DIR/step/.install4j" \
           "$ASSETS_DIR/step/logs" "$ASSETS_DIR/step/runStep.sh" \
           "$ASSETS_DIR/step/post-install.sh" 2>/dev/null || true

    # --- Apply STEP config patches (matches Docker deployment) ---
    local web_props="$ASSETS_DIR/step/step-web/WEB-INF/classes/step.web.properties"
    if [[ -f "$web_props" ]]; then
        sed -i 's/^app\.desktop=false$/app.desktop=true/' "$web_props"
        info "Patched app.desktop=true"
    fi
    local web_xml="$ASSETS_DIR/step/step-web/WEB-INF/web.xml"
    if [[ -f "$web_xml" ]]; then
        sed -i '/<filter-name>Remote Address Filter<\/filter-name>/,/<\/filter-mapping>/d' "$web_xml"
        sed -i '/<!-- The following are used for the stand-alone version/,/the "-Pstandalone-install"/d' "$web_xml"
        info "Removed Remote Address Filter from web.xml"
    fi

    # --- Compile StepServerLauncher bootstrap JAR + missing class stubs ---
    local jdk_home
    jdk_home=$(find "$JDK_DIR" -maxdepth 1 -type d -name "jdk-21*" 2>/dev/null | head -1 || true)
    if [[ -z "$jdk_home" ]] && [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/javac" ]]; then
        if "$JAVA_HOME/bin/javac" --version 2>&1 | grep -q "^javac 21"; then
            jdk_home="$JAVA_HOME"
        fi
    fi
    if [[ -z "$jdk_home" ]]; then
        info "JDK 21 not found, skipping bootstrap compilation"
        info "Run './build.sh setup' first or use './build.sh build'"
    else
        local boot_dir="$SCRIPT_DIR/build-cache/step-bootstrap"
        rm -rf "$boot_dir" && mkdir -p "$boot_dir"
        local cp
        cp=$(find "$ASSETS_DIR/step" -maxdepth 4 -name '*.jar' -type f 2>/dev/null | tr '\n' ':')
        # Compile StepServerLauncher and stub classes together
        "$jdk_home/bin/javac" --release 17 -cp "$cp" -d "$boot_dir" \
            "$SCRIPT_DIR/step-bootstrap/src/com/eratverbum/stepbible/bootstrap/StepServerLauncher.java" \
            "$SCRIPT_DIR/step-bootstrap/src/com/tyndalehouse/step/models/timeline/simile/SimileTimelineTranslatorImpl.java" \
            "$SCRIPT_DIR/step-bootstrap/src/com/tyndalehouse/step/rest/controllers/InternationalJsonController.java" && \
        "$jdk_home/bin/jar" cf "$ASSETS_DIR/step/step_bootstrap.jar" -C "$boot_dir" . && \
        info "StepServerLauncher compiled" || \
        die "Failed to compile StepServerLauncher (STEP server cannot start)"
        rm -rf "$boot_dir"
    fi

    # --- Extract JREs for all architectures (PojavLauncher) ---
    info "Copying JREs for all architectures..."
    for arch in "${JRE_ARCHS[@]}"; do
        local abi_var="JRE_ABI_MAP_${arch}"
        local abi="${!abi_var}"
        local jre_tar="$DOWNLOADS_DIR/jre17-${arch}.tar.xz"
        local extract_dir="$JRE_EXTRACT_DIR/$arch"

        if [[ ! -d "$extract_dir" ]]; then
            info "Extracting jre17-${arch}.tar.xz..."
            mkdir -p "$extract_dir"
            tar xf "$jre_tar" -C "$extract_dir"
        fi

        local jre_source
        jre_source=$(find "$extract_dir" -name "java" -type f 2>/dev/null | head -1 || true)
        [[ -z "$jre_source" ]] && die "Could not find java binary for $arch"
        jre_source="$(dirname "$(dirname "$jre_source")")"
        info "JRE $arch → $abi: $(du -sh "$jre_source" | cut -f1)"

        mkdir -p "$ASSETS_DIR/jre/$abi"
        cp -a "$jre_source"/* "$ASSETS_DIR/jre/$abi/"
        find "$ASSETS_DIR/jre/$abi" -name "*.debuginfo" -exec rm -f {} + 2>/dev/null || true
        rm -rf "$ASSETS_DIR/jre/$abi/demo" \
               "$ASSETS_DIR/jre/$abi/man" "$ASSETS_DIR/jre/$abi/include" \
               "$ASSETS_DIR/jre/$abi/src.zip" 2>/dev/null || true
        find "$ASSETS_DIR/jre/$abi/bin" -type f ! -name "java" -exec rm -f {} + 2>/dev/null || true
    done

    # --- Archive STEP data into single tar for fast first-launch extraction ---
    info "Archiving STEP data into step.tar..."
    cd "$ASSETS_DIR"
    if tar --format=gnu -cf "step.tar" step/ 2>/dev/null; then
        rm -rf step/
        info "step.tar: $(du -h step.tar | cut -f1)"
    else
        info "Warning: failed to create tar, keeping individual files"
    fi
    cd "$SCRIPT_DIR"

    info "STEP size: $(du -sh "$ASSETS_DIR/step" 2>/dev/null || echo "archived")"
    for d in "$ASSETS_DIR/jre"/*/; do
        info "  JRE $(basename "$d"): $(du -sh "$d" | cut -f1)"
    done

    echo "Extraction complete" > "$SCRIPT_DIR/.extracted"
    info "Extraction complete"
}

phase_build() {
    info "=== Phase: Build APK ==="

    setup_jdk
    setup_android_sdk
    setup_gradle
    [[ ! -f "$SCRIPT_DIR/.extracted" ]] && phase_extract

    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
    echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/local.properties"

    local gradle_props=""
    if [[ -n "${JAVA_HOME:-}" ]]; then
        gradle_props="-Dorg.gradle.java.home=$JAVA_HOME"
    fi

    info "Building APK..."
    local gradle_task="assemble${BUILD_TYPE}"
    cd "$SCRIPT_DIR"
    if [[ -x "./gradlew" ]]; then
        JAVA_HOME="${JAVA_HOME:-}" ./gradlew "$gradle_props" "$gradle_task"
    else
        gradle $gradle_props "$gradle_task"
    fi

    local apk_dir="$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
    for apk in "$SCRIPT_DIR/app/build/outputs/apk/$apk_dir/"*.apk; do
        info "APK generated: $(basename "$apk") (size: $(du -h "$apk" | cut -f1))"
    done
}

setup_emulator() {
    local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$SDK_DIR}}"
    local sdkmanager
    sdkmanager=$(find "$sdk_dir/cmdline-tools" -name "sdkmanager" -type f 2>/dev/null | head -1 || true)
    [[ -z "$sdkmanager" ]] && return

    # Install emulator binary if missing
    if [[ ! -f "$sdk_dir/emulator/emulator" ]]; then
        info "Installing Android emulator..."
        echo "y" | "$sdkmanager" --sdk_root="$sdk_dir" "emulator" 2>&1 | grep -v "^\[=\|Warning:" || true
    fi

    # Install system image if missing
    local sysimg="system-images;android-34;google_apis;x86_64"
    if ! "$sdkmanager" --sdk_root="$sdk_dir" --list_installed 2>/dev/null | grep -q "$sysimg"; then
        info "Downloading system image (~1.2GB, this may take a while)..."
        echo "y" | "$sdkmanager" --sdk_root="$sdk_dir" "$sysimg" 2>&1 | grep -v "^\[=\|Warning:" || true
    fi

    # Create AVD if missing
    local avdmanager
    avdmanager=$(find "$sdk_dir/cmdline-tools" -name "avdmanager" -type f 2>/dev/null | head -1 || true)
    if [[ -n "$avdmanager" ]]; then
        local existing
        existing=$("$avdmanager" list avd 2>/dev/null | grep -oP "Name: \Kstep_test" | head -1)
        if [[ -z "$existing" ]]; then
            info "Creating AVD 'step_test'..."
            echo "no" | "$avdmanager" create avd -n step_test -k "$sysimg" -d "pixel" 2>&1 | grep -v "^\[=\|Warning:" || true
        fi
    fi

    info "Emulator ready"
}

phase_setup() {
    info "=== Phase: Setup ==="
    setup_jdk
    setup_android_sdk
    setup_emulator
    setup_gradle
    info "Setup complete"
}

phase_system_image() {
    setup_android_sdk
    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
    local sdkmanager
    sdkmanager=$(find "$ANDROID_HOME/cmdline-tools" -name "sdkmanager" -type f 2>/dev/null | head -1 || true)
    [[ -z "$sdkmanager" ]] && die "sdkmanager not found"
    local system_image="system-images;android-34;google_apis;x86_64"
    info "Downloading system image (1.2GB)..."
    yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" "$system_image" 2>&1 | grep -v "^\[=\|Warning:" || true
    info "System image ready"
}

phase_run() {
    info "=== Phase: Run ==="
    local apk_dir="$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
    local apk
    apk=$(find "$SCRIPT_DIR/app/build/outputs/apk/$apk_dir" -name '*.apk' | head -1)
    [[ -f "$apk" ]] || die "No APK found. Run './build.sh build' first."

    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export ANDROID_AVD_HOME="$SCRIPT_DIR/build-cache/avd"
    export ANDROID_SDK_HOME="$SCRIPT_DIR/build-cache"

    local emulator="$ANDROID_HOME/emulator/emulator"
    local adb="$ANDROID_HOME/platform-tools/adb"
    local avdmanager
    avdmanager=$(find "$ANDROID_HOME/cmdline-tools" -name "avdmanager" -type f 2>/dev/null | head -1 || true)
    local sdkmanager
    sdkmanager=$(find "$ANDROID_HOME/cmdline-tools" -name "sdkmanager" -type f 2>/dev/null | head -1 || true)
    local avd_name="step_test"
    local system_image="system-images;android-34;google_apis;x86_64"

    [[ -f "$emulator" ]] || die "Emulator not found. Run './build.sh setup' first."
    [[ -f "$adb" ]] || die "ADB not found. Run './build.sh setup' first."
    [[ -f "$avdmanager" ]] || die "avdmanager not found. Run './build.sh setup' first."

    # Ensure system image
    if ! "$sdkmanager" --sdk_root="$ANDROID_HOME" --list 2>/dev/null | grep -q "$system_image"; then
        info "Downloading system image (this is large)..."
        yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" "$system_image" | grep -v "^\[=" || true
    fi

    # Create AVD if missing (always inside repo, never ~/.android)
    local existing_avd
    existing_avd=$("$avdmanager" list avd 2>/dev/null | grep -oP "Name: \K$avd_name" | head -1)
    if [[ -z "$existing_avd" ]]; then
        info "Creating AVD '$avd_name'..."
        rm -rf "$ANDROID_AVD_HOME" 2>/dev/null
        mkdir -p "$ANDROID_AVD_HOME"
        # avdmanager stores relative paths based on its SDK root detection.
        # Force the correct absolute path in the config.
        echo no | "$avdmanager" create avd -n "$avd_name" -k "$system_image" -d pixel_6 --force 2>&1 | \
            grep -v "^Warning:" || die "AVD creation failed"
        local avd_config="$ANDROID_AVD_HOME/${avd_name}.avd/config.ini"
        if [[ -f "$avd_config" ]]; then
            sed -i "s|^image\.sysdir\.1=.*|image.sysdir.1=system-images/android-34/google_apis/x86_64/|" "$avd_config"
        fi
    fi

    # Check if already running
    local already_running=false
    if "$adb" get-state 2>/dev/null; then
        already_running=true
    fi

    if ! $already_running; then
        info "Starting emulator (this takes ~30s)..."
        "$emulator" -avd "$avd_name" -no-audio -no-window -netdelay none -netspeed full &
        EMULATOR_PID=$!

        info "Waiting for device..."
        "$adb" wait-for-device 2>/dev/null || true
        # Additional wait for boot completion
        local boot_completed=""
        for i in $(seq 1 60); do
            boot_completed=$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
            if [[ "$boot_completed" == "1" ]]; then
                info "Device booted"
                break
            fi
            sleep 2
        done
        if [[ "$boot_completed" != "1" ]]; then
            info "Device may not have fully booted, continuing anyway..."
        fi
    else
        info "Emulator already running"
    fi

    # Install APK
    info "Installing APK..."
    "$adb" install -r "$apk" 2>&1 || die "Install failed"
    info "APK installed"

    # Launch
    info "Launching STEP Bible..."
    "$adb" shell am start -n "com.eratverbum.stepbible/.MainActivity" 2>/dev/null

    info "Running. Use './build.sh stop' to kill the emulator."
    info "Or run: $adb logcat | grep StepServer"
}

phase_stop() {
    info "=== Phase: Stop ==="
    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
    local adb="$ANDROID_HOME/platform-tools/adb"
    "$adb" emu kill 2>/dev/null || true
    "$adb" kill-server 2>/dev/null || true
    info "Emulator stopped"
}

phase_log() {
    info "=== Phase: Logcat ==="
    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
    local adb="$ANDROID_HOME/platform-tools/adb"
    "$adb" logcat -v time | grep -E "StepServer|WebView|SystemWebChromeClient"
}

phase_all() {
    phase_setup
    phase_download
    phase_extract
    phase_build
}

usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  download    Download STEP .deb and JRE .debs (all 4 archs)"
    echo "  extract     Extract debs and prepare assets"
    echo "  build       Build debug APK (default)"
    echo "  build release Build release APK (signed)"
    echo "  setup       Install JDK 21, Android SDK, Gradle"
    echo "  system-image Download Android 34 x86_64 system image for emulator"
    echo "  clean       Remove all downloaded and extracted files"
    echo "  all         Run all phases (default)"
    exit 0
}

case "${1:-all}" in
    download)  phase_download ;;
    extract)   phase_extract ;;
    build)     [[ "${2:-}" == "release" ]] && BUILD_TYPE="Release"; phase_build ;;
    run)       phase_run ;;
    stop)      phase_stop ;;
    log)       phase_log ;;
    setup)     phase_setup ;;
    system-image) phase_system_image ;;
    clean)     clean ;;
    all)       phase_all ;;
    *)         usage ;;
esac
