package hu.gepjozsi.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var keyInput: EditText
    private lateinit var statusText: TextView
    private val prefs by lazy { getSharedPreferences("gepjozsi", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (::statusText.isInitialized) {
                statusText.text = prefs.getString("voice_status", "A figyelés még nem indult el.")
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.rgb(28, 41, 59))
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun addGap(parent: LinearLayout, height: Int = 14) {
        parent.addView(TextView(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = Color.rgb(14, 22, 35)
        val white = Color.rgb(239, 245, 252)
        val muted = Color.rgb(163, 180, 199)
        val cyan = Color.rgb(55, 210, 220)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(label("GÉPJÓZSI", 28f, white, true))
        root.addView(label("HANGVEZÉRELT OKOSOTTHON", 11f, cyan, true))
        addGap(root, 20)

        val intro = card()
        intro.addView(label("Szia! Itt GépJózsi.", 20f, white, true))
        addGap(intro, 8)
        intro.addView(label("Mondd például: „GépJózsi, kapcsold fel a Kint lámpát!”", 15f, muted))
        root.addView(intro)
        addGap(root)

        val statusCard = card()
        statusCard.addView(label("ÉLŐ ÁLLAPOT", 12f, cyan, true))
        addGap(statusCard, 8)
        statusText = label(prefs.getString("voice_status", "A figyelés még nem indult el."), 17f, white, true)
        statusText.minHeight = dp(54)
        statusText.gravity = Gravity.CENTER_VERTICAL
        statusCard.addView(statusText)
        statusCard.addView(label("Ez az üzenet megegyezik az értesítés szövegével.", 12f, muted))
        root.addView(statusCard)
        addGap(root, 20)

        val keyCard = card()
        keyCard.addView(label("VEZÉRLÉSI KULCS", 12f, cyan, true))
        addGap(keyCard, 6)
        keyInput = EditText(this).apply {
            hint = "Cloudflare CONTROL_API_KEY"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 14f
            inputType = 129
            setSingleLine(true)
            setText(prefs.getString("api_key", ""))
            background = GradientDrawable().apply {
                setColor(Color.rgb(17, 29, 44))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(61, 83, 105))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        keyCard.addView(keyInput)
        addGap(keyCard, 10)

        val saveButton = Button(this).apply {
            text = "KULCS MENTÉSE"
            setOnClickListener {
                prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Kulcs mentve ezen a telefonon.", Toast.LENGTH_SHORT).show()
            }
        }
        keyCard.addView(saveButton)
        root.addView(keyCard)
        addGap(root, 18)

        root.addView(Button(this).apply {
            text = "▶  FIGYELÉS INDÍTÁSA"
            setOnClickListener {
                if (prefs.getString("api_key", "").isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Előbb mentsd el a vezérlési kulcsot.", Toast.LENGTH_LONG).show()
                } else requestPermissionsForVoice()
            }
        })
        root.addView(Button(this).apply {
            text = "■  FIGYELÉS LEÁLLÍTÁSA"
            setOnClickListener {
                stopService(Intent(this@MainActivity, VoiceService::class.java))
                prefs.edit().putString("voice_status", "A figyelés leállítva.").apply()
                statusText.text = "A figyelés leállítva."
            }
        })
        root.addView(label("GépJózsi • Okosotthon", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(4))
        })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusRefresh)
        handler.post(statusRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun requestPermissionsForVoice() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startVoiceService() else requestPermissions(missing.toTypedArray(), 40)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 40 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoiceService()
        else Toast.makeText(this, "A mikrofonengedély szükséges.", Toast.LENGTH_LONG).show()
    }

    private fun startVoiceService() {
        prefs.edit().putString("voice_status", "A figyelés elindítása…").apply()
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "GépJózsi figyelése elindult.", Toast.LENGTH_SHORT).show()
    }
}
