package net.wenyuanxu.translate.bergamot

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * On-device smoke test for libbergamot-translator-jni.so.
 *
 * The test expects a Mozilla en->zh model directory to be present on the
 * device under one of:
 *   /sdcard/bergamot/enzh
 *   /data/local/tmp/bergamot/enzh
 * Push it manually before running, e.g.:
 *   adb push models/bergamot/enzh /sdcard/bergamot/enzh
 *
 * To run:
 *   ./gradlew :app:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BergamotTranslatorInstrumentedTest {

    @Test
    fun loadModel_translateOneSentence_returnsNonEmptyChinese() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidates = listOf(
            "/sdcard/bergamot/enzh",
            "/data/local/tmp/bergamot/enzh",
            File(ctx.filesDir, "bergamot/enzh").absolutePath,
        )
        val modelDir = candidates.firstOrNull { File(it).isDirectory }
            ?: error(
                "No bergamot model dir found. Push one of: $candidates. " +
                    "See third_party/bergamot/README.md for download instructions."
            )
        Log.i("BergamotTest", "Using model dir: $modelDir")

        val translator: BergamotTranslator
        val ctorMs = measureTimeMillis { translator = BergamotTranslator(modelDir) }
        Log.i("BergamotTest", "Constructor took ${ctorMs}ms")
        try {
            val output: String
            val tMs = measureTimeMillis { output = translator.translate("Hello world.") }
            Log.i("BergamotTest", "Translation took ${tMs}ms; output=\"$output\"")
            assertNotNull(output)
            assertTrue("Empty output from translate()", output.isNotBlank())
        } finally {
            translator.close()
        }
    }
}
