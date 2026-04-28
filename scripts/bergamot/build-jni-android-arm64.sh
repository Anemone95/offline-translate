#!/usr/bin/env bash
# Builds libbergamot-translator-jni.so for Android arm64-v8a.
#
# Prerequisite: scripts/bergamot/build-android-arm64.sh has been run first to
# produce the static libraries under third_party/bergamot/build-android-arm64-v8a/.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
BERGAMOT_ROOT="${REPO_ROOT}/third_party/bergamot"
SRC_DIR="${BERGAMOT_ROOT}/src"
BUILD_DEPS_DIR="${BERGAMOT_ROOT}/build-android-arm64-v8a"
JNI_BUILD_DIR="${BERGAMOT_ROOT}/build-jni-android-arm64-v8a"

if [ ! -f "${BUILD_DEPS_DIR}/src/translator/libbergamot-translator.a" ]; then
    echo "ERROR: bergamot static libs missing. Run scripts/bergamot/build-android-arm64.sh first." >&2
    exit 1
fi

if [ -z "${ANDROID_NDK:-}" ]; then
    if [ -d /Users/wenyuan/Library/Android/sdk/ndk/26.3.11579264 ]; then
        ANDROID_NDK=/Users/wenyuan/Library/Android/sdk/ndk/26.3.11579264
    else
        ANDROID_NDK=$(ls -d /Users/wenyuan/Library/Android/sdk/ndk/* 2>/dev/null | sort | tail -1 || true)
    fi
fi
if [ ! -d "${ANDROID_NDK:-}" ]; then
    echo "ERROR: ANDROID_NDK not found." >&2
    exit 1
fi

echo "Using ANDROID_NDK = $ANDROID_NDK"
echo "Build dir         = $JNI_BUILD_DIR"

mkdir -p "$JNI_BUILD_DIR"
cd "$JNI_BUILD_DIR"

cmake \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBERGAMOT_SRC_DIR="$SRC_DIR" \
    -DBERGAMOT_BUILD_DIR="$BUILD_DEPS_DIR" \
    "${BERGAMOT_ROOT}/jni"

NCPU=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)
cmake --build . -- -j"${NCPU}"

echo
echo "Output:"
ls -lh "${JNI_BUILD_DIR}/libbergamot-translator-jni.so"

# Stage a deployable folder with .so + libc++_shared.so for convenience.
DEPLOY="${BERGAMOT_ROOT}/jniLibs/arm64-v8a"
mkdir -p "$DEPLOY"
cp -fv "${JNI_BUILD_DIR}/libbergamot-translator-jni.so" "$DEPLOY/"
cp -fv "${ANDROID_NDK}/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$DEPLOY/" || true
ls -lh "$DEPLOY"
