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
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "viwoods-stt"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

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

        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { onclick() }

        modelSpinner = findViewById(R.id.model_spinner)
        setupSpinner()

        transcriptView.text = getString(R.string.loading)

        // Default mode is SenseVoice — preload it so the user can start immediately.
        loadMode(currentMode, isInitial = true)
    }

    private fun setupSpinner() {
        val labels = ModelMode.values().map { getString(it.labelRes) }
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        modelSpinner.setSelection(currentMode.ordinal)
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
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
