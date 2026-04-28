package com.viwoods.stt.translate

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class TranslateService : Service() {

    private val tokenSeq = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Future<*>>()

    override fun onBind(intent: Intent?): IBinder = stub

    private val stub = object : ITranslator.Stub() {

        override fun translate(text: String?, srcLang: String?, tgtLang: String?): String {
            val src = text.orEmpty()
            if (src.isEmpty()) throwRemote(TranslateErrors.ERR_EMPTY_INPUT, "text is empty")
            if (!isPairSupported(srcLang, tgtLang)) {
                throwRemote(TranslateErrors.ERR_UNSUPPORTED_PAIR, "$srcLang-$tgtLang")
            }
            return try {
                TranslatorHolder.executor.submit<String> {
                    TranslatorHolder.ensureLoaded(applicationContext).translate(src)
                }.get()
            } catch (e: Throwable) {
                Log.e(TAG, "translate failed", e)
                throwRemote(TranslateErrors.ERR_ENGINE, e.message ?: "engine error")
            }
        }

        override fun translateAsync(
            text: String?,
            srcLang: String?,
            tgtLang: String?,
            cb: ITranslateCallback?,
        ): Long {
            cb ?: return 0L
            val src = text.orEmpty()
            if (src.isEmpty()) {
                safeError(cb, TranslateErrors.ERR_EMPTY_INPUT, "text is empty")
                return 0L
            }
            if (!isPairSupported(srcLang, tgtLang)) {
                safeError(cb, TranslateErrors.ERR_UNSUPPORTED_PAIR, "$srcLang-$tgtLang")
                return 0L
            }
            val token = tokenSeq.getAndIncrement()
            val future = TranslatorHolder.executor.submit {
                try {
                    val out = TranslatorHolder.ensureLoaded(applicationContext).translate(src)
                    safeResult(cb, out)
                } catch (e: InterruptedException) {
                    safeError(cb, TranslateErrors.ERR_CANCELLED, "cancelled")
                } catch (e: Throwable) {
                    Log.e(TAG, "translateAsync failed", e)
                    safeError(cb, TranslateErrors.ERR_ENGINE, e.message ?: "engine error")
                } finally {
                    pending.remove(token)
                }
            }
            pending[token] = future
            return token
        }

        override fun cancel(token: Long) {
            pending.remove(token)?.cancel(true)
        }

        override fun getSupportedPairs(): MutableList<String> = mutableListOf(PAIR_EN_ZH)

        override fun warmUp() {
            TranslatorHolder.executor.execute {
                runCatching { TranslatorHolder.ensureLoaded(applicationContext) }
                    .onFailure { Log.e(TAG, "warmUp failed", it) }
            }
        }

        override fun isReady(): Boolean = TranslatorHolder.isReady

        private fun isPairSupported(src: String?, tgt: String?): Boolean =
            src == "en" && tgt == "zh"

        private fun safeResult(cb: ITranslateCallback, text: String) {
            try { cb.onResult(text) } catch (e: RemoteException) {
                Log.w(TAG, "callback dead: ${e.message}")
            }
        }

        private fun safeError(cb: ITranslateCallback, code: Int, msg: String) {
            try { cb.onError(code, msg) } catch (e: RemoteException) {
                Log.w(TAG, "callback dead: ${e.message}")
            }
        }

        private fun throwRemote(code: Int, msg: String): Nothing {
            throw RemoteException("$code: $msg")
        }
    }

    companion object {
        private const val TAG = "TranslateService"
        private const val PAIR_EN_ZH = "en-zh"
    }
}
