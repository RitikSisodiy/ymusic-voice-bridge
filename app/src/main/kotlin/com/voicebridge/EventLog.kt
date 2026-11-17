package com.voicebridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small on-device log file (last [MAX_LINES] lines), shown and shared from the main screen. */
object EventLog {
    private const val TAG = "VoiceBridge"
    private const val FILE_NAME = "events.log"
    private const val MAX_LINES = 400
    private const val TRIM_TO = 300

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(context: Context, message: String) {
        Log.i(TAG, message)
        val file = file(context)
        try {
            file.appendText("${timeFormat.format(Date())}  $message\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) file.writeText(lines.takeLast(TRIM_TO).joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            // The log is diagnostics only; a failed write must never stop playback routing.
            Log.w(TAG, "Could not write the event log", e)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        val file = file(context)
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
