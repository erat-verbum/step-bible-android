#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

: "${STEP_DEB_URL:=""}"
: "${JRE_DEB_URL:=""}"
: "${JRE_VERSION:="17.0.19"}"
: "${GRADLE_VERSION:="9.5.1"}"
: "${TERMUX_MIRROR:="https://termux.librehat.com/apt/termux-main/pool/main/o/openjdk-17"}"

STEP_DEB_DIR="$SCRIPT_DIR/step-data"
JRE_DEB_DIR="$SCRIPT_DIR/jre-data"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64)     JRE_ARCH="aarch64" ;;
    armv7l|armhf|arm)  JRE_ARCH="arm" ;;
    i686|x86)          JRE_ARCH="i686" ;;
    x86_64|amd64)      JRE_ARCH="x86_64" ;;
    *) echo "Unknown arch: $ARCH"; exit 1 ;;
esac

info()  { echo "  -> $*"; }
die()   { echo "ERROR: $*" >&2; exit 1; }

clean() {
    rm -rf "$STEP_DEB_DIR" "$JRE_DEB_DIR" "$ASSETS_DIR/jre" "$ASSETS_DIR/step"
}

detect_step_version() {
    if [[ -n "$STEP_DEB_URL" ]]; then
        STEP_DEB="$STEP_DEB_DIR/$(basename "$STEP_DEB_URL")"
        return
    fi
    info "Detecting latest STEP version from dev.stepbible.org..."
    local page
    page=$(curl -sL "https://dev.stepbible.org/downloads/")
    local latest
    latest=$(echo "$page" | grep -oP 'stepbible_\d+_\d+_\d+\.deb' | sort -t_ -k2 -V | tail -1)
    [[ -z "$latest" ]] && die "Could not detect STEP version"
    STEP_DEB_URL="https://dev.stepbible.org/downloads/$latest"
    STEP_DEB="$STEP_DEB_DIR/$latest"
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
    curl -fSL "$url" -o "$dest" || die "Download failed: $url"
}

extract_deb() {
    local deb="$1" target="$2"
    info "Extracting $(basename "$deb")..."
    mkdir -p "$target"
    local tmpDir
    tmpDir=$(mktemp -d)
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
    cp -a ./* "$target/" 2>/dev/null || true
    cd "$SCRIPT_DIR"
    rm -rf "$tmpDir"
}

collect_jars() {
    local dir="$1"
    find "$dir" -name '*.jar' -type f 2>/dev/null | sort -u
}

setup_jdk() {
    local jdk_dir="$SCRIPT_DIR/.jdk21"
    local jdk_home
    jdk_home=$(ls -d "$jdk_dir"/jdk-21* 2>/dev/null | head -1)
    if [[ -n "$jdk_home" ]] && [[ -x "$jdk_home/bin/java" ]]; then
        export JAVA_HOME="$jdk_home"
        info "JDK 21 already at $jdk_home"
        return
    fi
    info "Downloading JDK 21..."
    mkdir -p "$jdk_dir"
    curl -fSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      -o "$jdk_dir/jdk21.tar.gz" || die "Failed to download JDK 21"
    tar -xzf "$jdk_dir/jdk21.tar.gz" -C "$jdk_dir/"
    jdk_home=$(ls -d "$jdk_dir"/jdk-21* 2>/dev/null | head -1)
    [[ -z "$jdk_home" ]] && die "JDK 21 extraction failed"
    export JAVA_HOME="$jdk_home"
    info "JDK 21 ready at $jdk_home"
}

setup_android_sdk() {
    local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$SCRIPT_DIR/.android-sdk}}"
    export ANDROID_HOME="$sdk_dir"

    if [[ -x "$sdk_dir/platform-tools/adb" ]] && [[ -d "$sdk_dir/platforms/android-34" ]]; then
        info "Android SDK already set up at $sdk_dir"
        return
    fi

    info "Setting up Android SDK at $sdk_dir..."
    mkdir -p "$sdk_dir"

    local cmdline_tools_zip="$sdk_dir/cmdline-tools.zip"
    if [[ ! -f "$cmdline_tools_zip" ]]; then
        local url="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        curl -fSL "$url" -o "$cmdline_tools_zip" || die "Failed to download cmdline-tools"
    fi
    unzip -qo "$cmdline_tools_zip" -d "$sdk_dir/" 2>/dev/null

    local sdkmanager="$sdk_dir/cmdline-tools/bin/sdkmanager"
    if [[ ! -f "$sdkmanager" ]]; then
        mkdir -p "$sdk_dir/cmdline-tools/latest"
        mv "$sdk_dir/cmdline-tools/bin" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
        mv "$sdk_dir/cmdline-tools/lib" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
        sdkmanager="$sdk_dir/cmdline-tools/latest/bin/sdkmanager"
    fi

    chmod +x "$sdkmanager"
    yes | "$sdkmanager" --sdk_root="$sdk_dir" "platforms;android-34" "build-tools;34.0.0" "platform-tools" | grep -v "^\[=" || true

    echo "sdk.dir=$sdk_dir" > "$SCRIPT_DIR/local.properties"
    info "Android SDK ready at $sdk_dir"
}

setup_gradle() {
    if [[ -x "$SCRIPT_DIR/gradlew" ]]; then return; fi
    info "Setting up Gradle wrapper..."
    if command -v gradle &>/dev/null; then
        gradle wrapper --gradle-version "$GRADLE_VERSION"
    else
        local gradle_url="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
        local gradle_zip="$SCRIPT_DIR/.gradle/gradle-${GRADLE_VERSION}-bin.zip"
        mkdir -p "$SCRIPT_DIR/.gradle"
        if [[ ! -f "$gradle_zip" ]]; then
            curl -fSL "$gradle_url" -o "$gradle_zip" || die "Failed to download Gradle"
        fi
        unzip -qo "$gradle_zip" -d "$SCRIPT_DIR/.gradle/" 2>/dev/null
        local gradle_home="$SCRIPT_DIR/.gradle/gradle-${GRADLE_VERSION}"
        "$gradle_home/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"
    fi
}

phase_download() {
    info "=== Phase: Download ==="
    detect_step_version
    JRE_DEB_URL="${JRE_DEB_URL:-${TERMUX_MIRROR}/openjdk-17_${JRE_VERSION}_${JRE_ARCH}.deb}"

    mkdir -p "$STEP_DEB_DIR" "$JRE_DEB_DIR"
    download "$STEP_DEB_URL" "$STEP_DEB"
    download "$JRE_DEB_URL" "$JRE_DEB_DIR/$(basename "$JRE_DEB_URL")"

    echo "STEP_DEB=$STEP_DEB" > "$SCRIPT_DIR/.build-vars"
    echo "JRE_DEB=$JRE_DEB_DIR/$(basename "$JRE_DEB_URL")" >> "$SCRIPT_DIR/.build-vars"
    echo "STEP_VERSION=$(echo "$STEP_DEB" | grep -oP '\d+_\d+_\d+' | head -1)" >> "$SCRIPT_DIR/.build-vars"
    info "Download complete"
}

phase_extract() {
    info "=== Phase: Extract ==="
    [[ -f "$SCRIPT_DIR/.build-vars" ]] || phase_download
    source "$SCRIPT_DIR/.build-vars"

    rm -rf "$ASSETS_DIR/jre" "$ASSETS_DIR/step"
    mkdir -p "$ASSETS_DIR/jre" "$ASSETS_DIR/step"

    local step_extract_dir="$STEP_DEB_DIR/extracted"
    local jre_extract_dir="$JRE_DEB_DIR/extracted"

    if [[ ! -d "$step_extract_dir" ]]; then
        extract_deb "$STEP_DEB" "$step_extract_dir"
    fi
    if [[ ! -d "$jre_extract_dir" ]]; then
        extract_deb "$JRE_DEB" "$jre_extract_dir"
    fi

    # --- Copy JRE ---
    info "Copying JRE..."
    local jre_source
    jre_source=$(find "$jre_extract_dir" -name "java" -type f 2>/dev/null | head -1)
    [[ -z "$jre_source" ]] && die "Could not find java binary in extracted JRE"
    jre_source="$(dirname "$(dirname "$jre_source")")"
    info "JRE root: $jre_source"
    cp -a "$jre_source"/* "$ASSETS_DIR/jre/"
    rm -rf "$ASSETS_DIR/jre/jmods" "$ASSETS_DIR/jre/demo" \
           "$ASSETS_DIR/jre/man" "$ASSETS_DIR/jre/include" \
           "$ASSETS_DIR/jre/src.zip" "$ASSETS_DIR/jre/javafx-src.zip" 2>/dev/null || true
    find "$ASSETS_DIR/jre/bin" -type f ! -name "java" -exec rm -f {} + 2>/dev/null || true

    # --- Copy STEP ---
    info "Copying STEP files..."
    local step_root
    step_root=$(find "$step_extract_dir" -maxdepth 5 -name "step-server-*.jar" -type f 2>/dev/null | head -1)
    [[ -z "$step_root" ]] && die "Could not find STEP server JAR in extracted package"
    step_root="$(dirname "$step_root")"
    info "STEP root: $step_root"

    cp -a "$step_root"/* "$ASSETS_DIR/step/"
    rm -rf "$ASSETS_DIR/step/jre" "$ASSETS_DIR/step/.install4j" \
           "$ASSETS_DIR/step/logs" "$ASSETS_DIR/step/runStep.sh" \
           "$ASSETS_DIR/step/post-install.sh" 2>/dev/null || true

    info "JRE size: $(du -sh "$ASSETS_DIR/jre" | cut -f1)"
    info "STEP size: $(du -sh "$ASSETS_DIR/step" | cut -f1)"
    info "JAR count: $(find "$ASSETS_DIR/step" -name '*.jar' | wc -l)"

    echo "Extraction complete" > "$SCRIPT_DIR/.extracted"
    info "Extraction complete"
}

phase_build() {
    info "=== Phase: Build APK ==="
    [[ ! -f "$SCRIPT_DIR/.extracted" ]] && phase_extract

    setup_jdk
    setup_android_sdk
    setup_gradle

    export ANDROID_HOME="${ANDROID_HOME:-$SCRIPT_DIR/.android-sdk}"
    if [[ ! -f "$SCRIPT_DIR/local.properties" ]]; then
        echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/local.properties"
    fi

    local gradle_props=""
    if [[ -n "${JAVA_HOME:-}" ]]; then
        gradle_props="-Dorg.gradle.java.home=$JAVA_HOME"
    fi

    info "Building APK..."
    cd "$SCRIPT_DIR"
    if [[ -x "./gradlew" ]]; then
        JAVA_HOME="${JAVA_HOME:-}" ./gradlew $gradle_props assembleDebug
    else
        gradle $gradle_props assembleDebug
    fi

    local apk
    apk=$(find "$SCRIPT_DIR/app/build/outputs/apk/debug" -name '*.apk' | head -1)
    if [[ -f "$apk" ]]; then
        info "APK generated: $apk"
        info "Size: $(du -h "$apk" | cut -f1)"
    else
        die "APK not found at expected path"
    fi
}

phase_all() {
    phase_setup
    phase_download
    phase_extract
    phase_build
}

phase_setup() {
    info "=== Phase: Setup ==="
    setup_jdk
    setup_android_sdk
    setup_gradle
    info "Setup complete"
}

usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  download    Download STEP .deb and JRE .deb"
    echo "  extract     Extract debs and prepare assets"
    echo "  build       Build APK (runs extract first if needed)"
    echo "  setup       Install Android SDK and Gradle"
    echo "  clean       Remove downloaded/extracted files"
    echo "  all         Run all phases (default)"
    exit 0
}

case "${1:-all}" in
    download)  phase_download ;;
    extract)   phase_extract ;;
    build)     phase_build ;;
    setup)     phase_setup ;;
    clean)     clean ;;
    all)       phase_all ;;
    *)         usage ;;
esac
