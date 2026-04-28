package com.k2fsa.sherpa.onnx

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

private const val TAG = "viwoods-stt"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// Throttle UI updates so e-ink isn't constantly redrawn during partial decodes.
private const val UI_REFRESH_MIN_INTERVAL_MS = 350L

class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    private lateinit var recognizer: OnlineRecognizer
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var transcriptView: TextView
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var idx: Int = 0
    private var lastText: String = ""
    private var lastUiUpdateMs: Long = 0L
    private var lastRenderedText: String = ""

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

        Log.i(TAG, "Initializing model")
        initModel()
        Log.i(TAG, "Model initialized")

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }

        transcriptView = findViewById(R.id.my_text)
        transcriptView.movementMethod = ScrollingMovementMethod()
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
            transcriptView.text = ""
            lastText = ""
            lastRenderedText = ""
            lastUiUpdateMs = 0L
            idx = 0

            recordingThread = thread(true) { processSamples() }
            Log.i(TAG, "Recording started")
        } else {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            Log.i(TAG, "Recording stopped")
        }
    }

    private fun processSamples() {
        val stream = recognizer.createStream()
        val interval = 0.1
        val bufferSize = (interval * sampleRateInHz).toInt()
        val buffer = ShortArray(bufferSize)

        try {
            while (isRecording) {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (ret <= 0) continue

                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = sampleRateInHz)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val isEndpoint = recognizer.isEndpoint(stream)
                val text = recognizer.getResult(stream).text

                var textToDisplay = lastText
                if (text.isNotBlank()) {
                    textToDisplay = if (lastText.isBlank()) text else "$lastText\n$text"
                }

                if (isEndpoint) {
                    recognizer.reset(stream)
                    if (text.isNotBlank()) {
                        lastText = if (lastText.isBlank()) text else "$lastText\n$text"
                        textToDisplay = lastText
                        idx += 1
                    }
                }

                maybeUpdateUi(textToDisplay, force = isEndpoint)
            }
        } finally {
            stream.release()
        }
    }

    private fun maybeUpdateUi(text: String, force: Boolean) {
        if (text == lastRenderedText) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdateMs < UI_REFRESH_MIN_INTERVAL_MS) return
        lastUiUpdateMs = now
        lastRenderedText = text
        runOnUiThread { transcriptView.text = text }
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
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2
        )
        return true
    }

    private fun initModel() {
        // Type 9 = sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23 (Chinese-only, ~25MB int8).
        val type = 9
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = getModelConfig(type = type)!!,
            endpointConfig = getEndpointConfig(),
            enableEndpoint = true,
        )
        recognizer = OnlineRecognizer(
            assetManager = application.assets,
            config = config,
        )
    }
}
