package hu.gepjozsi.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

class VoiceService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommandUntil = 0L
    private var restarting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(7, makeNotification("Ébresztőszó: GépJózsi"))
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Nem található Android beszédfelismerő.")
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        startListeningAgain()
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
        getSystemService(NotificationManager::class.java).notify(7, makeNotification(message))
    }

    private fun startListeningAgain() {
        if (restarting || recognizer == null) return
        restarting = true
        handler.postDelayed({
            restarting = false
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hu-HU")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                }
                recognizer?.startListening(intent)
            } catch (_: Exception) { startListeningAgain() }
        }, 700)
    }

    override fun onResults(results: Bundle?) {
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (phrase.isNotBlank()) updateNotification("Felismert szöveg: $phrase")
        processPhrase(phrase)
        startListeningAgain()
    }

    private fun processPhrase(phrase: String) {
        if (phrase.isBlank()) return
        val text = normalize(phrase)
        val wake = text.indexOf("gepjozsi")
        if (wake >= 0) {
            val command = text.substring(wake + 8).trim()
            if (command.isNotBlank() && sendCommand(command)) {
                waitingForCommandUntil = 0L
                updateNotification("Parancs elküldve. Újra figyelek.")
            } else {
                waitingForCommandUntil = android.os.SystemClock.elapsedRealtime() + 10000L
                updateNotification("Hallak! Mondd a kapcsolási parancsot.")
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < waitingForCommandUntil) {
            if (sendCommand(text)) {
                waitingForCommandUntil = 0L
                updateNotification("Parancs elküldve. Újra figyelek.")
            } else updateNotification("Nem értettem. Mondd újra a parancsot.")
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ő", "o").replace("ű", "u")
    }

    private fun sendCommand(text: String): Boolean {
        val on = listOf("kapcsold fel", "kapcsold be", "kapcsolj fel", "kapcsolj be", "kapcsol fel", "kapcsol be", "kapcsolt fel", "kapcsolt be").any { text.contains(it) }
        val off = listOf("kapcsold le", "kapcsold ki", "kapcsolj le", "kapcsolj ki", "kapcsol le", "kapcsol ki", "kapcsolt le", "kapcsolt ki").any { text.contains(it) }
        if (!on && !off) return false
        val id = when {
            text.contains("kint") -> "1001542f3d"
            text.contains("hatul") -> "1001543118"
            text.contains("bejarat") -> "10016fe2e9"
            text.contains("szobak") || text.contains("szoba") -> "100083ae1e"
            else -> return false
        }
        val key = getSharedPreferences("gepjozsi", MODE_PRIVATE).getString("api_key", "").orEmpty()
        if (key.isBlank()) return false
        thread {
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
                val code = connection.responseCode
                handler.post { updateNotification(if (code in 200..299) "Kapcsolási parancs elküldve." else "A kapcsolás hibát jelzett: HTTP $code") }
            } catch (_: Exception) {
                handler.post { updateNotification("Nem sikerült elérni az OkosOtthon szervert.") }
            } finally { connection?.disconnect() }
        }
        return true
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "HIBA: a beszédfelismerő mikrofonhibát jelzett (error $error)."
            SpeechRecognizer.ERROR_CLIENT -> "HIBA: a beszédfelismerő kliens leállt (error $error)."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "HIBA: nincs mikrofonengedély (error $error)."
            SpeechRecognizer.ERROR_NETWORK -> "HIBA: hálózati beszédfelismerési hiba (error $error)."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "HIBA: időtúllépés a beszédfelismerésnél (error $error)."
            SpeechRecognizer.ERROR_NO_MATCH -> "Nem ismertem fel a mondatot (error $error)."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "HIBA: a Google beszédfelismerő foglalt (error $error)."
            SpeechRecognizer.ERROR_SERVER -> "HIBA: a Google beszédfelismerő szerverhibát jelzett (error $error)."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nem hallottam beszédet (error $error)."
            else -> "Beszédfelismerési hiba: $error"
        }
        updateNotification(message)
        startListeningAgain()
    }
    override fun onReadyForSpeech(params: Bundle?) {
        updateNotification("Figyelek – mondd: GépJózsi.")
    }

    override fun onBeginningOfSpeech() {
        updateNotification("Beszédet érzékelek…")
    }

    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }

    override fun onEndOfSpeech() {
        updateNotification("Beszéd vége – feldolgozom…")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!phrase.isNullOrBlank()) {
            updateNotification("Részben ezt hallom: $phrase")
            processPhrase(phrase)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) { }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
