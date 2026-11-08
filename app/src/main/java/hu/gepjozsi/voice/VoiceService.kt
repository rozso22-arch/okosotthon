package hu.gepjozsi.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

class VoiceService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var detector: WakeWordDetector? = null
    @Volatile private var waitingForCommandUntil = 0L
    @Volatile private var commandMode = false
    private var restarting = false
    private var toneGenerator: ToneGenerator? = null

    private val commandTimeout = Runnable {
        if (commandMode && android.os.SystemClock.elapsedRealtime() >= waitingForCommandUntil) {
            finishCommandMode("Lejárt az idő. Újra Gép Józsira figyelek.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(7, makeNotification("Saját GépJózsi-felismerő indítása…"))
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        try {
            detector = WakeWordDetector(
                applicationContext,
                onWakeWord = { score -> handler.post { beginCommandMode(score) } },
                onStatus = { message -> handler.post { if (!commandMode) updateNotification(message) } }
            )
            detector?.start()
        } catch (e: Exception) {
            Log.e("GepJozsi", "Wake detector init failed", e)
            updateNotification("Nem sikerült elindítani a saját ébresztőszó-felismerőt: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("gepjozsi_voice", "GépJózsi hangfigyelés", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "gepjozsi_voice") else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("GépJózsi aktív")
            .setContentText(message)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSharedPreferences("gepjozsi", MODE_PRIVATE).edit().putString("voice_status", message).apply()
        getSystemService(NotificationManager::class.java).notify(7, makeNotification(message))
    }

    private fun beginCommandMode(score: Float) {
        if (commandMode) return
        commandMode = true
        waitingForCommandUntil = android.os.SystemClock.elapsedRealtime() + 15000L
        detector?.pauseForCommand()
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        updateNotification("Gép Józsi felismerve (SVM: ${"%.3f".format(Locale.US, score)}). Mondd: Kint FEL vagy Kint LE.")
        handler.removeCallbacks(commandTimeout)
        handler.postDelayed(commandTimeout, 15000L)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishCommandMode("Nincs elérhető beszédfelismerő a kapcsolási parancshoz.")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
        startCommandListening()
    }

    private fun startCommandListening() {
        if (!commandMode || restarting || recognizer == null) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (!commandMode) return@postDelayed
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hu-HU")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
                }
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("GepJozsi", "Command recognition start failed", e)
                handler.postDelayed({ startCommandListening() }, 700L)
            }
        }, 250L)
    }

    private fun finishCommandMode(message: String) {
        commandMode = false
        waitingForCommandUntil = 0L
        handler.removeCallbacks(commandTimeout)
        try { recognizer?.cancel() } catch (_: Exception) { }
        detector?.resume()
        updateNotification(message)
    }

    override fun onResults(results: Bundle?) {
        if (!commandMode) return
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (phrase.isNotBlank()) updateNotification("Parancs: $phrase")
        val normalized = normalize(phrase)
        if (sendCommand(normalized)) {
            finishCommandMode("A parancs elküldve. Újra Gép Józsira figyelek.")
        } else if (android.os.SystemClock.elapsedRealtime() < waitingForCommandUntil) {
            updateNotification("Nem értettem: „$phrase”. Mondd: Kint FEL vagy Kint LE.")
            startCommandListening()
        } else {
            finishCommandMode("Lejárt az idő. Újra Gép Józsira figyelek.")
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ő", "o").replace("ű", "u")
    }

    private fun sendCommand(text: String): Boolean {
        val on = listOf("kint fel", "hatul fel", "bejarat fel", "szobak fel", "szoba fel",
            "kapcsold fel", "kapcsold be", "kapcsolj fel", "kapcsolj be", "kapcsol fel", "kapcsol be",
            "kapcsolt fel", "kapcsolt be").any { text.contains(it) }
        val off = listOf("kint le", "hatul le", "bejarat le", "szobak le", "szoba le",
            "kapcsold le", "kapcsold ki", "kapcsolj le", "kapcsolj ki", "kapcsol le", "kapcsol ki",
            "kapcsolt le", "kapcsolt ki").any { text.contains(it) }
        if (!on && !off) return false
        val id = when {
            text.contains("kint") -> "1001542f3d"
            text.contains("hatul") -> "1001543118"
            text.contains("bejarat") -> "10016fe2e9"
            text.contains("szobak") || text.contains("szoba") -> "100083ae1e"
            else -> return false
        }
        val key = getSharedPreferences("gepjozsi", MODE_PRIVATE).getString("api_key", "").orEmpty()
        if (key.isBlank()) {
            handler.post { updateNotification("Hiányzik a vezérlési kulcs. Mentsd el az alkalmazásban.") }
            return false
        }
        thread(name = "GepJozsiCommand") {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://okosotthon-api.rozso22.workers.dev/control").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Api-Key", key)
                val body = JSONObject().put("id", id).put("switch", if (on) "on" else "off").toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                handler.post {
                    updateNotification(if (responseCode in 200..299) "Kapcsolási parancs sikeresen elküldve." else "A kapcsolás hibát jelzett: HTTP $responseCode")
                }
            } catch (e: Exception) {
                handler.post { updateNotification("Nem sikerült elérni az OkosOtthon szervert: ${e.message}") }
            } finally {
                connection?.disconnect()
            }
        }
        return true
    }

    override fun onError(error: Int) {
        if (!commandMode) return
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (android.os.SystemClock.elapsedRealtime() < waitingForCommandUntil) {
                updateNotification("Parancsra várok: Kint FEL vagy Kint LE.")
                startCommandListening()
            } else {
                finishCommandMode("Lejárt az idő. Újra Gép Józsira figyelek.")
            }
            return
        }
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Mikrofonhiba a parancs felismerésekor."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Nincs mikrofonengedély."
            SpeechRecognizer.ERROR_NETWORK -> "Hálózati hiba a parancs felismerésekor."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "A beszédfelismerő foglalt, újrapróbálom."
            SpeechRecognizer.ERROR_SERVER -> "A beszédfelismerő szerverhibát jelzett."
            else -> "Beszédfelismerési hiba: $error"
        }
        updateNotification(message)
        if (android.os.SystemClock.elapsedRealtime() < waitingForCommandUntil) {
            handler.postDelayed({ startCommandListening() }, 700L)
        } else {
            finishCommandMode("Lejárt az idő. Újra Gép Józsira figyelek.")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (commandMode) updateNotification("Hallgatlak: Kint FEL vagy Kint LE.")
    }
    override fun onBeginningOfSpeech() { if (commandMode) updateNotification("A parancsot hallom…") }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { if (commandMode) updateNotification("Feldolgozom a parancsot…") }
    override fun onPartialResults(partialResults: Bundle?) { }
    override fun onEvent(eventType: Int, params: Bundle?) { }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        commandMode = false
        waitingForCommandUntil = 0L
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        try { detector?.stop() } catch (_: Exception) { }
        detector = null
        try { toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
