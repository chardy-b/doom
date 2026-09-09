package com.chardyb.doom.testfixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** An original public-UI fixture. Intents cannot select surfaces or grant access. */
class FixtureActivity : Activity() {
    private var surface = "feed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = savedInstanceState?.getString("surface") ?: "feed"
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("surface", surface)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("The test fixture deliberately makes Back a public Unknown transition")
    override fun onBackPressed() { surface = "unknown"; render() }

    private fun render() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 80, 32, 48)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val (marker, label) = when (surface) {
            "dm" -> R.id.fixture_dm_marker to R.string.dm
            "unknown" -> R.id.fixture_unknown_marker to R.string.unknown
            else -> R.id.fixture_feed_marker to R.string.feed
        }
        layout.addView(TextView(this).apply { id = marker; setText(label); textSize = 26f })
        layout.addView(TextView(this).apply { setText(R.string.notice); textSize = 18f })
        fun action(idValue: Int, labelValue: Int, next: String) {
            layout.addView(Button(this).apply {
                id = idValue
                setText(labelValue)
                setOnClickListener { surface = next; render() }
            })
        }
        action(R.id.fixture_dm_button, R.string.dm_action, "dm")
        action(R.id.fixture_feed_button, R.string.feed_action, "feed")
        action(R.id.fixture_unknown_button, R.string.unknown_action, "unknown")
        setContentView(layout)
    }
}
