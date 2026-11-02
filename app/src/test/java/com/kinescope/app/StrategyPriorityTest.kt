package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 59: the two strategies that lead the Wi-Fi group by default, and how far that lead goes. */
class StrategyPriorityTest {
    private val asset = listOf(
        File("src/main/assets/dpi_strategies_bundled.txt"),
        File("app/src/main/assets/dpi_strategies_bundled.txt")
    ).firstOrNull { it.exists() } ?: File("src/main/assets/dpi_strategies_bundled.txt")

    private val a = StrategyPriority.WIFI[0]
    private val b = StrategyPriority.WIFI[1]

    @Test
    fun bothPriorityStrategiesAreAcceptedByTheStrictParser() {
        assertEquals(2, StrategyPriority.WIFI.size)
        for (line in StrategyPriority.WIFI) {
            assertTrue("rejected: $line", DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Ok)
        }
    }

    @Test
    fun onlyTheWifiGroupHasPriorityStrategies() {
        assertEquals(StrategyPriority.WIFI, StrategyPriority.forClass(NetClass.WIFI))
        assertTrue(StrategyPriority.forClass(NetClass.MOBILE).isEmpty())
    }

    @Test
    fun theyAreOnTheCommunityListAlreadySoTheTotalStaysSeventyTwo() {
        val bundled = DpiStrategyParser.parseList(asset.readText())
        assertTrue(StrategyPriority.WIFI.all { it in bundled })
        val offered = StrategyPriority.withPriority(mergeStrategyLists(DpiBuiltInStrategies.lines, bundled, emptyList()))
        assertEquals(72, offered.size)
        assertEquals(StrategyPriority.WIFI, offered.take(2))
    }

    @Test
    fun aDownloadedListThatDroppedThemStillOffersThem() {
        val downloaded = listOf("-d1 -s3+s -a1", "-o3 -d7 -a1")
        val offered = StrategyPriority.withPriority(mergeStrategyLists(DpiBuiltInStrategies.lines, emptyList(), downloaded))
        assertEquals(StrategyPriority.WIFI, offered.take(2))
        assertEquals(DpiBuiltInStrategies.lines.size + downloaded.size + 2, offered.size)
    }

    @Test
    fun theBonusGoesToUnknownAndGoodStrategiesOnly() {
        assertEquals(StrategyPriority.BONUS, StrategyPriority.bonus(a, null, StrategyPriority.WIFI))
        assertEquals(StrategyPriority.BONUS, StrategyPriority.bonus(a, 50, StrategyPriority.WIFI))
        assertEquals(0, StrategyPriority.bonus(a, 49, StrategyPriority.WIFI))
        assertEquals(0, StrategyPriority.bonus("-d1", 90, StrategyPriority.WIFI))
        assertEquals(0, StrategyPriority.bonus(a, 90, emptyList()))
    }

    @Test
    fun withNoEvidenceThePriorityStrategiesAreTestedAndOfferedFirst() {
        val candidates = listOf("-x", "-y", b, "-z", a)
        val order = StrategyRank.order(candidates, emptyMap(), emptyMap(), StrategyPriority.WIFI)
        assertEquals(listOf(a, b, "-x", "-y", "-z"), order)
    }

    @Test
    fun withoutPriorityTheOrderIsTheOldOne() {
        val candidates = listOf("-x", "-y", b, "-z", a)
        assertEquals(candidates, StrategyRank.order(candidates, emptyMap(), emptyMap()))
    }

    @Test
    fun aPriorityStrategyWinsAClosePairButNotAClearDifference() {
        val ratings = mapOf("-x" to 90, a to 85, b to 70)
        val order = StrategyRank.order(listOf("-x", a, b), ratings, emptyMap(), StrategyPriority.WIFI)
        // 85 + 10 beats 90; 70 + 10 does not beat 90.
        assertEquals(listOf(a, "-x", b), order)
    }

    @Test
    fun aPriorityStrategyThatFailedSinksLikeAnyOther() {
        val tested = mapOf(a to StoredResult(a, 0, false, 0, 0, 0, 0, 1_000L))
        val ratings = mapOf("-x" to 60, a to 10)
        val order = StrategyRank.order(listOf(a, "-x", "-y"), ratings, tested, StrategyPriority.WIFI)
        assertEquals(listOf("-x", "-y", a), order)
    }

    @Test
    fun theChainAfterASearchLeadsWithAPriorityStrategyThatIsAboutAsGood() {
        val ratings = mapOf("-x" to 90, a to 85)
        val chain = StrategyRouter.afterSearch(
            ranked = listOf("-x", a),
            failed = emptySet(),
            ratings = ratings,
            proven = null,
            pinned = null,
            keepProven = false,
            fill = emptyList(),
            max = 6,
            priority = StrategyPriority.WIFI
        )
        assertEquals(listOf(a, "-x"), chain)
        val plain = StrategyRouter.afterSearch(listOf("-x", a), emptySet(), ratings, null, null, false, emptyList(), 6)
        assertEquals(listOf("-x", a), plain)
    }

    @Test
    fun aFailedPriorityStrategyIsDemotedInTheChain() {
        val ratings = mapOf("-x" to 60, a to 20)
        val chain = StrategyRouter.afterFailure(listOf(a, "-x"), a, ratings, StrategyPriority.WIFI)
        assertFalse(chain.first() == a)
    }
}
