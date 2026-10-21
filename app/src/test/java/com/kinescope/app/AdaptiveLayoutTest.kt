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
        assertEquals(48, spec.controlHeightDp)
        assertEquals(64, spec.headerHeightDp)
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
    fun aCompactWindowKeepsTheBottomBarHoweverShort() {
        // Patch 48: a rail in a 360x400dp split-screen window left 240dp for the content. Below 600dp wide the
        // bottom bar stays at every height; the rail needs width, not a short window.
        for (height in listOf(200, 400, 479, 480, 900)) {
            val spec = adaptiveSpecFor(widthDp = 360, heightDp = height, tv = false)
            assertFalse("h=$height", spec.useRail)
            assertFalse("h=$height", spec.twoPane)
        }
        assertFalse(adaptiveSpecFor(widthDp = 599, heightDp = 300, tv = false).useRail)
        assertTrue(adaptiveSpecFor(widthDp = 600, heightDp = 300, tv = false).useRail)
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
        assertEquals(56, spec.controlHeightDp)
        assertEquals(72, spec.headerHeightDp)
    }

    @Test
    fun theTvEdgeMarginFollowsTheSettingAndIsAPercentageOfTheWindow() {
        // 5% is the margin the layout always had (48x27dp on the canonical 960x540dp TV), so it is the default.
        assertEquals(DEFAULT_OVERSCAN_PERCENT, OVERSCAN_MAX_PERCENT)
        val none = adaptiveSpecFor(widthDp = 960, heightDp = 540, tv = true, overscanPercent = 0)
        assertEquals(0, none.safeHorizontalDp)
        assertEquals(0, none.safeVerticalDp)
        val three = adaptiveSpecFor(widthDp = 960, heightDp = 540, tv = true, overscanPercent = 3)
        assertEquals(29, three.safeHorizontalDp)
        assertEquals(16, three.safeVerticalDp)
        val wide = adaptiveSpecFor(widthDp = 1280, heightDp = 720, tv = true, overscanPercent = 5)
        assertEquals(64, wide.safeHorizontalDp)
        assertEquals(36, wide.safeVerticalDp)
        // Out-of-range values (a stale preference) are clamped, never trusted.
        assertEquals(48, adaptiveSpecFor(960, 540, true, overscanPercent = 40).safeHorizontalDp)
        assertEquals(0, adaptiveSpecFor(960, 540, true, overscanPercent = -3).safeHorizontalDp)
    }

    @Test
    fun theEdgeMarginChangesNothingOffTv() {
        for (percent in 0..OVERSCAN_MAX_PERCENT) {
            val tablet = adaptiveSpecFor(widthDp = 1280, heightDp = 800, tv = false, overscanPercent = percent)
            assertEquals(0, tablet.safeHorizontalDp)
            assertEquals(0, tablet.safeVerticalDp)
            assertEquals(adaptiveSpecFor(1280, 800, false), tablet)
        }
    }

    @Test
    fun aSmallerTvMarginNeverLosesTheTwoPaneLayout() {
        for (width in 600..1920 step 40) {
            val full = adaptiveSpecFor(width, 540, tv = true, overscanPercent = OVERSCAN_MAX_PERCENT)
            val none = adaptiveSpecFor(width, 540, tv = true, overscanPercent = 0)
            assertTrue("w=$width", !full.twoPane || none.twoPane)
        }
        // On the canonical TV the margin is the difference between 744dp and 840dp of content.
        assertTrue(adaptiveSpecFor(960, 540, tv = true, overscanPercent = 0).twoPane)
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
        assertEquals(32, spec.safeHorizontalDp)
    }

    @Test
    fun aDeviceWithoutATouchScreenIsATvUnlessItIsAPc() {
        // Phone, tablet: a touch screen, no leanback.
        assertFalse(looksLikeTelevision(tvUiMode = false, leanback = false, touchscreen = true, pc = false))
        // TV with the TV UI mode, with only the leanback feature, and a set-top box that declares nothing but no touch.
        assertTrue(looksLikeTelevision(tvUiMode = true, leanback = false, touchscreen = false, pc = false))
        assertTrue(looksLikeTelevision(tvUiMode = false, leanback = true, touchscreen = true, pc = false))
        assertTrue(looksLikeTelevision(tvUiMode = false, leanback = false, touchscreen = false, pc = false))
        // A Chromebook or desktop-mode device without touch is not a TV (the Settings override can still say so).
        assertFalse(looksLikeTelevision(tvUiMode = false, leanback = false, touchscreen = false, pc = true))
        // The explicit TV signals win over the PC flag.
        assertTrue(looksLikeTelevision(tvUiMode = true, leanback = false, touchscreen = false, pc = true))
        assertTrue(looksLikeTelevision(tvUiMode = false, leanback = true, touchscreen = false, pc = true))
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
            for (percent in 0..OVERSCAN_MAX_PERCENT) {
                for (width in 280..2000 step 20) {
                    for (height in 240..1400 step 40) {
                        assertRulesHold(width, height, tv, percent)
                    }
                }
            }
        }
    }

    private fun assertRulesHold(width: Int, height: Int, tv: Boolean, percent: Int) {
        val spec = adaptiveSpecFor(width, height, tv, percent)
        val label = "w=$width h=$height tv=$tv margin=$percent"
        // The bottom bar layout is only for a compact-width window, at any height.
        if (!spec.useRail) {
            assertFalse(label, tv)
            assertTrue(label, width < 600)
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
        assertEquals(label, tv && percent > 0, spec.safeHorizontalDp > 0)
        assertEquals(label, tv && percent > 0, spec.safeVerticalDp > 0)
        assertTrue(label, spec.contentMaxWidthDp >= 640)
        // The tokens never shrink below a touch target, a header is never shorter than a control, and a TV gets
        // the larger control.
        assertTrue(label, spec.controlHeightDp >= 48)
        assertTrue(label, spec.headerHeightDp >= spec.controlHeightDp)
        assertEquals(label, tv, spec.controlHeightDp == 56)
    }
}
