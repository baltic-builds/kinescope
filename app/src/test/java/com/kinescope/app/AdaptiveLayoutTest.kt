package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the window classification (patch 41): which layout a phone, a tablet and a TV get, and where it switches. */
class AdaptiveLayoutTest {

    @Test
    fun aPhoneInPortraitKeepsTheLayoutItAlwaysHad() {
        val spec = adaptiveSpecFor(widthDp = 360, heightDp = 800, tv = false)
        assertFalse(spec.isTv)
        assertFalse(spec.useRail)
        assertFalse(spec.twoPane)
        assertEquals(640, spec.contentMaxWidthDp)
        assertEquals(20, spec.contentStartPaddingDp)
        assertEquals(20, spec.contentEndPaddingDp)
        assertEquals(0, spec.safeHorizontalDp)
        assertEquals(0, spec.safeVerticalDp)
        assertEquals(1f, spec.typographyScale, 0f)
    }

    @Test
    fun theContentCapSwitchesAtTheMaterialBreakpoints() {
        assertEquals(640, adaptiveSpecFor(599, 900, false).contentMaxWidthDp)
        assertEquals(720, adaptiveSpecFor(600, 900, false).contentMaxWidthDp)
        assertEquals(720, adaptiveSpecFor(839, 900, false).contentMaxWidthDp)
        assertEquals(1200, adaptiveSpecFor(840, 900, false).contentMaxWidthDp)
    }

    @Test
    fun theRailAppearsFromMediumWidthAndNeverBelow() {
        assertFalse(adaptiveSpecFor(599, 900, false).useRail)
        assertTrue(adaptiveSpecFor(600, 900, false).useRail)
    }

    @Test
    fun aTabletInPortraitGetsTheRailAndOneWiderColumn() {
        val spec = adaptiveSpecFor(widthDp = 800, heightDp = 1280, tv = false)
        assertTrue(spec.useRail)
        assertFalse(spec.twoPane)
        assertEquals(720, spec.contentMaxWidthDp)
        assertEquals(80, spec.railWidthDp)
        assertEquals(1f, spec.typographyScale, 0f)
    }

    @Test
    fun aTabletInLandscapeGetsTwoPanes() {
        val spec = adaptiveSpecFor(widthDp = 1280, heightDp = 800, tv = false)
        assertTrue(spec.useRail)
        assertTrue(spec.twoPane)
        assertEquals(1200, spec.contentMaxWidthDp)
        assertEquals(32, spec.contentEndPaddingDp)
    }

    @Test
    fun twoPanesNeedRoomForTwoReadableLists() {
        // The rule is 700dp of content: 820dp - rail 80 - gap 16 - end padding 24 = 700 (medium width class).
        assertTrue(adaptiveSpecFor(820, 600, false).twoPane)
        assertFalse(adaptiveSpecFor(819, 600, false).twoPane)
        // Expanded: 840dp - 80 - 16 - 32 = 712, enough; a portrait tablet (800dp) is not.
        assertTrue(adaptiveSpecFor(840, 600, false).twoPane)
        assertFalse(adaptiveSpecFor(800, 1280, false).twoPane)
        assertFalse(adaptiveSpecFor(700, 600, false).twoPane)
    }

    @Test
    fun aLandscapePhoneUsesTheRailAndSavesItsShortHeight() {
        val big = adaptiveSpecFor(widthDp = 915, heightDp = 412, tv = false)
        assertTrue(big.useRail)
        assertTrue(big.twoPane)

        val small = adaptiveSpecFor(widthDp = 640, heightDp = 360, tv = false)
        assertTrue(small.useRail)
        assertFalse(small.twoPane)
    }

    @Test
    fun aCompactWindowThatIsAlsoShortStillGetsTheRail() {
        // The same 500dp width: tall keeps the bottom bar, short (under 480dp high) moves to the rail.
        assertFalse(adaptiveSpecFor(widthDp = 500, heightDp = 480, tv = false).useRail)
        val spec = adaptiveSpecFor(widthDp = 500, heightDp = 479, tv = false)
        assertTrue(spec.useRail)
        assertFalse(spec.twoPane)
    }

    @Test
    fun aTvGetsTheRailOverscanMarginsAndLargerText() {
        val spec = adaptiveSpecFor(widthDp = 960, heightDp = 540, tv = true)
        assertTrue(spec.isTv)
        assertTrue(spec.useRail)
        assertTrue(spec.twoPane)
        assertEquals(48, spec.safeHorizontalDp)
        assertEquals(27, spec.safeVerticalDp)
        assertEquals(96, spec.railWidthDp)
        assertEquals(1.15f, spec.typographyScale, 0f)
        assertEquals(0, spec.contentEndPaddingDp)
    }

    @Test
    fun aTvAtOneThousandTwoHundredEightyDpUsesItsWholeWidthUpToTheCap() {
        val spec = adaptiveSpecFor(widthDp = 1280, heightDp = 720, tv = true)
        assertTrue(spec.twoPane)
        assertEquals(1280, spec.contentMaxWidthDp)
    }

    @Test
    fun aTvIsNeverLeftWithoutTheRailEvenInAnOddWindow() {
        val spec = adaptiveSpecFor(widthDp = 640, heightDp = 360, tv = true)
        assertTrue(spec.useRail)
        assertFalse(spec.twoPane)
        assertEquals(48, spec.safeHorizontalDp)
    }

    @Test
    fun theUsersChoiceOverridesWhatTheDeviceReports() {
        assertTrue(resolveTelevision(InterfaceMode.TV, autoDetected = false))
        assertFalse(resolveTelevision(InterfaceMode.TOUCH, autoDetected = true))
        assertTrue(resolveTelevision(InterfaceMode.AUTO, autoDetected = true))
        assertFalse(resolveTelevision(InterfaceMode.AUTO, autoDetected = false))
    }

    @Test
    fun theStoredInterfaceModeFallsBackToAutomatic() {
        assertEquals(InterfaceMode.AUTO, InterfaceMode.fromPersisted(null))
        assertEquals(InterfaceMode.AUTO, InterfaceMode.fromPersisted("nonsense"))
        for (mode in InterfaceMode.entries) {
            assertEquals(mode, InterfaceMode.fromPersisted(mode.persistedValue))
        }
    }

    @Test
    fun theRulesHoldForEveryWindowSize() {
        for (tv in listOf(false, true)) {
            for (width in 280..2000 step 20) {
                for (height in 240..1400 step 40) {
                    val spec = adaptiveSpecFor(width, height, tv)
                    val label = "w=$width h=$height tv=$tv"
                    // The bottom bar layout is only for a plain phone-sized, tall window.
                    if (!spec.useRail) {
                        assertFalse(label, tv)
                        assertTrue(label, width < 600 && height >= 480)
                        assertFalse(label, spec.twoPane)
                        assertEquals(label, 20, spec.contentStartPaddingDp)
                    } else {
                        assertEquals(label, 0, spec.contentStartPaddingDp)
                    }
                    if (spec.twoPane) {
                        assertTrue(label, spec.useRail)
                        val available = width - 2 * spec.safeHorizontalDp - spec.railWidthDp -
                            spec.railGapDp - spec.contentEndPaddingDp
                        assertTrue(label, minOf(available, spec.contentMaxWidthDp) >= 700)
                    }
                    assertEquals(label, tv, spec.isTv)
                    assertEquals(label, tv, spec.safeHorizontalDp > 0)
                    assertTrue(label, spec.contentMaxWidthDp >= 640)
                }
            }
        }
    }
}
