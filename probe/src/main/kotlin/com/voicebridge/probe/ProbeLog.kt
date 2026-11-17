package com.voicebridge.probe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device log of everything the assistant sends this app. */
object ProbeLog {
    private const val TAG = "YtmProbe"
    private const val FILE_NAME = "probe.log"
    private const val MAX_LINES = 600
    private const val TRIM_TO = 450

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(context: Context, message: String) {
        Log.i(TAG, message)
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        try {
            file.appendText("${timeFormat.format(Date())}  $message\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) file.writeText(lines.takeLast(TRIM_TO).joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the probe log", e)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }

    fun describe(intent: Intent?): String {
        if (intent == null) return "intent=null"
        return buildString {
            append("action=").append(intent.action)
            intent.data?.let { append(" data=").append(it) }
            intent.type?.let { append(" type=").append(it) }
            intent.categories?.let { append(" categories=").append(it) }
            append(" flags=0x").append(Integer.toHexString(intent.flags))
            intent.`package`?.let { append(" package=").append(it) }
            intent.component?.let { append(" component=").append(it.flattenToShortString()) }
            intent.extras?.let { append(" extras=").append(describe(it)) }
        }
    }

    fun describe(bundle: Bundle?): String {
        if (bundle == null) return "null"
        return bundle.keySet().sorted().joinToString(", ", "{", "}") { key ->
            @Suppress("DEPRECATION")
            val value = try {
                bundle.get(key)
            } catch (e: Exception) {
                "<unreadable: ${e.javaClass.simpleName}>"
            }
            val shown = when (value) {
                is Bundle -> describe(value)
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
                is IntArray -> value.joinToString(prefix = "[", postfix = "]")
                is LongArray -> value.joinToString(prefix = "[", postfix = "]")
                else -> value.toString()
            }
            "$key=$shown"
        }
    }
}
