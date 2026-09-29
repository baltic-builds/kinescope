package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Patch 34: the order in which a download tries its routes. */
class BypassRoutesTest {
    private val chain = listOf("a", "b", "c")

    @Test
    fun strategiesKeepTheirRankAndDirectIsNotIncludedWithoutAVpn() {
        assertEquals(chain, BypassRoutes.order(chain, systemVpn = false, proven = null))
    }

    @Test
    fun aThirdPartyVpnMakesDirectTheFirstRoute() {
        assertEquals(listOf("direct", "a", "b", "c"), BypassRoutes.order(chain, systemVpn = true, proven = null))
    }

    @Test
    fun aRouteThatAlreadyWorkedGoesFirst() {
        assertEquals(listOf("c", "a", "b"), BypassRoutes.order(chain, systemVpn = false, proven = "c"))
        assertEquals(listOf("direct", "a", "b", "c"), BypassRoutes.order(chain, systemVpn = true, proven = "direct"))
    }

    @Test
    fun aProvenRouteThatIsGoneFromTheChainIsIgnored() {
        assertEquals(chain, BypassRoutes.order(chain, systemVpn = false, proven = "zzz"))
    }

    @Test
    fun labelsAreCompact() {
        assertEquals("s2/3", BypassRoutes.label("b", chain))
        assertEquals("direct", BypassRoutes.label(BypassRoutes.DIRECT, chain))
    }
}
