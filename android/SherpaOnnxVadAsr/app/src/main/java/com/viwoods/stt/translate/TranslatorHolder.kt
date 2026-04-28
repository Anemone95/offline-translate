package com.viwoods.stt.translate

import android.content.Context
import android.util.Log
import com.viwoods.stt.bergamot.BergamotTranslator
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Process-wide owner of the BergamotTranslator instance and the worker
// thread that serialises engine calls (the JNI is not thread-safe).
//
// MainActivity (translate tab) and TranslateService (AIDL) both go through
// this object so the model is loaded once per process and reused across
// in-app and external callers.
object TranslatorHolder {
    private const val TAG = "TranslatorHolder"
    private const val MODEL_DIR = "bergamot-enzh"
    private val ASSETS = listOf(
        "model.enzh.intgemm.alphas.bin",
        "lex.50.50.enzh.s2t.bin",
        "srcvocab.enzh.spm",
        "trgvocab.enzh.spm",
    )

    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bergamot-translate").apply { isDaemon = true }
    }

    @Volatile
    private var translator: BergamotTranslator? = null

    val isReady: Boolean get() = translator != null

    @Synchronized
    fun ensureLoaded(context: Context): BergamotTranslator {
        translator?.let { return it }
        val dir = copyAssets(context.applicationContext)
        return BergamotTranslator(dir.absolutePath).also { translator = it }
    }

    private fun copyAssets(context: Context): File {
        val outDir = File(context.filesDir, MODEL_DIR)
        if (!outDir.exists()) outDir.mkdirs()
        for (name in ASSETS) {
            val out = File(outDir, name)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("$MODEL_DIR/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Log.d(TAG, "copied asset $name (${out.length() / 1024} KB)")
        }
        return outDir
    }
}
