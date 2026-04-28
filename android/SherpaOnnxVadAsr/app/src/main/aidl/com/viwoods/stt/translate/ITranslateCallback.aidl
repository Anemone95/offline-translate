package com.viwoods.stt.translate;

interface ITranslateCallback {
    // Fires exactly once on success.
    void onResult(String translation);

    // Fires exactly once on failure. `code` is one of TranslateErrors.ERR_*.
    void onError(int code, String message);
}
