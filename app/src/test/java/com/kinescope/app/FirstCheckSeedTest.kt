package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstCheckSeedTest {
    private val list = listOf("a", "b", "c", "d", "e", "f", "g", "h")

    @Test
    fun anUntestedChainStartsWithThePriorityThenTheList() {
        assertEquals(listOf("p", "a", "b", "c", "d", "e"), StrategyRouter.untestedSeed(list, listOf("p"), 6))
    }

    @Test
    fun aPriorityStrategyAlreadyInTheListIsNotRepeated() {
        assertEquals(listOf("c", "a", "b"), StrategyRouter.untestedSeed(list, listOf("c"), 3))
    }

    @Test
    fun aClassWithoutPriorityStartsWithTheList() {
        assertEquals(listOf("a", "b"), StrategyRouter.untestedSeed(list, emptyList(), 2))
        assertEquals(emptyList<String>(), StrategyRouter.untestedSeed(emptyList(), emptyList(), 6))
    }
}
