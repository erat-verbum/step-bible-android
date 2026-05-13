#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

: "${STEP_DEB_URL:=""}"
: "${JRE_VERSION:="17.0.19"}"
: "${GRADLE_VERSION:="9.5.1"}"
: "${TERMUX_MIRROR:="https://termux.librehat.com/apt/termux-main/pool/main/o/openjdk-17"}"

CACHE_DIR="$SCRIPT_DIR/build-cache"
DOWNLOADS_DIR="$CACHE_DIR/downloads"
STEP_EXTRACT_DIR="$CACHE_DIR/step-extracted"
JRE_EXTRACT_DIR="$CACHE_DIR/jre-extracted"
JDK_DIR="$CACHE_DIR/jdk"
SDK_DIR="$CACHE_DIR/android-sdk"
GRADLE_DIR="$CACHE_DIR/gradle"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

JRE_ARCHS=("aarch64" "arm" "i686" "x86_64")
JRE_ABI_MAP_aarch64="arm64-v8a"
JRE_ABI_MAP_arm="armeabi-v7a"
JRE_ABI_MAP_i686="x86"
JRE_ABI_MAP_x86_64="x86_64"

info()  { echo "  -> $*"; }
die()   { echo "ERROR: $*" >&2; exit 1; }

clean() {
    rm -rf "$CACHE_DIR" "$ASSETS_DIR/jre" "$ASSETS_DIR/step"
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

setup_jdk() {
    local jdk_home
    jdk_home=$(find "$JDK_DIR" -maxdepth 1 -type d -name "jdk-21*" 2>/dev/null | head -1)
    if [[ -n "$jdk_home" ]] && [[ -x "$jdk_home/bin/java" ]]; then
        export JAVA_HOME="$jdk_home"
        info "JDK 21 already at $jdk_home"
        return
    fi
    info "Downloading JDK 21..."
    mkdir -p "$JDK_DIR"
    curl -SL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      -o "$JDK_DIR/jdk21.tar.gz" || die "Failed to download JDK 21"
    tar -xzf "$JDK_DIR/jdk21.tar.gz" -C "$JDK_DIR/"
    jdk_home=$(find "$JDK_DIR" -maxdepth 1 -type d -name "jdk-21*" 2>/dev/null | head -1)
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

    local sdkmanager="$sdk_dir/cmdline-tools/bin/sdkmanager"
    if [[ ! -f "$sdkmanager" ]]; then
        mkdir -p "$sdk_dir/cmdline-tools/latest"
        mv "$sdk_dir/cmdline-tools/bin" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
        mv "$sdk_dir/cmdline-tools/lib" "$sdk_dir/cmdline-tools/latest/" 2>/dev/null || true
        sdkmanager="$sdk_dir/cmdline-tools/latest/bin/sdkmanager"
    fi

    chmod +x "$sdkmanager"
    yes | "$sdkmanager" --sdk_root="$sdk_dir" \
        "platforms;android-34" "build-tools;34.0.0" "platform-tools" | grep -v "^\[=" || true

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
        local url="${TERMUX_MIRROR}/openjdk-17_${JRE_VERSION}_${arch}.deb"
        download "$url" "$DOWNLOADS_DIR/openjdk-17_${JRE_VERSION}_${arch}.deb"
    done

    echo "STEP_DEB=$STEP_DEB" > "$SCRIPT_DIR/.build-vars"
    echo "STEP_VERSION=$(echo "$STEP_DEB" | grep -oP '\d+_\d+_\d+' | head -1)" >> "$SCRIPT_DIR/.build-vars"
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
    step_root=$(find "$STEP_EXTRACT_DIR" -maxdepth 5 -name "step-server-*.jar" -type f 2>/dev/null | head -1)
    [[ -z "$step_root" ]] && die "Could not find STEP server JAR in extracted package"
    step_root="$(dirname "$step_root")"
    info "STEP root: $step_root"

    cp -a "$step_root"/* "$ASSETS_DIR/step/"
    rm -rf "$ASSETS_DIR/step/jre" "$ASSETS_DIR/step/.install4j" \
           "$ASSETS_DIR/step/logs" "$ASSETS_DIR/step/runStep.sh" \
           "$ASSETS_DIR/step/post-install.sh" 2>/dev/null || true

    # --- Extract JREs for all architectures ---
    info "Copying JREs for all architectures..."
    for arch in "${JRE_ARCHS[@]}"; do
        local abi_var="JRE_ABI_MAP_${arch}"
        local abi="${!abi_var}"
        local jre_deb="$DOWNLOADS_DIR/openjdk-17_${JRE_VERSION}_${arch}.deb"
        local extract_dir="$JRE_EXTRACT_DIR/$arch"

        if [[ ! -d "$extract_dir" ]]; then
            extract_deb "$jre_deb" "$extract_dir"
        fi

        local jre_source
        jre_source=$(find "$extract_dir" -name "java" -type f 2>/dev/null | head -1)
        [[ -z "$jre_source" ]] && die "Could not find java binary for $arch"
        jre_source="$(dirname "$(dirname "$jre_source")")"
        info "JRE $arch → $abi: $(du -sh "$jre_source" | cut -f1)"

        mkdir -p "$ASSETS_DIR/jre/$abi"
        cp -a "$jre_source"/* "$ASSETS_DIR/jre/$abi/"
        rm -rf "$ASSETS_DIR/jre/$abi/jmods" "$ASSETS_DIR/jre/$abi/demo" \
               "$ASSETS_DIR/jre/$abi/man" "$ASSETS_DIR/jre/$abi/include" \
               "$ASSETS_DIR/jre/$abi/src.zip" 2>/dev/null || true
        find "$ASSETS_DIR/jre/$abi/bin" -type f ! -name "java" -exec rm -f {} + 2>/dev/null || true
    done

    info "STEP size: $(du -sh "$ASSETS_DIR/step" | cut -f1)"
    for d in "$ASSETS_DIR/jre"/*/; do
        info "  JRE $(basename "$d"): $(du -sh "$d" | cut -f1)"
    done

    echo "Extraction complete" > "$SCRIPT_DIR/.extracted"
    info "Extraction complete"
}

phase_build() {
    info "=== Phase: Build APK ==="
    [[ ! -f "$SCRIPT_DIR/.extracted" ]] && phase_extract

    setup_jdk
    setup_android_sdk
    setup_gradle

    export ANDROID_HOME="${ANDROID_HOME:-$SDK_DIR}"
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

phase_setup() {
    info "=== Phase: Setup ==="
    setup_jdk
    setup_android_sdk
    setup_gradle
    info "Setup complete"
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
    echo "  build       Build APK (runs extract first if needed)"
    echo "  setup       Install JDK 21, Android SDK, Gradle"
    echo "  clean       Remove all downloaded and extracted files"
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
