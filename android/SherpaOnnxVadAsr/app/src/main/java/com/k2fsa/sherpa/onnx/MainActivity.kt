package com.k2fsa.sherpa.onnx.vad.asr

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.tabs.TabLayout
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.Vad
import com.viwoods.stt.bergamot.BergamotTranslator
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "viwoods-stt"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
private const val TAB_SPEECH = 0
private const val TAB_TRANSLATE = 1

private enum class ModelMode(val labelRes: Int, val onlineType: Int?) {
    SENSE_VOICE(R.string.model_sense_voice, null),
    ZIPFORMER_ZH(R.string.model_zipformer_zh, 9),
    ZIPFORMER_EN(R.string.model_zipformer_en, 6),
    ZIPFORMER_KO(R.string.model_zipformer_ko, 14),
    ZIPFORMER_FR(R.string.model_zipformer_fr, 23),
    ZIPFORMER_ES(R.string.model_zipformer_es, 22),
    ZIPFORMER_DE(R.string.model_zipformer_de, 24),
    ZIPFORMER_RU(R.string.model_zipformer_ru, 26),
}

// Model ids in kotlin-api/OfflineRecognizer.kt::getOfflineModelConfig.
private const val OFFLINE_TYPE_SENSE_VOICE = 15

// 4 threads = MT6771's perf cluster (Cortex-A73). Using all 8 hits the slow A53s.
private const val ASR_NUM_THREADS = 4

// Throttle UI updates so e-ink isn't redrawn on every streaming partial.
private const val UI_REFRESH_MIN_INTERVAL_MS = 350L

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var modelSpinner: Spinner

    private var vad: Vad? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private val onlineRecognizers = mutableMapOf<ModelMode, OnlineRecognizer>()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private var currentMode: ModelMode = ModelMode.SENSE_VOICE
    private var lastText: String = ""

    // Models whose tokens are uppercase Latin — downcase for readability.
    private val latinUppercaseModes = setOf(ModelMode.ZIPFORMER_EN)

    @Volatile
    private var isRecording: Boolean = false

    private var currentTab: Int = TAB_SPEECH
    private var translator: BergamotTranslator? = null
    private val translatorExecutor = Executors.newSingleThreadExecutor()
    private lateinit var translationView: TextView

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.e(TAG, "Audio record permission denied")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        transcriptView = findViewById(R.id.my_text)
        transcriptScroll = findViewById(R.id.transcript_scroll)
        translationView = findViewById(R.id.translation_text)

        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { onPrimaryButtonClick() }

        modelSpinner = findViewById(R.id.model_spinner)
        setupSpinner()

        setupTabs()

        transcriptView.text = getString(R.string.loading)

        // Default mode is SenseVoice — preload it so the user can start immediately.
        loadMode(currentMode, isInitial = true)
    }

    private fun setupTabs() {
        val tabLayout: TabLayout = findViewById(R.id.tab_layout)
        val transcriptLabel: TextView = findViewById(R.id.transcript_label)
        val titles = listOf(
            getString(R.string.tab_speech),
            getString(R.string.tab_translate),
        )
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            val custom = layoutInflater.inflate(R.layout.tab_item, tabLayout, false) as TextView
            custom.text = titles[i]
            tab.customView = custom
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                onTabChanged(tab.position, transcriptLabel)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun onTabChanged(position: Int, transcriptLabel: TextView) {
        if (position == TAB_TRANSLATE && isRecording) {
            // Stop recording before switching modes.
            onPrimaryButtonClick()
        }
        currentTab = position
        applySpinnerForTab(position)
        when (position) {
            TAB_SPEECH -> {
                transcriptLabel.setText(R.string.label_transcript)
                transcriptView.isFocusable = false
                transcriptView.isFocusableInTouchMode = false
                transcriptView.isCursorVisible = false
                if (transcriptView.text.isNullOrEmpty()) {
                    transcriptView.setText(R.string.hint)
                }
                recordButton.setText(if (isRecording) R.string.stop else R.string.start)
            }
            TAB_TRANSLATE -> {
                transcriptLabel.setText(R.string.label_original)
                transcriptView.isFocusable = true
                transcriptView.isFocusableInTouchMode = true
                transcriptView.isCursorVisible = true
                transcriptView.hint = getString(R.string.translate_hint)
                // Clear ASR-mode placeholder.
                if (transcriptView.text?.toString() == getString(R.string.hint) ||
                    transcriptView.text?.toString() == getString(R.string.loading)) {
                    transcriptView.text = ""
                }
                recordButton.setText(R.string.translate)
                recordButton.isEnabled = true
            }
        }
    }

    private fun onPrimaryButtonClick() {
        if (currentTab == TAB_TRANSLATE) {
            runTranslation()
        } else {
            onclick()
        }
    }

    private fun runTranslation() {
        val src = transcriptView.text?.toString().orEmpty().trim()
        if (src.isEmpty()) return
        recordButton.isEnabled = false
        translationView.text = getString(R.string.translation_loading)
        translatorExecutor.execute {
            try {
                val t = ensureTranslator()
                val started = SystemClock.elapsedRealtime()
                val out = t.translate(src)
                val dt = SystemClock.elapsedRealtime() - started
                Log.i(TAG, "translate ${src.length} chars in ${dt} ms")
                runOnUiThread {
                    translationView.text = out
                    recordButton.isEnabled = true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "translation failed", t)
                runOnUiThread {
                    translationView.text = "翻译失败: ${t.message}"
                    recordButton.isEnabled = true
                }
            }
        }
    }

    @Synchronized
    private fun ensureTranslator(): BergamotTranslator {
        var t = translator
        if (t == null) {
            val modelDir = copyBergamotModelToFiles()
            t = BergamotTranslator(modelDir.absolutePath)
            translator = t
        }
        return t
    }

    private fun copyBergamotModelToFiles(): java.io.File {
        val outDir = java.io.File(filesDir, "bergamot-enzh")
        if (!outDir.exists()) outDir.mkdirs()
        val files = listOf(
            "model.enzh.intgemm.alphas.bin",
            "lex.50.50.enzh.s2t.bin",
            "srcvocab.enzh.spm",
            "trgvocab.enzh.spm",
        )
        for (name in files) {
            val out = java.io.File(outDir, name)
            if (out.exists() && out.length() > 0) continue
            assets.open("bergamot-enzh/$name").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "copied bergamot asset $name (${out.length() / 1024} KB)")
        }
        return outDir
    }

    private fun setupSpinner() {
        applySpinnerForTab(TAB_SPEECH)
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (currentTab != TAB_SPEECH) return  // Translate tab has only one model.
                val picked = ModelMode.values()[pos]
                if (picked == currentMode) return
                if (isRecording) {
                    // Don't allow switching mid-recording; revert the spinner.
                    modelSpinner.setSelection(currentMode.ordinal)
                    return
                }
                loadMode(picked, isInitial = false)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun applySpinnerForTab(tab: Int) {
        val labels = when (tab) {
            TAB_TRANSLATE -> listOf(getString(R.string.model_translate_en_zh))
            else -> ModelMode.values().map { getString(it.labelRes) }
        }
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        modelSpinner.setSelection(if (tab == TAB_SPEECH) currentMode.ordinal else 0)
        modelSpinner.isEnabled = (tab == TAB_SPEECH)
    }

    private fun loadMode(mode: ModelMode, isInitial: Boolean) {
        recordButton.isEnabled = false
        modelSpinner.isEnabled = false
        transcriptView.text = getString(R.string.loading)
        lastText = ""

        thread(start = true, isDaemon = true, name = "model-init") {
            try {
                if (vad == null) initVadModel()
                if (mode == ModelMode.SENSE_VOICE) {
                    if (offlineRecognizer == null) initOfflineRecognizer()
                } else {
                    val type = mode.onlineType ?: error("$mode missing onlineType")
                    if (onlineRecognizers[mode] == null)
                        onlineRecognizers[mode] = buildOnlineRecognizer(type)
                }
                currentMode = mode
                Log.i(TAG, "Mode ready: $mode")
                runOnUiThread {
                    recordButton.isEnabled = true
                    modelSpinner.isEnabled = true
                    transcriptView.text = ""
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load mode $mode", t)
                runOnUiThread {
                    transcriptView.text = "模型加载失败: ${t.message}"
                    modelSpinner.isEnabled = true
                }
            }
        }
    }

    private fun onclick() {
        if (!isRecording) {
            if (!initMicrophone()) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            modelSpinner.isEnabled = false

            transcriptView.text = ""
            lastText = ""
            vad?.reset()
            recordingThread = thread(true) {
                if (currentMode == ModelMode.SENSE_VOICE) processSamplesVadOffline()
                else processSamplesStreaming()
            }
            Log.i(TAG, "Recording started ($currentMode)")
        } else {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            modelSpinner.isEnabled = true
            Log.i(TAG, "Recording stopped")
        }
    }

    private fun initVadModel() {
        if (vad != null) return
        val config = getVadModelConfig(0)
            ?: error("VAD model config null")
        config.numThreads = 2
        config.sileroVadModelConfig.minSilenceDuration = 0.15F
        vad = Vad(assetManager = application.assets, config = config)
    }

    private fun initOfflineRecognizer() {
        val modelConfig = getOfflineModelConfig(type = OFFLINE_TYPE_SENSE_VOICE)
            ?: error("ASR model config null")
        modelConfig.numThreads = ASR_NUM_THREADS
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = modelConfig,
        )
        val rec = OfflineRecognizer(assetManager = application.assets, config = config)
        offlineRecognizer = rec
        // Warm allocators so the first real segment isn't slow.
        runCatching {
            val stream = rec.createStream()
            stream.acceptWaveform(FloatArray(sampleRateInHz / 2), sampleRateInHz)
            rec.decode(stream)
            stream.release()
        }
    }

    private fun buildOnlineRecognizer(modelType: Int): OnlineRecognizer {
        val modelConfig = getModelConfig(type = modelType)
            ?: error("Online model config null for type $modelType")
        modelConfig.numThreads = ASR_NUM_THREADS
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = application.assets, config = config)
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }
        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            audioSource, sampleRateInHz, channelConfig, audioFormat, numBytes * 2
        )
        return true
    }

    // VAD-segmented offline ASR (SenseVoice). Text appears once per pause.
    private fun processSamplesVadOffline() {
        val v = vad ?: return
        val rec = offlineRecognizer ?: return
        val buffer = ShortArray(512)
        val asrExecutor = Executors.newSingleThreadExecutor()
        try {
            while (isRecording) {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (ret <= 0) continue
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                v.acceptWaveform(samples)
                while (!v.empty()) {
                    val segmentSamples = v.front().samples
                    asrExecutor.submit {
                        val text = decodeOfflineSegment(rec, segmentSamples)
                        if (text.isNotBlank()) appendCommittedLine(text)
                    }
                    v.pop()
                }
            }
        } finally {
            asrExecutor.shutdown()
        }
    }

    private fun decodeOfflineSegment(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        stream.acceptWaveform(samples, sampleRateInHz)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text
    }

    // Streaming Zipformer. Partials appear as you speak; on endpoint, the line is committed.
    private fun processSamplesStreaming() {
        val rec = onlineRecognizers[currentMode] ?: return
        val stream = rec.createStream()
        val intervalSec = 0.1
        val bufferSize = (intervalSec * sampleRateInHz).toInt()
        val buffer = ShortArray(bufferSize)

        var lastUiUpdateMs = 0L
        var lastRendered = ""

        try {
            while (isRecording) {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (ret <= 0) continue
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRateInHz)
                while (rec.isReady(stream)) rec.decode(stream)

                val isEndpoint = rec.isEndpoint(stream)
                // The English Zipformer's tokens are uppercase; downcase Latin-script outputs for readability.
                val partial = rec.getResult(stream).text.let {
                    if (currentMode in latinUppercaseModes) it.lowercase() else it
                }

                if (isEndpoint) {
                    rec.reset(stream)
                    if (partial.isNotBlank()) {
                        appendCommittedLine(partial)
                        lastRendered = lastText
                        lastUiUpdateMs = SystemClock.uptimeMillis()
                    }
                    continue
                }

                if (partial.isBlank()) continue
                val display = if (lastText.isBlank()) partial else "$lastText\n$partial"
                if (display == lastRendered) continue
                val now = SystemClock.uptimeMillis()
                if (now - lastUiUpdateMs < UI_REFRESH_MIN_INTERVAL_MS) continue
                lastUiUpdateMs = now
                lastRendered = display
                runOnUiThread {
                    transcriptView.text = display
                    scrollTranscriptToBottom()
                }
            }
        } finally {
            stream.release()
        }
    }

    private fun appendCommittedLine(text: String) {
        runOnUiThread {
            lastText = if (lastText.isBlank()) text else "$lastText\n$text"
            transcriptView.text = lastText
            scrollTranscriptToBottom()
        }
    }

    private fun scrollTranscriptToBottom() {
        // post() until after the TextView's new layout has propagated to the ScrollView,
        // otherwise fullScroll uses the pre-update content height.
        transcriptScroll.post {
            transcriptScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
