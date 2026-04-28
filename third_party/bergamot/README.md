# bergamot-translator for Android arm64-v8a

Cross-compile of [Mozilla / browsermt's bergamot-translator][upstream] (Marian
NMT runtime + ssplit + sentencepiece) into a single self-contained
`libbergamot-translator-jni.so` for the **arm64-v8a** Android ABI, plus a thin
JNI wrapper class.

[upstream]: https://github.com/browsermt/bergamot-translator

This is the on-device English-to-Chinese translation evaluation track for the
viwoods STT app. Models are ~25 MB (vs ~225 MB for OPUS-MT or ~600 MB for
NLLB-600M); quality is intentionally lower in exchange for speed and footprint
on a 3.8 GB-RAM e-ink tablet.

---

## What's here

| Path | What it is |
| ---- | ---------- |
| `src/` | Submoduleless clone of `browsermt/bergamot-translator` at commit `9271618` (subprojects: `marian-dev` `2781d735`, `ssplit-cpp`, `pybind11`). |
| `build-android-arm64-v8a/` | CMake build dir for the upstream static-lib build. Contains `libbergamot-translator.a`, `libmarian.a`, etc. |
| `jni/` | Our JNI wrapper sources. `jni/CMakeLists.txt` produces `libbergamot-translator-jni.so` by linking the static libs above. |
| `build-jni-android-arm64-v8a/` | CMake build dir for the JNI .so. |
| `jniLibs/arm64-v8a/` | Final deployable: `libbergamot-translator-jni.so` (7.6 MB stripped) + `libc++_shared.so`. Drop these into any Android app's `app/src/main/jniLibs/arm64-v8a/`. |
| `android-test/` | Standalone Gradle project that runs `BergamotTranslatorInstrumentedTest` on a connected device. Does NOT modify or depend on the existing `android/SherpaOnnxVadAsr/` app. |
| `logs/` | Saved build logs from each iteration. |

| `../scripts/bergamot/build-android-arm64.sh` | Builds all the upstream static libs (~2 minutes on M1, parallel). |
| `../scripts/bergamot/build-jni-android-arm64.sh` | Links those into the JNI .so and stages it under `jniLibs/`. |
| `../scripts/bergamot/android-patches.patch` | The Android-specific source patches we applied to `marian-dev`'s submoduled `pathie-cpp`. |

---

## Build environment used

| Tool | Version |
| ---- | ------- |
| Android NDK | **r26d** (`26.3.11579264`), darwin-x86_64 toolchain (works on Apple Silicon via Rosetta) |
| Android API | `android-24` (matches `minSdk 24` on the existing app) |
| ANDROID_ABI | `arm64-v8a` only |
| ANDROID_STL | `c++_shared` |
| CMake | 3.30.1 (Homebrew) |
| Host | macOS 15.6 / Apple Silicon |
| Build time | ~2 minutes (8 parallel jobs) for the static libs, <30 s for the JNI .so |

---

## Build commands

```sh
# 1. (one-time) install the NDK if you don't have it. The build script
#    auto-detects /Users/wenyuan/Library/Android/sdk/ndk/26.3.11579264/.
#    To use a different NDK, set ANDROID_NDK=/path/to/ndk before running.

# 2. clone bergamot-translator + submodules into third_party/bergamot/src/
git clone --recursive --depth 1 \
    https://github.com/browsermt/bergamot-translator.git \
    third_party/bergamot/src

# 3. build the static libs (marian, ssplit, bergamot-translator, pcre2, ...)
./scripts/bergamot/build-android-arm64.sh

# 4. build the JNI .so that pulls them all together
./scripts/bergamot/build-jni-android-arm64.sh
```

The two scripts apply `scripts/bergamot/android-patches.patch` automatically
(idempotent). Logs go to `third_party/bergamot/logs/`.

---

## Where the .so ends up

```
third_party/bergamot/jniLibs/arm64-v8a/
├── libbergamot-translator-jni.so   # 7.6 MB, fully self-contained
└── libc++_shared.so                # 1.7 MB, NDK runtime
```

To integrate into `android/SherpaOnnxVadAsr/`, copy both files into
`android/SherpaOnnxVadAsr/app/src/main/jniLibs/arm64-v8a/` (alongside
`libsherpa-onnx-jni.so` and `libonnxruntime.so`). The two `libc++_shared.so`
files from sherpa-onnx and bergamot are byte-identical (same NDK).

---

## CMake invocation (full)

The build script wraps a single `cmake` invocation. Reproduced here for
reference:

```sh
cmake \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
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
    third_party/bergamot/src

cmake --build . -j8 \
    bergamot-translator marian ssplit sentencepiece-static
```

Key choices:

* `BUILD_ARCH=armv8-a` overrides marian's default `BUILD_ARCH=native`, which
  on a Mac would compile `-march=native` flags reading the **host** CPU
  (Apple Silicon ARM with NEON+SVE) — useless when targeting the Helio P60
  Cortex-A73/A53.
* `USE_INTGEMM=OFF` because intgemm is x86-only (AVX2 / AVX512). Marian's
  CMake auto-detects ARM via `cmake/TargetArch.cmake` and substitutes Ruy's
  ARM NEON kernels, but we set the flag explicitly anyway.
* `USE_RUY=ON` and `USE_RUY_SGEMM=ON`. Without `USE_RUY_SGEMM`, marian falls
  back to a stub SGEMM and dies at first matmul on devices without
  Apple Accelerate / MKL / OpenBLAS — i.e. all Android phones.
* `SSPLIT_USE_INTERNAL_PCRE2=ON` downloads and builds PCRE2 10.39
  in-tree. Otherwise the build looks for a system `libpcre2-8.so` that no
  Android device ships.
* `SPM_ENABLE_TCMALLOC=OFF` avoids dragging in a malloc replacement that
  doesn't compile cleanly on Bionic.
* `COMPILE_LIBRARY_ONLY=ON` skips marian-decoder/marian-server CLI
  binaries — they pull in signal handling code that needs `siginfo_t`
  members not exposed at older API levels.

---

## Patches we had to apply

Saved as `scripts/bergamot/android-patches.patch`. Applied to the
`marian-dev` submodule (i.e. `third_party/bergamot/src/3rd_party/marian-dev/`),
**not** to bergamot's top-level repo.

Two surface defects in the submoduled `pathie-cpp`:

1. **`Path::glob` references `glob()` / `globfree()`**, which Bionic only
   exposes at API ≥ 28. Our `minSdk` is 24. Stub the function out — it's
   never called from marian or bergamot.
2. **`Pathie::convert_encodings` and the `nl_langinfo(CODESET)` calls** rely
   on `iconv` and `nl_langinfo`, which Bionic doesn't ship at all. Force
   `PATHIE_ASSUME_UTF8_ON_UNIX` and stub the function. Android filesystem is
   always UTF-8, so the conversion is a no-op anyway.

The full diff:

```diff
diff --git a/src/3rd_party/pathie-cpp/src/path.cpp b/src/3rd_party/pathie-cpp/src/path.cpp
--- a/src/3rd_party/pathie-cpp/src/path.cpp
+++ b/src/3rd_party/pathie-cpp/src/path.cpp
@@ -61,7 +61,9 @@
 #include <sys/types.h>
 #include <sys/param.h> // defines "BSD" macro on BSD systems
 #include <pwd.h>
+#if !defined(__ANDROID__)
 #include <glob.h>
+#endif
 #include <fnmatch.h>

 #else
@@ -3173,6 +3175,12 @@
 std::vector<Path> Path::glob(const std::string& pattern, int flags /* = 0 */)
 {
+#if defined(__ANDROID__)
+  // Android NDK only exposes glob()/globfree() at API >= 28.
+  // Path::glob is not used by marian/bergamot at runtime, so we just
+  // stub it out to keep our minSdk at 24.
+  (void)pattern; (void)flags;
+  return std::vector<Path>();
+#elif defined(_PATHIE_UNIX)
-#if defined(_PATHIE_UNIX)
   std::string nstr = utf8_to_filename(pattern);
   glob_t globinfo;

diff --git a/src/3rd_party/pathie-cpp/src/pathie.cpp b/src/3rd_party/pathie-cpp/src/pathie.cpp
--- a/src/3rd_party/pathie-cpp/src/pathie.cpp
+++ b/src/3rd_party/pathie-cpp/src/pathie.cpp
@@ -82,8 +82,16 @@
 #include <cstring>
 #include <cstdlib>
 #include <errno.h>
+// Android Bionic ships neither iconv.h nor <langinfo.h>'s nl_langinfo() at
+// reasonable API levels (<28). Treat the filesystem encoding as UTF-8, which
+// is the only encoding ever used on Android, and skip those headers.
+#if defined(__ANDROID__) && !defined(PATHIE_ASSUME_UTF8_ON_UNIX)
+#define PATHIE_ASSUME_UTF8_ON_UNIX 1
+#endif
+#if !defined(__ANDROID__)
 #include <iconv.h>
 #include <langinfo.h>
+#endif
 #include <sys/param.h> // defines "BSD" macro on BSD systems

@@ -112,6 +120,13 @@
 std::string Pathie::convert_encodings(const char* from_encoding, const char* to_encoding, const std::string& string)
 {
+#if defined(__ANDROID__)
+  // Android: filesystem is always UTF-8 (PATHIE_ASSUME_UTF8_ON_UNIX is set
+  // above), so callers never reach this function with non-equal encodings.
+  // Just return the string as-is.
+  (void)from_encoding; (void)to_encoding;
+  return string;
+#else
   size_t input_length = string.length();
@@ -187,6 +202,7 @@
   std::string result(outbuf, count);
   free(outbuf);

   return result;
+#endif // __ANDROID__
 }
```

bergamot's own `patches/01-marian-fstream-for-macos.patch` (in
`src/patches/`) is also re-applied if not already merged — it's needed
on Android too because Bionic provides XSI-style `strerror_r()` rather
than the GNU style.

---

## Where the .so ends up (final size breakdown)

```
$ ls -lh third_party/bergamot/build-jni-android-arm64-v8a/libbergamot-translator-jni.so
-rwxr-xr-x  1 wenyuan  staff  7.6M Apr 28 13:53 libbergamot-translator-jni.so

$ file third_party/bergamot/build-jni-android-arm64-v8a/libbergamot-translator-jni.so
ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV),
dynamically linked, BuildID[sha1]=..., stripped

$ llvm-readelf --dyn-syms libbergamot-translator-jni.so | grep Java_
   380: ...  4820 FUNC  GLOBAL DEFAULT  Java_net_wenyuanxu_translate_bergamot_BergamotTranslator_nativeNew
   402: ...  1328 FUNC  GLOBAL DEFAULT  Java_net_wenyuanxu_translate_bergamot_BergamotTranslator_nativeTranslate
   465: ...   116 FUNC  GLOBAL DEFAULT  Java_net_wenyuanxu_translate_bergamot_BergamotTranslator_nativeClose
```

7.6 MB stripped is the cost of: marian's transformer kernels, sentencepiece
tokenizer, ssplit + pcre2 sentence splitter, ruy GEMM kernels (ARM NEON +
the x86 variants we couldn't strip cleanly), faiss shortlist lookup,
yaml-cpp, zlib, pathie-cpp.

---

## Java/Kotlin API

Defined in `jni/kotlin/BergamotTranslator.kt`:

```kotlin
class BergamotTranslator(modelDir: String) {
    fun translate(text: String): String
    fun close()
}
```

`modelDir` must contain Mozilla Firefox-translations files for one language
pair, e.g. `en→zh`:

```
enzh/
├── model.enzh.intgemm.alphas.bin   (~25 MB)
├── lex.50.50.enzh.s2t.bin          (~6 MB)
└── vocab.enzh.spm                  (~800 KB)
```

The wrapper synthesises a marian YAML config at runtime from those filenames
(see `jni/src/bergamot_jni.cpp::makeBergamotConfigYaml`) — you don't have to
ship a `config.yml`. The selected `gemm-precision` switches between
`int8shiftAll` and `int8shiftAlphaAll` based on whether the model filename
contains `.alphas`.

---

## How to download Mozilla en→zh model files

Mozilla's released models live at https://github.com/mozilla/firefox-translations-models/.

For en→zh specifically (note: Mozilla doesn't officially ship en→zh as of
April 2026; the easiest path is the community-trained model from
[browsermt/students][students] or to use `zh→en` and pivot):

[students]: https://github.com/browsermt/students

```sh
mkdir -p models/bergamot/enzh
cd models/bergamot/enzh

# Mozilla zhen (zh -> en) released model:
BASE='https://github.com/mozilla/firefox-translations-models/raw/main/models/prod/zhen'
curl -L -o model.zhen.intgemm.alphas.bin.gz "$BASE/model.zhen.intgemm.alphas.bin.gz"
curl -L -o lex.50.50.zhen.s2t.bin.gz       "$BASE/lex.50.50.zhen.s2t.bin.gz"
curl -L -o vocab.zhen.spm.gz               "$BASE/vocab.zhen.spm.gz"
gunzip *.gz
# (the wrapper auto-detects the model.*.bin / lex.*.bin / vocab.*.spm trio)
```

For en→zh you'll need a non-Mozilla source — for example the
`opusmt-en-zh` model converted via [browsermt's training pipeline][students],
or any pre-converted Bergamot-format `.bin` files (e.g. from the
[MTranServer model zoo](https://github.com/xxnuo/MTranServer/blob/main/MODELS.md)).

---

## Standalone test (deliverable #3)

`third_party/bergamot/android-test/` is a tiny standalone Gradle project
that loads the .so and translates one sentence on a connected device.
**It does NOT depend on `android/SherpaOnnxVadAsr/`.**

```sh
# 1. push a model dir to the device
adb push models/bergamot/zhen /sdcard/bergamot/enzh

# 2. run the instrumentation test
cd third_party/bergamot/android-test
./gradlew :app:connectedAndroidTest      # if/when you add a wrapper
# or open in Android Studio and run BergamotTranslatorInstrumentedTest
```

The test:
1. Looks for a model dir under `/sdcard/bergamot/enzh`,
   `/data/local/tmp/bergamot/enzh`, or app `filesDir/bergamot/enzh`.
2. Constructs `BergamotTranslator(modelDir)` (logs construction time).
3. Calls `translate("Hello world.")` (logs translation time + output).
4. Asserts the output is non-empty.
5. Calls `close()`.

---

## Concrete next step for integration into MainActivity

(Don't do this yet — listed for the next task.)

1. Copy
   `third_party/bergamot/jniLibs/arm64-v8a/libbergamot-translator-jni.so`
   into
   `android/SherpaOnnxVadAsr/app/src/main/jniLibs/arm64-v8a/libbergamot-translator-jni.so`.
   `libc++_shared.so` is already present from sherpa-onnx — don't copy
   bergamot's again, they are byte-identical.
2. Copy
   `third_party/bergamot/jni/kotlin/BergamotTranslator.kt`
   into
   `android/SherpaOnnxVadAsr/app/src/main/java/net/wenyuanxu/translate/bergamot/BergamotTranslator.kt`.
   Note the existing OPUS-MT `Translator.kt` lives under
   `com.k2fsa.sherpa.onnx`; the bergamot wrapper deliberately uses
   `net.wenyuanxu.translate.bergamot` so the two can coexist.
3. In `MainActivity.kt`, add a "Bergamot" toggle next to the existing OPUS-MT
   one. On startup, copy the Bergamot model files out of `assets/` (or from
   downloaded storage) into a writable directory and pass that path to
   `BergamotTranslator(modelDir)`.
4. No `build.gradle` changes are strictly required — the existing
   `ndk { abiFilters 'arm64-v8a' }` and `useLegacyPackaging = true`
   settings handle the new .so as-is. If `libc++_shared.so` causes a
   `pickFirsts` warning, mirror what's already done for `libonnxruntime.so`.

---

## Things known not to work / TODO

* **No CPU-feature-gated Ruy kernels for older A53 micro-architectures**
  beyond what Ruy already auto-detects. The Helio P60's Cortex-A73 has
  NEON+VFPv4 which Ruy supports out of the box. Cortex-A53 fallback is
  the same. No action needed but worth profiling.
* **Single-threaded.** We use `BlockingService` with `cpu-threads: 0`.
  An e-ink tablet typically translates one block at a time after STT,
  so this matches usage. If we ever go multi-threaded, switch to
  `AsyncService` and bump `--cpu-threads`.
* **No quality estimation / alignment.** Disabled in
  `ResponseOptions` to keep the API surface minimal.
* **Models are not bundled.** They have to be pushed via `adb push` or
  downloaded at first launch. ~32 MB per direction.
