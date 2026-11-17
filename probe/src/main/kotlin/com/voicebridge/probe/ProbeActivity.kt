package com.voicebridge.probe

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Logs every intent it receives (with the caller), forwards anything playable to YMusic and
 * closes; opened from the launcher it shows the log instead.
 */
class ProbeActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun handle(intent: Intent?) {
        ProbeLog.add(this, "ACTIVITY from=${referrer ?: "?"} ${ProbeLog.describe(intent)}")
        if (intent == null || intent.action == Intent.ACTION_MAIN) return

        val link = Forwarder.youtubeLink(intent.data)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstLink)?.let { Forwarder.youtubeLink(it) }
        val query = intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("query")?.takeIf { it.isNotBlank() }

        val forwarded = when {
            link != null -> Forwarder.openLink(this, link)
            query != null -> Forwarder.searchViaIntent(this, query)
            else -> {
                ProbeLog.add(this, "Nothing playable recognized in this intent")
                false
            }
        }
        if (forwarded) finish() else refreshLog()
    }

    private fun firstLink(text: String): Uri? =
        Regex("https?://\\S+").find(text)?.value?.let(Uri::parse)

    private fun refreshLog() {
        val lines = ProbeLog.read(this).lines().filter { it.isNotBlank() }.reversed()
        logView.text = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(
            TextView(this).apply {
                text = "Probe: records what the assistant sends to \"YouTube Music\" and forwards it to YMusic V4. " +
                    "Ask Gemini: \"play Arijit Singh on YouTube Music\", then share this log."
            },
        )
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Refresh"; setOnClickListener { refreshLog() } })
        buttons.addView(
            Button(this).apply {
                text = "Clear"
                setOnClickListener {
                    ProbeLog.clear(this@ProbeActivity)
                    refreshLog()
                }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = "Share"
                setOnClickListener {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, ProbeLog.read(this@ProbeActivity))
                    startActivity(Intent.createChooser(send, "Share log"))
                }
            },
        )
        column.addView(buttons)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        column.addView(logView)
        return ScrollView(this).apply { addView(column) }
    }
}
