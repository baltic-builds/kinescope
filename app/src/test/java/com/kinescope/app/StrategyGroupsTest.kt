package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Patch 56: what the Home card says about the Wi-Fi and the mobile group. */
class StrategyGroupsTest {
    private fun group(inRouter: Int, seeded: Boolean = false, cls: NetClass = NetClass.WIFI) = GroupState(
        cls = cls,
        current = true,
        inRouter = inRouter,
        known = inRouter,
        seeded = seeded,
        checkedAt = 0L,
        random = null
    )

    @Test
    fun theRandomCheckOutcomeSurvivesTheRoundTripThroughThePreferences() {
        val last = RandomLast(at = 1_790_000_000_000L, tested = 5, works = 2)
        assertEquals("1790000000000,5,2", last.serialize())
        assertEquals(last, RandomLast.parse(last.serialize()))
    }

    @Test
    fun anythingThatIsNotAnOutcomeParsesToNothing() {
        assertNull(RandomLast.parse(null))
        assertNull(RandomLast.parse(""))
        assertNull(RandomLast.parse("1,2"))
        assertNull(RandomLast.parse("a,b,c"))
        assertNull(RandomLast.parse("0,1,1"))
        assertNull(RandomLast.parse("100,-1,0"))
        assertNull(RandomLast.parse("100,2,3"))
        assertNull(RandomLast.parse("100,2,1,9"))
    }

    @Test
    fun aGroupWithNoWaysIsNotCheckedAndABorrowedOneSaysSo() {
        assertEquals(GroupStatus.NOT_CHECKED, group(0).status)
        assertEquals(GroupStatus.NOT_CHECKED, group(0, seeded = true).status)
        assertEquals(GroupStatus.BORROWED, group(6, seeded = true).status)
        assertEquals(GroupStatus.READY, group(6).status)
    }

    @Test
    fun theConnectionThePhoneIsOnComesFirst() {
        assertEquals(listOf(NetClass.WIFI, NetClass.MOBILE), groupOrder(NetClass.WIFI))
        assertEquals(listOf(NetClass.MOBILE, NetClass.WIFI), groupOrder(NetClass.MOBILE))
    }
}
