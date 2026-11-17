package com.voicebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser(CommandParser.DEFAULT_ALIASES)

    private fun query(line: String) = parser.parse(line)?.query

    @Test
    fun englishPlayOnApp() {
        assertEquals("arijit singh", query("Play Arijit Singh on YMusic"))
        assertEquals("arijit singh", query("play Arijit Singh on YMusic V4."))
        assertEquals("arijit singh", query("Hey Google, play Arijit Singh on Y Music V4"))
        assertEquals("tum hi ho", query("Please play Tum Hi Ho in Y-Music"))
        assertEquals("lofi beats", query("play lofi beats using the YMusic app"))
        assertEquals("kesariya", query("Play Kesariya on why music"))
    }

    @Test
    fun hinglish() {
        assertEquals("arijit singh", query("YMusic pe Arijit Singh chalao"))
        assertEquals("arijit singh ke gaane", query("Arijit Singh ke gaane YMusic par bajao"))
        assertEquals("kesariya", query("Kesariya chala do Y Music pe"))
        assertEquals("kesariya", query("YMusic mein play Kesariya"))
    }

    @Test
    fun devanagari() {
        assertEquals("अरिजीत सिंह", query("वाई म्यूजिक पर अरिजीत सिंह चलाओ"))
        assertEquals("केसरिया", query("केसरिया वाई म्यूज़िक पे बजाओ"))
    }

    @Test
    fun ignoresOtherLines() {
        assertNull(query("Play Arijit Singh on YouTube Music"))
        assertNull(query("Play Arijit Singh"))
        assertNull(query("Turn on the living room lights"))
        assertNull(query("I can't control third-party apps like Y Music V4 directly"))
        assertNull(query("You'll need to open Y Music V4 and search for Arijit Singh there."))
        assertNull(query("play on YMusic"))
    }

    @Test
    fun customAlias() {
        val custom = CommandParser(listOf("my player"))
        assertEquals("arijit", custom.parse("play arijit on my player")?.query)
        assertNull(custom.parse("play arijit on ymusic"))
    }
}
