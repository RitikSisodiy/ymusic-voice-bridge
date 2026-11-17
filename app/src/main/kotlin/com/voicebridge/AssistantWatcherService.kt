package com.voicebridge

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the assistant's windows (Gemini by default) for the user's transcribed request. When a
 * line reads "play [query] on YMusic", it closes the assistant and hands [query] to
 * [PlayerLauncher]. Everything else on screen is left alone: the assistant keeps handling every
 * other command (smart home, questions, …).
 *
 * Scans are throttled: the first event schedules one scan [SCAN_DELAY_MS] later, and events in
 * between only mark it pending, so a streaming reply doesn't trigger a scan per frame.
 */
class AssistantWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: BridgeSettings
    private lateinit var launcher: PlayerLauncher

    private var scanPending = false
    private var parser: CommandParser? = null
    private var parserAliases: List<String> = emptyList()

    private var lastQuery: String? = null
    private var lastQueryAt = 0L

    /** Last logged screen text per package, so the debug log only records changes. */
    private val lastSnapshot = HashMap<String, String>()

    /** Lines seen in the previous scan, per window id. A window seen for the first time has no
     *  entry: its lines (chat history, an old transcript) are the baseline, not a request. */
    private val previousLines = HashMap<Int, Set<String>>()

    private val scanRunnable = Runnable {
        scanPending = false
        scan()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = BridgeSettings(this)
        launcher = PlayerLauncher(this)
        EventLog.add(this, "Service connected; watching ${watchedDescription()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::settings.isInitialized) return
        if (!settings.enabled && !settings.debugLog) return
        val pkg = event?.packageName?.toString() ?: return
        if (!settings.watchAllApps && pkg !in settings.watchedPackages) return
        if (!scanPending) {
            scanPending = true
            handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(scanRunnable)
        super.onDestroy()
    }

    private fun scan() {
        val watchAll = settings.watchAllApps
        val watched = settings.watchedPackages.toSet()
        val screens = ArrayList<Screen>()

        val windowList = try {
            windows
        } catch (e: Exception) {
            EventLog.add(this, "Could not read windows: ${e.message}")
            emptyList()
        }
        for (window in windowList) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (!watchAll && pkg !in watched) continue
            screens.add(Screen(window.id, pkg, collectLines(root)))
        }
        if (windowList.isEmpty()) {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString()
                if (pkg != null && (watchAll || pkg in watched)) screens.add(Screen(NO_WINDOW_ID, pkg, collectLines(root)))
            }
        }

        if (settings.debugLog) logChanges(screens)

        // A line is a new request only if it appeared in a window we had already seen: on the
        // first sight of a window, whatever it shows (chat history, an old transcript) is old.
        val newLines = ArrayList<Pair<Screen, String>>()
        for (screen in screens) {
            val previous = previousLines[screen.windowId]
            if (previous != null) {
                screen.lines.filter { it !in previous }.forEach { newLines.add(screen to it) }
            } else if (settings.debugLog) {
                EventLog.add(this, "FIRST [${screen.pkg}#${screen.windowId}] ${screen.lines.size} lines taken as old")
            }
            previousLines[screen.windowId] = screen.lines.toHashSet()
        }
        val liveIds = screens.map { it.windowId }.toSet()
        previousLines.keys.retainAll(liveIds)

        if (settings.debugLog) {
            newLines.take(MAX_LOGGED_LINES).forEach { (screen, line) ->
                EventLog.add(this, "NEW [${screen.pkg}#${screen.windowId}] ${line.take(MAX_LOGGED_CHARS)}")
            }
        }
        if (!settings.enabled) return

        val parser = currentParser()
        for ((_, line) in newLines) {
            val match = parser.parse(line) ?: continue
            if (isDuplicate(match.query)) return
            handle(match)
            return
        }
    }

    private class Screen(val windowId: Int, val pkg: String, val lines: List<String>)

    private fun collectLines(root: AccessibilityNodeInfo): List<String> =
        ArrayList<String>().also { collectText(root, it, depth = 0) }

    private fun handle(match: CommandParser.Match) {
        lastQuery = match.query
        lastQueryAt = SystemClock.elapsedRealtime()
        EventLog.add(this, "MATCH \"${match.normalizedLine}\" → play \"${match.query}\"")
        if (settings.closeAssistant) {
            val closed = performGlobalAction(GLOBAL_ACTION_BACK)
            EventLog.add(this, if (closed) "Closed the assistant" else "Could not close the assistant")
        }
        // Let the assistant's window and its speech go away before the music starts.
        handler.postDelayed({ launcher.play(match.query) }, PLAY_DELAY_MS)
    }

    /** The same query again within the window: a later frame of the same request (e.g. the
     *  transcript growing from "…on YMusic" to "…on YMusic V4"), not a new one. */
    private fun isDuplicate(query: String): Boolean =
        query == lastQuery && SystemClock.elapsedRealtime() - lastQueryAt < DUPLICATE_WINDOW_MS

    private fun currentParser(): CommandParser {
        val aliases = settings.aliases
        val cached = parser
        if (cached != null && aliases == parserAliases) return cached
        return CommandParser(aliases).also {
            parser = it
            parserAliases = aliases
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_LINES) return
        node.text?.toString()?.let { addLines(it, out) }
        node.contentDescription?.toString()?.let { addLines(it, out) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
        }
    }

    private fun addLines(text: String, out: MutableList<String>) {
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            if (out.size < MAX_LINES && it !in out) out.add(it)
        }
    }

    private fun logChanges(screens: List<Screen>) {
        for (screen in screens) {
            if (screen.lines.isEmpty()) continue
            val key = "${screen.pkg}#${screen.windowId}"
            val snapshot = screen.lines.take(MAX_LOGGED_LINES).joinToString(" | ") { it.take(MAX_LOGGED_CHARS) }
            if (lastSnapshot[key] == snapshot) continue
            lastSnapshot[key] = snapshot
            EventLog.add(this, "[$key] $snapshot")
        }
    }

    private fun watchedDescription(): String =
        if (settings.watchAllApps) "all apps" else settings.watchedPackages.joinToString()

    companion object {
        private const val SCAN_DELAY_MS = 400L
        private const val PLAY_DELAY_MS = 350L
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val NO_WINDOW_ID = -1
        private const val MAX_DEPTH = 40
        private const val MAX_LINES = 200
        private const val MAX_LOGGED_LINES = 15
        private const val MAX_LOGGED_CHARS = 120
    }
}
