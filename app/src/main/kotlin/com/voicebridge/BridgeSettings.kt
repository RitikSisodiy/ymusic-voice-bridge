package com.voicebridge

import android.content.Context

/** User settings, stored in SharedPreferences. Read fresh on every use, so changes apply at once. */
class BridgeSettings(context: Context) {

    enum class Method { SESSION, INTENT }

    private val prefs = context.applicationContext.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var closeAssistant: Boolean
        get() = prefs.getBoolean(KEY_CLOSE_ASSISTANT, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOSE_ASSISTANT, value).apply()

    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, true)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    var watchAllApps: Boolean
        get() = prefs.getBoolean(KEY_WATCH_ALL, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCH_ALL, value).apply()

    var method: Method
        get() = prefs.getString(KEY_METHOD, null)?.let { runCatching { Method.valueOf(it) }.getOrNull() } ?: Method.SESSION
        set(value) = prefs.edit().putString(KEY_METHOD, value.name).apply()

    var targetPackage: String
        get() = prefs.getString(KEY_TARGET_PACKAGE, null) ?: DEFAULT_TARGET_PACKAGE
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGE, value.trim()).apply()

    var targetService: String
        get() = prefs.getString(KEY_TARGET_SERVICE, null) ?: DEFAULT_TARGET_SERVICE
        set(value) = prefs.edit().putString(KEY_TARGET_SERVICE, value.trim()).apply()

    var watchedPackages: List<String>
        get() = prefs.getString(KEY_WATCHED, null)?.let { splitList(it) } ?: DEFAULT_WATCHED_PACKAGES
        set(value) = prefs.edit().putString(KEY_WATCHED, value.joinToString(", ")).apply()

    var aliases: List<String>
        get() = prefs.getString(KEY_ALIASES, null)?.let { splitList(it) }?.takeIf { it.isNotEmpty() }
            ?: CommandParser.DEFAULT_ALIASES
        set(value) = prefs.edit().putString(KEY_ALIASES, value.joinToString(", ")).apply()

    fun resetAdvanced() {
        prefs.edit()
            .remove(KEY_TARGET_PACKAGE)
            .remove(KEY_TARGET_SERVICE)
            .remove(KEY_WATCHED)
            .remove(KEY_ALIASES)
            .apply()
    }

    companion object {
        const val DEFAULT_TARGET_PACKAGE = "io.ymusic.android"
        const val DEFAULT_TARGET_SERVICE = "io.ymusic.android.PlaybackService"

        /** The Gemini overlay lives in the Google app; the standalone Gemini app is the second one. */
        val DEFAULT_WATCHED_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
        )

        private const val KEY_ENABLED = "enabled"
        private const val KEY_CLOSE_ASSISTANT = "close_assistant"
        private const val KEY_DEBUG_LOG = "debug_log"
        private const val KEY_WATCH_ALL = "watch_all"
        private const val KEY_METHOD = "method"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_TARGET_SERVICE = "target_service"
        private const val KEY_WATCHED = "watched_packages"
        private const val KEY_ALIASES = "aliases"

        fun splitList(raw: String): List<String> =
            raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
