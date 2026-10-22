package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch 50: keeps the window theme from silently disappearing. Without an explicit theme the platform draws a native
 * action bar (the app name on a dark strip) above the Compose screens, and the window behind them is the platform's
 * white until Compose has drawn a frame.
 */
class ThemeResourcesTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root (settings.gradle.kts) not found above ${File("").absolutePath}" }
    }

    private fun read(path: String): String = File(repoRoot(), path).readText()

    @Test
    fun theApplicationSetsTheKinescopeTheme() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        val application = requireNotNull(Regex("<application\\b[^>]*>").find(manifest)) {
            "no <application> element in the manifest"
        }
        assertTrue(
            "the <application> element must set android:theme=\"@style/Theme.Kinescope\"",
            application.value.contains("android:theme=\"@style/Theme.Kinescope\"")
        )
    }

    @Test
    fun theKinescopeThemeBringsNoActionBar() {
        val themes = read("app/src/main/res/values/themes.xml")
        val style = requireNotNull(Regex("<style\\s+name=\"Theme\\.Kinescope\"\\s+parent=\"([^\"]+)\"").find(themes)) {
            "values/themes.xml has no Theme.Kinescope style with a parent"
        }
        val parent = style.groupValues[1]
        assertTrue("the parent of Theme.Kinescope must be a NoActionBar theme, was $parent", parent.contains("NoActionBar"))
    }

    @Test
    fun theWindowBackgroundMatchesTheComposeBackground() {
        val compose = read("app/src/main/java/com/kinescope/app/Theme.kt")

        fun composeBackground(scheme: String): String {
            val block = requireNotNull(Regex("private val $scheme = \\w+\\(([\\s\\S]*?)\\n\\)").find(compose)) {
                "Theme.kt has no $scheme block"
            }.groupValues[1]
            val argb = requireNotNull(Regex("\\bbackground = Color\\(0x([0-9A-Fa-f]{8})\\)").find(block)) {
                "$scheme sets no background colour"
            }.groupValues[1]
            return argb.substring(2).uppercase()
        }

        fun windowBackground(path: String): String {
            val color = Regex("<color\\s+name=\"kinescope_window_background\">#([0-9A-Fa-f]{6})</color>").find(read(path))
            return requireNotNull(color) { "$path has no kinescope_window_background colour" }.groupValues[1].uppercase()
        }

        assertEquals(composeBackground("LightColors"), windowBackground("app/src/main/res/values/colors.xml"))
        assertEquals(composeBackground("DarkColors"), windowBackground("app/src/main/res/values-night/colors.xml"))
    }
}
