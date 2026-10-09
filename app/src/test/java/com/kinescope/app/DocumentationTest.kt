package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the documentation and the resources from drifting away from the code. */
class DocumentationTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root (settings.gradle.kts) not found above ${File("").absolutePath}" }
    }

    @Test
    fun theHandoffFileMapNamesEverySourceFile() {
        val root = repoRoot()
        val handoff = File(root, "HANDOFF.md").readText()
        val missing = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { it.name }
            .filterNot { handoff.contains(it) }
            .toList()
        assertTrue("HANDOFF.md file map is missing: $missing", missing.isEmpty())
    }

    @Test
    fun everyStringExistsInEnglishAndRussian() {
        val res = File(repoRoot(), "app/src/main/res")
        fun names(path: String): Set<String> =
            Regex("<string\\s+name=\"([^\"]+)\"").findAll(File(res, path).readText()).map { it.groupValues[1] }.toSet()
        val en = names("values/strings.xml")
        val ru = names("values-ru/strings.xml")
        assertTrue(en.isNotEmpty())
        assertEquals("only in English: ${en - ru}; only in Russian: ${ru - en}", en, ru)
    }
}
