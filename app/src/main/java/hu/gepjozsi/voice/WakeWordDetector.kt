package hu.gepjozsi.voice

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Folyamatos, helyi GépJózsi-ébresztőszó felismerés.
 * A tanítási pipeline-nal egyezően: 16 kHz mono, 1.5 s,
 * YAMNet embeddingek időbeli átlaga + maximuma = 2048 float.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWord: (Float) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "GepJozsiWake"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 24000
        private const val HOP_SAMPLES = 4000 // 250 ms
        private const val THRESHOLD = 0.0f
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var yamnet: Interpreter? = null
    private var ortEnv: OrtEnvironment? = null
    private var svm: OrtSession? = null
    private var svmInputName: String = ""

    fun start() {
        if (running) return
        try {
            loadModels()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBuffer > 0) { "A mikrofon nem támogatja a 16 kHz-es PCM hangot." }
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, 8192)
            )
            require(recorder?.state == AudioRecord.STATE_INITIALIZED) { "Nem sikerült megnyitni a mikrofont." }
            running = true
            paused = false
            recorder?.startRecording()
            worker = thread(name = "GepJozsiAudio") { audioLoop() }
            onStatus("GépJózsi ébresztőszóra figyel – helyi YAMNet + SVM.")
        } catch (e: Exception) {
            running = false
            releaseAudio()
            onStatus("A saját felismerő nem indult el: ${e.message}")
            Log.e(TAG, "Detector start failed", e)
        }
    }

    fun pauseForCommand() {
        paused = true
        try { recorder?.stop() } catch (_: Exception) { }
    }

    fun resume() {
        if (!running) return
        try {
            paused = false
            recorder?.startRecording()
            onStatus("Újra GépJózsi ébresztőszóra figyelek.")
        } catch (e: Exception) {
            onStatus("Nem sikerült újraindítani a mikrofont: ${e.message}")
        }
    }

    fun stop() {
        running = false
        paused = false
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.join(700)
        worker = null
        releaseAudio()
        releaseModels()
    }

    private fun loadModels() {
        if (yamnet == null) {
            val model = mapAsset("yamnet.tflite")
            val options = Interpreter.Options().setNumThreads(2)
            yamnet = Interpreter(model, options)
            val input = yamnet!!.getInputTensor(0)
            val signature = input.shapeSignature()
            val expected = if (signature.size == 1) intArrayOf(WINDOW_SAMPLES) else intArrayOf(1, WINDOW_SAMPLES)
            if (!input.shape().contentEquals(expected)) {
                yamnet!!.resizeInput(0, expected)
                yamnet!!.allocateTensors()
            }
            require(yamnet!!.getInputTensor(0).dataType().toString() == "FLOAT32") {
                "A YAMNet bemenete nem FLOAT32."
            }
        }
        if (svm == null) {
            ortEnv = OrtEnvironment.getEnvironment()
            svm = ortEnv!!.createSession(context.assets.open("gepjozsi_svm.onnx").use { it.readBytes() })
            svmInputName = svm!!.inputNames.firstOrNull()
                ?: error("Az ONNX modellnek nincs bemenete.")
            val inputInfo = svm!!.inputInfo[svmInputName] ?: error("Nem olvasható az ONNX bemenet.")
            val shape = inputInfo.info.shape
            require(shape.size == 2 && (shape[1] == 2048L || shape[1] == -1L)) {
                "Az SVM bemeneti alakja nem 2048: ${shape.contentToString()}"
            }
        }
    }

    private fun mapAsset(name: String): ByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(name)
        return FileInputStream(afd.fileDescriptor).channel.use { channel ->
            channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                .order(ByteOrder.nativeOrder())
        }.also { afd.close() }
    }

    private fun audioLoop() {
        val shortBuffer = ShortArray(2048)
        val window = FloatArray(WINDOW_SAMPLES)
        var filled = 0
        var sinceInference = 0
        var lastWakeAt = 0L

        while (running) {
            if (paused) {
                Thread.sleep(50)
                continue
            }
            val count = try { recorder?.read(shortBuffer, 0, shortBuffer.size) ?: -1 }
                catch (e: Exception) { -1 }
            if (count <= 0) {
                if (running && !paused) onStatus("Mikrofonolvasási hiba: $count")
                Thread.sleep(100)
                continue
            }
            for (i in 0 until count) {
                val sample = shortBuffer[i] / 32768.0f
                if (filled < WINDOW_SAMPLES) {
                    window[filled++] = sample
                } else {
                    System.arraycopy(window, 1, window, 0, WINDOW_SAMPLES - 1)
                    window[WINDOW_SAMPLES - 1] = sample
                }
                sinceInference++
                if (filled == WINDOW_SAMPLES && sinceInference >= HOP_SAMPLES) {
                    sinceInference = 0
                    if (SystemClock.elapsedRealtime() - lastWakeAt > 2500L) {
                        try {
                            val score = classify(window)
                            if (score >= THRESHOLD) {
                                lastWakeAt = SystemClock.elapsedRealtime()
                                onWakeWord(score)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Inference failed", e)
                            onStatus("Felismerési hiba: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun classify(waveform: FloatArray): Float {
        val interpreter = yamnet ?: error("YAMNet nincs betöltve.")
        val inputShape = interpreter.getInputTensor(0).shape()
        val input: Any = if (inputShape.size == 1) waveform.copyOf() else arrayOf(waveform.copyOf())

        val outputObjects = HashMap<Int, Any>()
        var embeddingOutputIndex = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.isNotEmpty() && shape.last() == 1024) embeddingOutputIndex = i
            outputObjects[i] = allocateFloatOutput(shape)
        }
        require(embeddingOutputIndex >= 0) { "A YAMNet 1024 elemű embedding kimenete nem található." }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputObjects)

        val embeddings = flattenFloats(outputObjects[embeddingOutputIndex])
        require(embeddings.isNotEmpty() && embeddings.size % 1024 == 0) {
            "Érvénytelen YAMNet embedding méret: ${embeddings.size}"
        }
        val frames = embeddings.size / 1024
        val features = FloatArray(2048)
        for (j in 0 until 1024) {
            var sum = 0.0
            var maximum = Float.NEGATIVE_INFINITY
            for (frame in 0 until frames) {
                val value = embeddings[frame * 1024 + j]
                sum += value
                if (value > maximum) maximum = value
            }
            features[j] = (sum / frames).toFloat()
            features[1024 + j] = maximum
        }

        val env = ortEnv ?: error("ONNX környezet nincs betöltve.")
        val session = svm ?: error("SVM nincs betöltve.")
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features), longArrayOf(1, 2048)
        )
        tensor.use { inputTensor ->
            session.run(mapOf(svmInputName to inputTensor)).use { result ->
                // skl2onnx SVC export: az első kimenet a 0/1 osztálycímke.
                val labelOutput = result.firstOrNull { it.value is OnnxTensor }
                    ?: error("Az SVM nem adott tenzor kimenetet.")
                val values = flattenNumbers(labelOutput.value)
                require(values.isNotEmpty()) { "Üres SVM kimenet." }
                val first = values.first()
                // A modell kimenete címke (0/1); a notebookban használt 0
                // döntési küszöbnek megfelelő pozitív osztály: 1.
                return if (first >= 1.0) 1.0f else -1.0f
            }
        }
    }

    private fun allocateFloatOutput(shape: IntArray): Any {
        require(shape.isNotEmpty() && shape.all { it > 0 }) {
            "A YAMNet kimeneti alakja nem áll rendelkezésre: ${shape.contentToString()}"
        }
        fun build(depth: Int): Any {
            if (depth == shape.lastIndex) return FloatArray(shape[depth])
            return Array(shape[depth]) { build(depth + 1) }
        }
        return build(0)
    }

    private fun flattenFloats(value: Any): FloatArray {
        val out = ArrayList<Float>()
        fun visit(v: Any?) {
            when (v) {
                is FloatArray -> v.forEach(out::add)
                is Array<*> -> v.forEach(::visit)
            }
        }
        visit(value)
        return out.toFloatArray()
    }

    private fun flattenNumbers(value: Any?): List<Double> {
        val out = ArrayList<Double>()
        fun visit(v: Any?) {
            when (v) {
                is Number -> out.add(v.toDouble())
                is LongArray -> v.forEach { out.add(it.toDouble()) }
                is IntArray -> v.forEach { out.add(it.toDouble()) }
                is FloatArray -> v.forEach { out.add(it.toDouble()) }
                is DoubleArray -> v.forEach(out::add)
                is Array<*> -> v.forEach(::visit)
                is Iterable<*> -> v.forEach(::visit)
                is Map<*, *> -> v.values.forEach(::visit)
            }
        }
        visit(value)
        return out
    }

    private fun releaseAudio() {
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
    }

    private fun releaseModels() {
        try { yamnet?.close() } catch (_: Exception) { }
        yamnet = null
        try { svm?.close() } catch (_: Exception) { }
        svm = null
        try { ortEnv?.close() } catch (_: Exception) { }
        ortEnv = null
    }
}
