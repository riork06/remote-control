package com.example.remotecontrol

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Point this at your deployed relay server, e.g. "http://your-vps:8080"
    // or "https://your-domain" if behind TLS (use wss:// in that case below).
    // For production, point this to your actual domain with HTTPS/WSS enabled (e.g., via Nginx)
    private val relayHttpBase = "https://YOUR_PRODUCTION_DOMAIN"
    private val relayWsBase = "wss://YOUR_PRODUCTION_DOMAIN"

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    // Handles the system consent dialog result. This is the ONLY legitimate
    // way to obtain capture permission — there is no way to bypass it,
    // by design of the Android platform.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            statusText.text = "Sharing active (Background persistent)"
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_RELAY_URL, relayWsBase)
                putExtra(ScreenCaptureService.EXTRA_ROOM_CODE, "default")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            statusText.text = "Sharing declined"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // Simple programmatic layout so this file is self-contained; swap for
        // a real layout.xml in the actual project.
        statusText = TextView(this).apply { text = "Not sharing" }
        val startButton = Button(this).apply {
            text = "Start screen sharing"
            setOnClickListener {
                statusText.text = "Requesting permission..."
                requestProjectionConsent()
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop sharing"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                })
                statusText.text = "Not sharing"
            }
        }
        val a11yButton = Button(this).apply {
            text = "Enable Accessibility Service (for remote input)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(statusText)
            addView(startButton)
            addView(stopButton)
            addView(a11yButton)
        }
        setContentView(layout)
    }



    private fun requestProjectionConsent() {
        // This always shows the system "Start recording or casting?" dialog.
        // The app cannot suppress or auto-accept it.
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
