package hu.gepjozsi.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var keyInput: EditText
    private val prefs by lazy { getSharedPreferences("gepjozsi", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply { text = "GépJózsi"; textSize = 30f })
        layout.addView(TextView(this).apply {
            text = "Mondd: GépJózsi, kapcsold fel a Kint lámpát!"
            textSize = 16f
        })
        keyInput = EditText(this).apply {
            hint = "Cloudflare CONTROL_API_KEY"
            inputType = 129
            setText(prefs.getString("api_key", ""))
        }
        layout.addView(keyInput)
        layout.addView(Button(this).apply {
            text = "Kulcs mentése"
            setOnClickListener {
                prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Kulcs mentve ezen a telefonon.", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "Figyelés indítása"
            setOnClickListener {
                if (prefs.getString("api_key", "").isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Előbb mentsd el a vezérlési kulcsot.", Toast.LENGTH_LONG).show()
                } else requestPermissionsForVoice()
            }
        })
        layout.addView(Button(this).apply {
            text = "Figyelés leállítása"
            setOnClickListener { stopService(Intent(this@MainActivity, VoiceService::class.java)) }
        })
        setContentView(layout)
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
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "GépJózsi figyelése elindult.", Toast.LENGTH_SHORT).show()
    }
}
