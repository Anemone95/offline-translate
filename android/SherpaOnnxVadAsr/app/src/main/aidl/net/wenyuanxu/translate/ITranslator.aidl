// On-device translation service exposed by net.wenyuanxu.translate.
// Stable interface; new methods MUST be appended at the end to keep
// older callers binary-compatible.
package net.wenyuanxu.translate;

import net.wenyuanxu.translate.ITranslateCallback;

interface ITranslator {
    // Synchronous translate. Caller MUST be on a worker thread —
    // this method blocks while the JNI engine runs.
    // Throws RemoteException on engine error or invalid args; the message
    // is prefixed with one of the ERR_* codes in TranslateErrors.
    String translate(String text, String srcLang, String tgtLang);

    // Async translate. Returns a token usable with cancel(); 0 means the
    // call was rejected synchronously (callback already received onError).
    // The callback fires exactly once on a binder thread.
    long translateAsync(String text, String srcLang, String tgtLang,
                        in ITranslateCallback callback);

    // Cancel an in-flight translateAsync. No-op if already finished.
    void cancel(long token);

    // Currently supported language pairs as "<src>-<tgt>", e.g. ["en-zh"].
    List<String> getSupportedPairs();

    // Eagerly load the model in the background. Returns immediately.
    // Idempotent — safe to call repeatedly.
    void warmUp();

    // Whether the model is loaded and the next translate() will be fast.
    boolean isReady();
}
