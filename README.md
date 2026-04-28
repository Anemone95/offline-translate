# offline-translate / viwoods STT

On-device speech-to-text + English→Chinese translation for the Viwoods
AiPaper (3.8 GB-RAM, e-ink, MT6771). Bundles:

- **ASR** — sherpa-onnx (SenseVoice for offline multilingual paragraphs;
  Zipformer for streaming Chinese / English / Korean / French / Spanish /
  German / Russian).
- **Translation** — Mozilla Bergamot (`en→zh`), cross-compiled to
  arm64-v8a. ~640 ms model load (one-time per process), ~200 ms per
  short sentence after that.

The translation engine is exposed as an **AIDL service** so other apps on
the same device (e.g. `zotero-android`, `notes2`) can translate without
launching the STT UI.

---

## Calling the translation service from another app

The service lives in `net.wenyuanxu.translate` under
`net.wenyuanxu.translate.TranslateService`. No permission is required —
any app on the device can bind, as long as it has been granted package
visibility for `net.wenyuanxu.translate` (Android 11+ requires this; see step 2
below).

### 1. Copy the AIDL files into the calling app

Two files, package `net.wenyuanxu.translate`, must be placed under the
caller's `app/src/main/aidl/net/wenyuanxu/translate/`:

- `ITranslator.aidl`
- `ITranslateCallback.aidl`

The canonical copies are at
`android/SherpaOnnxVadAsr/app/src/main/aidl/net/wenyuanxu/translate/`
in this repo.

In the caller's `app/build.gradle` enable AIDL:
```groovy
android {
    buildFeatures { aidl true }
}
```

### 2. Caller manifest

```xml
<!-- Android 11+: declare visibility of the host package -->
<queries>
    <package android:name="net.wenyuanxu.translate" />
</queries>
```

### 3. Bind and call

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import net.wenyuanxu.translate.ITranslator
import net.wenyuanxu.translate.ITranslateCallback

class TranslatorClient(private val ctx: Context) {
    private var svc: ITranslator? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            svc = ITranslator.Stub.asInterface(b).also { it.warmUp() }
        }
        override fun onServiceDisconnected(name: ComponentName) { svc = null }
    }

    fun bind() {
        val i = Intent("net.wenyuanxu.translate.action.BIND").apply {
            setPackage("net.wenyuanxu.translate")
        }
        ctx.bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        ctx.unbindService(conn)
        svc = null
    }

    // Synchronous; call from a worker thread. Returns null if not yet bound.
    fun translate(text: String): String? = svc?.translate(text, "en", "zh")

    // Async; result delivered on a binder thread. Returns a token for cancel().
    fun translateAsync(text: String, onResult: (String) -> Unit, onError: (Int, String) -> Unit): Long? {
        val s = svc ?: return null
        return s.translateAsync(text, "en", "zh", object : ITranslateCallback.Stub() {
            override fun onResult(translation: String) = onResult(translation)
            override fun onError(code: Int, message: String) = onError(code, message)
        })
    }
}
```

### Interface summary

| Method | Notes |
| --- | --- |
| `String translate(text, srcLang, tgtLang)` | Blocking. Throws `RemoteException` (message prefixed with error code) on failure. |
| `long translateAsync(text, srcLang, tgtLang, callback)` | Returns a token; callback fires once. Returns `0` if rejected synchronously. |
| `void cancel(token)` | No-op if already finished. |
| `List<String> getSupportedPairs()` | Currently `["en-zh"]`. |
| `void warmUp()` | Async eager model load. Idempotent. |
| `boolean isReady()` | Whether the model is in memory. |

Error codes (`net.wenyuanxu.translate.TranslateErrors`):

| Code | Constant | Meaning |
| --- | --- | --- |
| 0 | `OK` | success |
| 1 | `ERR_UNSUPPORTED_PAIR` | `srcLang`/`tgtLang` not in `getSupportedPairs()` |
| 2 | `ERR_MODEL_LOAD` | model file missing / corrupt |
| 3 | `ERR_ENGINE` | bergamot internal error |
| 4 | `ERR_CANCELLED` | `cancel()` called or future interrupted |
| 5 | `ERR_EMPTY_INPUT` | `text` was null or empty |

### Threading

- AIDL methods run on the service's binder thread pool; the engine itself
  is single-threaded internally (`Executors.newSingleThreadExecutor`),
  so concurrent callers are serialised automatically.
- `translate()` blocks until the work finishes — call it from a worker
  thread on the caller side or use `translateAsync()`.
- The model stays loaded as long as the service's process is alive.
  Android may kill the process under memory pressure; the next bind will
  reload (~640 ms).

### Quick end-to-end check

```
adb shell pm dump net.wenyuanxu.translate | grep -A 1 TranslateService
# expected: filter with action "net.wenyuanxu.translate.action.BIND"
```

---

## Repo layout

```
android/SherpaOnnxVadAsr/        # the actual app (AIDL service lives here)
    app/src/main/aidl/net/wenyuanxu/translate/
    app/src/main/java/net/wenyuanxu/translate/
sherpa-onnx/                     # submodule, sparse-checkout of kotlin-api
third_party/bergamot/            # bergamot-translator JNI wrapper + build
scripts/bergamot/                # cross-compile scripts
scripts/translate/               # abandoned ONNX route, kept for reference
```
