package net.wenyuanxu.translate.bergamot

/**
 * Thin Kotlin wrapper around the bergamot-translator JNI .so.
 *
 * Usage:
 *   val t = BergamotTranslator("/data/local/tmp/bergamot/enzh")
 *   val zh = t.translate("Hello world")
 *   t.close()
 *
 * `modelDir` must contain Mozilla Firefox-translations files for one language
 * pair: `model.<...>.intgemm.alphas.bin` (or `.intgemm.bin` / `.bin`),
 * `lex.50.50.<src><tgt>.s2t.bin`, and `vocab.<...>.spm`.
 *
 * NOT thread-safe: the underlying BlockingService is serialised by an
 * internal mutex on the C++ side, but you should not call translate() and
 * close() concurrently.
 */
class BergamotTranslator(modelDir: String) {

    private var handle: Long = nativeNew(modelDir)

    init {
        require(handle != 0L) { "Failed to load Bergamot model from $modelDir" }
    }

    fun translate(text: String): String {
        check(handle != 0L) { "Translator is closed" }
        return nativeTranslate(handle, text)
    }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    protected fun finalize() {
        close()
    }

    companion object {
        init {
            // libc++_shared comes from the NDK and is shipped alongside our .so.
            // sherpa-onnx already loads its own libc++_shared, so this is a no-op
            // in the existing app. For a standalone test, both names are valid.
            try {
                System.loadLibrary("c++_shared")
            } catch (_: UnsatisfiedLinkError) {
                // already loaded by another module - fine
            }
            System.loadLibrary("bergamot-translator-jni")
        }

        @JvmStatic external fun nativeNew(modelDir: String): Long
        @JvmStatic external fun nativeTranslate(handle: Long, text: String): String
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
