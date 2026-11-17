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

    /** Lines seen in the previous scan per package, and when, to tell new lines from old ones. */
    private val previousLines = HashMap<String, Set<String>>()
    private val previousScanAt = HashMap<String, Long>()

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
        val linesByPackage = LinkedHashMap<String, MutableList<String>>()

        val windowRoots: List<AccessibilityNodeInfo> = try {
            windows.mapNotNull { it.root }
        } catch (e: Exception) {
            EventLog.add(this, "Could not read windows: ${e.message}")
            emptyList()
        }
        val roots = windowRoots.ifEmpty { listOfNotNull(rootInActiveWindow) }

        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (!watchAll && pkg !in watched) continue
            collectText(root, linesByPackage.getOrPut(pkg) { mutableListOf() }, depth = 0)
        }

        if (settings.debugLog) logChanges(linesByPackage)

        // Only a line that appears while we watch is a new request. On the first scan of a window
        // (nothing seen from it recently) every line is old: chat history, a previous transcript.
        val now = SystemClock.elapsedRealtime()
        val newLinesByPackage = linesByPackage.mapValues { (pkg, lines) ->
            val fresh = previousScanAt[pkg]?.let { now - it > FRESH_WINDOW_MS } ?: true
            val previous = previousLines[pkg].orEmpty()
            previousLines[pkg] = lines.toHashSet()
            previousScanAt[pkg] = now
            if (fresh) emptyList() else lines.filter { it !in previous }
        }
        if (!settings.enabled) return

        val parser = currentParser()
        for ((_, lines) in newLinesByPackage) {
            for (line in lines) {
                val match = parser.parse(line) ?: continue
                if (isDuplicate(match.query)) return
                handle(match)
                return
            }
        }
    }

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

    private fun logChanges(linesByPackage: Map<String, List<String>>) {
        for ((pkg, lines) in linesByPackage) {
            if (lines.isEmpty()) continue
            val snapshot = lines.take(MAX_LOGGED_LINES).joinToString(" | ") { it.take(MAX_LOGGED_CHARS) }
            if (lastSnapshot[pkg] == snapshot) continue
            lastSnapshot[pkg] = snapshot
            EventLog.add(this, "[$pkg] $snapshot")
        }
    }

    private fun watchedDescription(): String =
        if (settings.watchAllApps) "all apps" else settings.watchedPackages.joinToString()

    companion object {
        private const val SCAN_DELAY_MS = 400L
        private const val PLAY_DELAY_MS = 350L
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val FRESH_WINDOW_MS = 20_000L
        private const val MAX_DEPTH = 40
        private const val MAX_LINES = 200
        private const val MAX_LOGGED_LINES = 15
        private const val MAX_LOGGED_CHARS = 120
    }
}
