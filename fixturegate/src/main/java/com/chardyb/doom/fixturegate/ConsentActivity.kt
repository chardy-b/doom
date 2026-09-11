package com.chardyb.doom.fixturegate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class ConsentActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.setText(if (FixtureGateService.connected) R.string.connected else R.string.disconnected)
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(CONSENT_FILE, MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 80, 32, 48)
        }
        layout.addView(TextView(this).apply { setText(R.string.app_name); textSize = 26f })
        layout.addView(TextView(this).apply { setText(R.string.service_description); textSize = 18f })
        val consent = CheckBox(this).apply {
            id = R.id.fixture_consent
            setText(R.string.consent)
            isChecked = preferences.getBoolean(CONSENT_KEY, false)
        }
        val settings = Button(this).apply {
            setText(R.string.settings)
            isEnabled = consent.isChecked
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        consent.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(CONSENT_KEY, checked).apply()
            settings.isEnabled = checked
        }
        layout.addView(consent)
        layout.addView(settings)
        layout.addView(Button(this).apply {
            id = R.id.fixture_clear_consent
            setText(R.string.clear)
            setOnClickListener {
                preferences.edit().clear().apply()
                consent.isChecked = false
                settings.isEnabled = false
            }
        })
        status = TextView(this).apply { id = R.id.fixture_connection }
        layout.addView(status)
        setContentView(layout)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    companion object {
        const val CONSENT_FILE = "fixture-consent"
        const val CONSENT_KEY = "accepted"
    }
}
