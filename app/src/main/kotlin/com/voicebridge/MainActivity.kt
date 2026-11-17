package com.voicebridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings, a manual test of the player hand-off and the phrase parser, and the debug log. */
class MainActivity : Activity() {

    private lateinit var settings: BridgeSettings
    private lateinit var launcher: PlayerLauncher

    private lateinit var serviceStatus: TextView
    private lateinit var targetPackage: EditText
    private lateinit var targetService: EditText
    private lateinit var watchedPackages: EditText
    private lateinit var aliases: EditText
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = BridgeSettings(this)
        launcher = PlayerLauncher(this)

        serviceStatus = findViewById(R.id.serviceStatus)
        targetPackage = findViewById(R.id.targetPackage)
        targetService = findViewById(R.id.targetService)
        watchedPackages = findViewById(R.id.watchedPackages)
        aliases = findViewById(R.id.aliases)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindSwitch(R.id.switchEnabled, settings.enabled) { settings.enabled = it }
        bindSwitch(R.id.switchCloseAssistant, settings.closeAssistant) { settings.closeAssistant = it }
        bindSwitch(R.id.switchDebug, settings.debugLog) { settings.debugLog = it }
        bindSwitch(R.id.switchWatchAll, settings.watchAllApps) { settings.watchAllApps = it }

        val methodGroup = findViewById<RadioGroup>(R.id.methodGroup)
        findViewById<RadioButton>(
            if (settings.method == BridgeSettings.Method.SESSION) R.id.methodSession else R.id.methodIntent,
        ).isChecked = true
        methodGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.method = if (checkedId == R.id.methodSession) BridgeSettings.Method.SESSION else BridgeSettings.Method.INTENT
        }

        fillAdvancedFields()
        findViewById<Button>(R.id.save).setOnClickListener {
            settings.targetPackage = targetPackage.text.toString().ifBlank { BridgeSettings.DEFAULT_TARGET_PACKAGE }
            settings.targetService = targetService.text.toString().ifBlank { BridgeSettings.DEFAULT_TARGET_SERVICE }
            settings.watchedPackages = BridgeSettings.splitList(watchedPackages.text.toString())
            settings.aliases = BridgeSettings.splitList(aliases.text.toString())
            fillAdvancedFields()
            hideKeyboard()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.resetDefaults).setOnClickListener {
            settings.resetAdvanced()
            fillAdvancedFields()
        }

        val testQuery = findViewById<EditText>(R.id.testQuery)
        findViewById<Button>(R.id.testPlay).setOnClickListener {
            val query = testQuery.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard()
                launcher.play(query)
                logView.postDelayed({ refreshLog() }, 1_500)
            }
        }

        val testPhrase = findViewById<EditText>(R.id.testPhrase)
        val phraseResult = findViewById<TextView>(R.id.phraseResult)
        findViewById<Button>(R.id.testPhraseButton).setOnClickListener {
            val match = CommandParser(settings.aliases).parse(testPhrase.text.toString())
            phraseResult.text = match?.let { getString(R.string.phrase_match, it.query) } ?: getString(R.string.phrase_no_match)
        }

        findViewById<Button>(R.id.logRefresh).setOnClickListener { refreshLog() }
        findViewById<Button>(R.id.logClear).setOnClickListener {
            EventLog.clear(this)
            refreshLog()
        }
        findViewById<Button>(R.id.logShare).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, EventLog.read(this))
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.setText(if (isServiceEnabled()) R.string.service_on else R.string.service_off)
        refreshLog()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun fillAdvancedFields() {
        targetPackage.setText(settings.targetPackage)
        targetService.setText(settings.targetService)
        watchedPackages.setText(settings.watchedPackages.joinToString(", "))
        aliases.setText(settings.aliases.joinToString(", "))
    }

    private fun refreshLog() {
        // Newest first: the latest test is what you look for.
        val lines = EventLog.read(this).lines().filter { it.isNotBlank() }.reversed()
        logView.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(this, AssistantWatcherService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
