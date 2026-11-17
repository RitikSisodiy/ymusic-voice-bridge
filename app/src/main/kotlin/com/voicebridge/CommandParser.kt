package com.voicebridge

import java.text.Normalizer
import java.util.Locale

/**
 * Recognizes a spoken "play [query] on [app]" line, in English, Hinglish or Hindi (Devanagari),
 * as the assistant transcribes it on screen. Every pattern is anchored to the whole line, so the
 * assistant's own replies ("I can't control third-party apps like YMusic…") never match.
 */
class CommandParser(aliases: List<String>) {

    data class Match(val query: String, val normalizedLine: String)

    private val patterns: List<Regex>

    init {
        val alias = aliases
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length } // "ymusic v4" before "ymusic"
            .joinToString("|") { Regex.escape(it) }
        require(alias.isNotEmpty()) { "At least one app name is required" }

        val a = "(?:$alias)"
        val wake = "(?:(?:hey|ok|okay) google )?"
        val please = "(?:please )?"
        val enPrep = "(?:on|in|using|with|from|via)"
        val hiPost = "(?:pe|par|per|mein|me|main|mai|पर|पे|में|मे)"
        val hiVerb = "(?:chalao|chala do|chala de|chala|bajao|baja do|baja de|play karo|play kar do|play kro|" +
            "lagao|laga do|चलाओ|चला दो|चला दे|बजाओ|बजा दो|बजा दे|प्ले करो|प्ले कर दो|लगाओ|लगा दो)"

        patterns = listOf(
            // play arijit singh on ymusic (app)
            "^$wake${please}play (.+?) $enPrep (?:the )?$a(?: app)?(?: please)?$",
            // ymusic pe arijit singh chalao
            "^$wake$a $hiPost (.+?) $hiVerb$",
            // arijit singh ymusic pe chalao
            "^$wake(.+?) $a $hiPost $hiVerb$",
            // arijit singh chalao ymusic pe
            "^$wake(.+?) $hiVerb $a $hiPost$",
            // ymusic pe play arijit singh
            "^$wake$a $hiPost play (.+?)$",
        ).map { Regex(it) }
    }

    /** The query to play, or null when [line] is not a play command for the app. */
    fun parse(line: String): Match? {
        val normalized = normalize(line)
        if (normalized.isEmpty() || normalized.length > MAX_LINE_LENGTH) return null
        for (pattern in patterns) {
            val query = pattern.matchEntire(normalized)?.groupValues?.get(1)?.trim() ?: continue
            if (query.isNotEmpty()) return Match(query, normalized)
        }
        return null
    }

    companion object {
        private const val MAX_LINE_LENGTH = 160

        val DEFAULT_ALIASES = listOf(
            "ymusic v4", "y music v4", "ymusic v 4", "y music v 4", "ymusic version 4",
            "ymusic", "y music", "why music", "wai music", "vai music",
            "वाई म्यूजिक", "वाई म्यूज़िक", "वाईम्यूजिक",
        )

        /** Lowercase, Unicode NFC, every run of non-letters/digits collapsed to one space. */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
                .trim()
    }
}
