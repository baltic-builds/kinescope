package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 51: the colour names of the strategies (the rule is in `StrategyNames`' own comment and in `CLAUDE.md`). */
class StrategyNamesTest {
    private val palette = listOf("White", "Black", "Red")

    @Test
    fun newStrategiesTakeTheFirstFreeColoursInTheOrderTheyAreMet() {
        val book = StrategyNames.assign(emptyMap(), listOf("a", "b", "c"), emptySet())
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), book)
    }

    @Test
    fun aNameNeverChangesWhileTheStrategyStaysInTheList() {
        val first = StrategyNames.assign(emptyMap(), listOf("a", "b", "c"), emptySet())
        // The list is reordered and a new strategy arrives at the front.
        val second = StrategyNames.assign(first, listOf("new", "c", "b", "a"), emptySet())
        assertEquals(first["a"], second["a"])
        assertEquals(first["b"], second["b"])
        assertEquals(first["c"], second["c"])
        assertEquals(3, second["new"])
    }

    @Test
    fun noTwoStrategiesShareAnIndex() {
        val lines = (1..250).map { "s$it" }
        val book = StrategyNames.assign(emptyMap(), lines, emptySet())
        assertEquals(250, book.size)
        assertEquals(250, book.values.toSet().size)
    }

    @Test
    fun aStrategyThatLeavesTheListFreesItsColour() {
        val first = StrategyNames.assign(emptyMap(), listOf("a", "b", "c"), emptySet())
        val second = StrategyNames.assign(first, listOf("a", "c", "d"), emptySet())
        assertFalse("b" in second)
        // "d" takes the colour "b" left.
        assertEquals(first["b"], second["d"])
    }

    @Test
    fun aStrategyInTheChainOrPinnedKeepsItsColourEvenWhenTheListDroppedIt() {
        val first = StrategyNames.assign(emptyMap(), listOf("a", "b"), emptySet())
        val second = StrategyNames.assign(first, listOf("a"), keep = setOf("b"))
        assertEquals(first["b"], second["b"])
    }

    @Test
    fun aKeptStrategyThatIsNewGetsAColourToo() {
        val book = StrategyNames.assign(emptyMap(), listOf("a"), keep = setOf("pinned"))
        assertEquals(mapOf("a" to 0, "pinned" to 1), book)
    }

    @Test
    fun theBookSurvivesItsTextForm() {
        val book = StrategyNames.assign(emptyMap(), listOf("-d1 -Atorst", "-n {sni} -f-1"), emptySet())
        assertEquals(book, StrategyNames.parse(StrategyNames.serialize(book)))
    }

    @Test
    fun aDamagedStoredBookIsReadWithoutCrashingOrSharingIndexes() {
        val text = "0\ta\n0\tb\nx\tc\n-1\td\n5\t\n7\ta\n2\te\nnotabsent"
        val book = StrategyNames.parse(text)
        assertEquals(mapOf("a" to 0, "e" to 2), book)
        assertEquals(emptyMap<String, Int>(), StrategyNames.parse(null))
        assertEquals(emptyMap<String, Int>(), StrategyNames.parse(""))
    }

    @Test
    fun namesCarryANumberOnlyAfterThePaletteIsUsedUp() {
        assertEquals("White", StrategyNames.displayName(0, palette))
        assertEquals("Red", StrategyNames.displayName(2, palette))
        assertEquals("White 2", StrategyNames.displayName(3, palette))
        assertEquals("Red 3", StrategyNames.displayName(8, palette))
        assertEquals("", StrategyNames.displayName(-1, palette))
        assertEquals("", StrategyNames.displayName(0, emptyList()))
    }

    @Test
    fun everyDisplayNameIsUniqueEvenPastThePalette() {
        val names = (0 until 250).map { StrategyNames.displayName(it, palette) }
        assertEquals(250, names.toSet().size)
    }

    @Test
    fun theBookAnswersByLineAndKnowsWhatItHasNotMet() {
        val book = StrategyNameBook(mapOf("a" to 1), palette)
        assertEquals("Black", book.nameOf("a"))
        assertEquals("", book.nameOf("unknown"))
        assertNotEquals(book.nameOf("a"), book.nameOf("unknown"))
    }

    // The palette itself: both languages, the same colours in the same order, no repeats, room for the whole list.
    private fun resource(path: String): File {
        val candidates = listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    private fun paletteOf(path: String): List<String> {
        val file = resource(path)
        assertTrue("missing ${file.absolutePath}", file.exists())
        val block = Regex("<string-array\\s+name=\"strategy_colors\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
        assertTrue("strategy_colors is missing in $path", block != null)
        return Regex("<item>(.*?)</item>").findAll(block!!.groupValues[1]).map { it.groupValues[1].trim() }.toList()
    }

    @Test
    fun theColourPaletteIsCompleteAndUniqueInBothLanguages() {
        val english = paletteOf("values/strings.xml")
        val russian = paletteOf("values-ru/strings.xml")
        assertEquals("both palettes list the same number of colours", english.size, russian.size)
        assertEquals("no English colour is listed twice", english.size, english.toSet().size)
        assertEquals("no Russian colour is listed twice", russian.size, russian.toSet().size)
        assertTrue("every name is filled in", (english + russian).none { it.isBlank() })
        // The library of today (12 built-in + 60 community strategies) plus room to grow before a name needs a number.
        assertTrue("the palette must cover every strategy: ${english.size}", english.size >= 90)
        // Append-only: the first colours are the eleven basic colour terms (Berlin and Kay).
        assertEquals(
            listOf("White", "Black", "Red", "Green", "Yellow", "Blue", "Brown", "Purple", "Pink", "Orange", "Gray"),
            english.take(11)
        )
    }
}
