#!/usr/bin/env bash
#
# Cross-compile bergamot-translator (and its deps) for Android arm64-v8a.
#
# Output: third_party/bergamot/build-android-arm64-v8a/
#         - all marian/bergamot static libs (.a) ready to be linked into a JNI .so
#
# Requirements:
#   ANDROID_NDK env var pointing at the NDK root, OR
#   /Users/wenyuan/Library/Android/sdk/ndk/<ver> auto-detected.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
BERGAMOT_ROOT="${REPO_ROOT}/third_party/bergamot"
SRC_DIR="${BERGAMOT_ROOT}/src"
BUILD_DIR="${BERGAMOT_ROOT}/build-android-arm64-v8a"

# --- locate NDK -------------------------------------------------------------
if [ -z "${ANDROID_NDK:-}" ]; then
  if [ -d /Users/wenyuan/Library/Android/sdk/ndk/26.3.11579264 ]; then
    ANDROID_NDK=/Users/wenyuan/Library/Android/sdk/ndk/26.3.11579264
  else
    # pick the highest installed NDK
    candidate=$(ls -d /Users/wenyuan/Library/Android/sdk/ndk/* 2>/dev/null | sort | tail -1 || true)
    if [ -n "$candidate" ]; then
      ANDROID_NDK="$candidate"
    fi
  fi
fi

if [ -z "${ANDROID_NDK:-}" ] || [ ! -d "$ANDROID_NDK" ]; then
  echo "ERROR: ANDROID_NDK not set / found." >&2
  exit 1
fi

echo "Using ANDROID_NDK = $ANDROID_NDK"
echo "Source dir       = $SRC_DIR"
echo "Build dir        = $BUILD_DIR"

# --- apply patches ----------------------------------------------------------
# bergamot ships an upstream patch for macOS/glibc that we also want on Android.
# Re-apply it idempotently.
pushd "$SRC_DIR" > /dev/null
if [ -f patches/01-marian-fstream-for-macos.patch ]; then
  if ! git -C "$SRC_DIR" apply --check patches/01-marian-fstream-for-macos.patch 2>/dev/null; then
    echo "Patch 01-marian-fstream-for-macos.patch already applied (or already merged)."
  else
    git -C "$SRC_DIR" apply patches/01-marian-fstream-for-macos.patch
    echo "Applied patches/01-marian-fstream-for-macos.patch"
  fi
fi
# Apply our own Android-specific patch (created next to this script).
# These changes live in the marian-dev submodule, so apply from inside it.
ANDROID_PATCH="${REPO_ROOT}/scripts/bergamot/android-patches.patch"
MARIAN_DIR="${SRC_DIR}/3rd_party/marian-dev"
if [ -f "$ANDROID_PATCH" ] && [ -d "$MARIAN_DIR/.git" -o -f "$MARIAN_DIR/.git" ]; then
  if git -C "$MARIAN_DIR" apply --check "$ANDROID_PATCH" 2>/dev/null; then
    git -C "$MARIAN_DIR" apply "$ANDROID_PATCH"
    echo "Applied $(basename "$ANDROID_PATCH")"
  else
    echo "Android patch already applied or no longer needed."
  fi
fi
popd > /dev/null

# --- configure --------------------------------------------------------------
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Critical flags:
#   COMPILE_CUDA=OFF             - obviously no CUDA on Android
#   USE_FBGEMM=OFF               - fbgemm is x86-only
#   USE_INTGEMM=OFF              - x86-only; arm uses gemmology/ruy
#   USE_RUY=ON                   - SGEMM/int8 GEMM via Ruy on ARM
#   USE_RUY_SGEMM=ON             - use Ruy for fp32 GEMM too (no BLAS available)
#   USE_MKL=OFF                  - no MKL
#   USE_APPLE_ACCELERATE=OFF     - no Accelerate
#   USE_SENTENCEPIECE=ON         - bundled, statically built
#   USE_STATIC_LIBS=ON           - all .a, only the final JNI .so is dynamic
#   SSPLIT_USE_INTERNAL_PCRE2=ON - download+build PCRE2 ourselves
#   SSPLIT_COMPILE_LIBRARY_ONLY=ON
#   COMPILE_LIBRARY_ONLY=ON      - skip marian-decoder/server binaries that need
#                                  things like signal.h SIGSEGV handlers / iostream-binary
#   BUILD_ARCH=armv8-a           - tells marian's "-march=${BUILD_ARCH}" to emit
#                                  -march=armv8-a (instead of native, which would
#                                  read the *host* CPU on a Mac)
#   COMPILE_TESTS=OFF, COMPILE_UNIT_TESTS=OFF
#   USE_THREADS=ON, COMPILE_WITHOUT_EXCEPTIONS=OFF (we are NOT WASM)

# Make sure we don't reuse a stale CMakeCache from a different toolchain.
if [ -f CMakeCache.txt ]; then
  if ! grep -q "CMAKE_TOOLCHAIN_FILE.*android.toolchain.cmake" CMakeCache.txt; then
    echo "Stale CMakeCache.txt found from a non-Android build; removing."
    rm -rf CMakeCache.txt CMakeFiles
  fi
fi

cmake \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_ARCH=armv8-a \
    -DCOMPILE_CUDA=OFF \
    -DUSE_FBGEMM=OFF \
    -DUSE_INTGEMM=OFF \
    -DUSE_RUY=ON \
    -DUSE_RUY_SGEMM=ON \
    -DUSE_MKL=OFF \
    -DUSE_APPLE_ACCELERATE=OFF \
    -DUSE_SENTENCEPIECE=ON \
    -DUSE_STATIC_LIBS=ON \
    -DUSE_THREADS=ON \
    -DCOMPILE_LIBRARY_ONLY=ON \
    -DCOMPILE_TESTS=OFF \
    -DCOMPILE_UNIT_TESTS=OFF \
    -DCOMPILE_WASM=OFF \
    -DUSE_WASM_COMPATIBLE_SOURCE=OFF \
    -DSSPLIT_USE_INTERNAL_PCRE2=ON \
    -DSSPLIT_COMPILE_LIBRARY_ONLY=ON \
    -DSPM_ENABLE_TCMALLOC=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$SRC_DIR"

# --- build ------------------------------------------------------------------
NCPU=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)
cmake --build . -- -j"${NCPU}" bergamot-translator marian ssplit sentencepiece-static

echo
echo "Built static libraries:"
find . -maxdepth 6 -name "libbergamot-translator.a" -o -name "libmarian.a" -o -name "libssplit.a" -o -name "libsentencepiece.a" -o -name "libsentencepiece-static.a" -o -name "libpcre2-8.a" -o -name "libruy*.a" -o -name "libcpuinfo.a" -o -name "libpathie-cpp.a" -o -name "libyaml-cpp.a" -o -name "libzlib.a" -o -name "libzlibstatic.a" -o -name "libfaiss.a" 2>/dev/null | sort
