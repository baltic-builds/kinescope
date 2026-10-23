package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled community-list snapshot. `DpiStrategyParser.parseList` silently drops any
 * line it rejects, so without this a bad edit to the asset would quietly shrink the list.
 */
class DpiBundledStrategiesTest {
    // Gradle runs unit tests with the module directory (app/) as the working directory; the
    // second candidate covers a run from the repository root.
    private val asset = listOf(
        File("src/main/assets/dpi_strategies_bundled.txt"),
        File("app/src/main/assets/dpi_strategies_bundled.txt")
    ).firstOrNull { it.exists() } ?: File("src/main/assets/dpi_strategies_bundled.txt")

    private fun strategyLines(): List<String> = asset.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun everyBundledLineIsAcceptedByTheStrictParser() {
        assertTrue("asset missing: ${asset.absolutePath}", asset.exists())
        val lines = strategyLines()
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            val result = DpiStrategyParser.parse(line)
            assertTrue("rejected: $line -> $result", result is DpiStrategyParser.Parsed.Ok)
        }
        assertEquals(lines.size, DpiStrategyParser.parseList(asset.readText()).size)
    }

    @Test
    fun builtInsPlusBundledListGiveAllSeventyTwoStrategies() {
        val bundled = DpiStrategyParser.parseList(asset.readText())
        assertEquals(60, bundled.size)
        assertTrue(DpiBuiltInStrategies.lines.none { it in bundled })
        assertEquals(72, (DpiBuiltInStrategies.lines + bundled).distinct().size)
    }

    @Test
    fun aDownloadedListReplacesTheSnapshotInsteadOfStackingOnIt() {
        val bundled = DpiStrategyParser.parseList(asset.readText())
        // The upstream flag fix of 2026-10-14 changed four lines. Stacking kept the old and the fixed variant side by
        // side (76 strategies for a source of 60); the downloaded list must replace the snapshot.
        val downloaded = bundled.mapIndexed { index, line -> if (index < 4) "$line -a9" else line }
        val merged = mergeStrategyLists(DpiBuiltInStrategies.lines, bundled, downloaded)
        assertEquals(DpiBuiltInStrategies.lines.size + downloaded.size, merged.size)
        assertTrue(bundled.take(4).none { it in merged })
        assertEquals(DpiBuiltInStrategies.lines + downloaded, merged)
    }

    @Test
    fun withoutADownloadedListTheSnapshotIsUsed() {
        val bundled = DpiStrategyParser.parseList(asset.readText())
        assertEquals(
            (DpiBuiltInStrategies.lines + bundled).distinct(),
            mergeStrategyLists(DpiBuiltInStrategies.lines, bundled, emptyList())
        )
    }

    @Test
    fun theSnapshotHoldsNoPositionalFlagTheSourceFixed() {
        // Six flags were corrected upstream on 2026-10-14 (3e2a85b); none of the old spellings may come back.
        val bundled = DpiStrategyParser.parseList(asset.readText())
        assertTrue(bundled.none { "+sh" in it || "+nme" in it || "+sn" in it })
    }
}
