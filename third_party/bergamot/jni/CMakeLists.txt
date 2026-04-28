cmake_minimum_required(VERSION 3.18)
project(bergamot-translator-jni CXX)

# This CMakeLists.txt is not invoked from the bergamot-translator build above.
# Instead, run the helper script `scripts/bergamot/build-jni-android-arm64.sh`
# AFTER the main build is finished. That script invokes cmake with
# BERGAMOT_BUILD_DIR pointing to ../build-android-arm64-v8a/.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT BERGAMOT_BUILD_DIR)
    message(FATAL_ERROR "BERGAMOT_BUILD_DIR must be set (the cmake build dir of bergamot-translator)")
endif()
if(NOT BERGAMOT_SRC_DIR)
    message(FATAL_ERROR "BERGAMOT_SRC_DIR must be set (the bergamot-translator source root)")
endif()

# Headers exposed by bergamot-translator and marian-dev. We need both because
# our wrapper instantiates TranslationModel directly.
# Note: parser.h does `#include "3rd_party/marian-dev/src/3rd_party/CLI/CLI.hpp"`
# (relative to the bergamot project root), so we add ${BERGAMOT_SRC_DIR} too.
include_directories(
    ${BERGAMOT_SRC_DIR}
    ${BERGAMOT_SRC_DIR}/src
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src/3rd_party
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src/3rd_party/CLI
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src/3rd_party/sentencepiece
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src/3rd_party/yaml-cpp/include
    ${BERGAMOT_SRC_DIR}/3rd_party/marian-dev/src/3rd_party/spdlog/include
    ${BERGAMOT_SRC_DIR}/3rd_party/ssplit-cpp/src
    ${BERGAMOT_SRC_DIR}/3rd_party/ssplit-cpp/src/ssplit
    ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src
    ${BERGAMOT_BUILD_DIR}/src/translator
)

# Match marian's compile-time defines so headers behave consistently.
add_compile_definitions(
    USE_SENTENCEPIECE
    _USE_INTERNAL_STRING_VIEW
    ARM
    FMA
    SSE
)

add_library(bergamot-translator-jni SHARED
    src/bergamot_jni.cpp
)

# Order matters for static-link: dependents first, providers last. ssplit pulls
# in pcre2-8; bergamot-translator pulls in marian; marian pulls in
# sentencepiece, yaml-cpp, pathie, faiss, ruy stack and zlib.
#
# We use --whole-archive on the bergamot/marian static libs so that internally-
# referenced symbols (e.g. virtual destructors, factory registrations) survive
# linking even though our JNI surface only touches a tiny subset.
target_link_libraries(bergamot-translator-jni
    PRIVATE
        -Wl,--whole-archive
        ${BERGAMOT_BUILD_DIR}/src/translator/libbergamot-translator.a
        -Wl,--no-whole-archive
        ${BERGAMOT_BUILD_DIR}/libssplit.a
        ${BERGAMOT_BUILD_DIR}/lib/libpcre2-8.a
        # libmarian.a already bundles yaml-cpp, pathie-cpp, zlib, and faiss as
        # OBJECT libraries, so we don't link them separately.
        ${BERGAMOT_BUILD_DIR}/libmarian.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/sentencepiece/src/libsentencepiece.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/sentencepiece/src/libsentencepiece_train.a
        # Ruy GEMM kernels (ARM-only paths get used at runtime, but all
        # archives contain stubs/pack functions and must be linked).
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_frontend.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_context.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_context_get_ctx.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_ctx.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_trmul.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_thread_pool.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_blocking_counter.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_block_map.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_allocator.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_kernel_arm.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_kernel_avx.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_kernel_avx2_fma.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_kernel_avx512.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_pack_arm.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_pack_avx.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_pack_avx2_fma.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_pack_avx512.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_have_built_path_for_avx.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_have_built_path_for_avx2_fma.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_have_built_path_for_avx512.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_apply_multiplier.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_cpuinfo.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_denormal.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_prepacked_cache.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_prepare_packed_matrices.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_system_aligned_alloc.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_tune.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/libruy_wait.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/ruy/profiler/libruy_profiler_instrumentation.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/third_party/cpuinfo/libcpuinfo.a
        ${BERGAMOT_BUILD_DIR}/3rd_party/marian-dev/src/3rd_party/ruy/third_party/cpuinfo/deps/clog/libclog.a
        # Android: log lib (for __android_log_print)
        log
        # Android: c++_shared comes from NDK; libc++abi/libunwind are linked statically by NDK
)

# Strip release builds aggressively so the .so doesn't blow up.
target_link_options(bergamot-translator-jni PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,--exclude-libs,ALL
)
