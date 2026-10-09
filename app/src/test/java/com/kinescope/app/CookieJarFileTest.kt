package com.kinescope.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarFileTest {
    @Test
    fun parsesAHeaderAndKeepsTheFirstValueOfARepeatedName() {
        assertEquals(
            listOf("a" to "1", "b" to "x=y", "c" to ""),
            CookieJarFile.parse(" a=1; b=x=y ;c=; a=2; junk; =nameless")
        )
        assertTrue(CookieJarFile.parse("").isEmpty())
    }

    @Test
    fun writesTheNetscapeFormatYtDlpReads() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kinescope-cookie-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val target = File(dir, "jar.txt")
            CookieJarFile.write(
                target, ".example.com", "made by a test",
                listOf("sessionid" to "abc", "bad\tname" to "v", "bad" to "line\nbreak", "k" to "v")
            )
            val lines = target.readLines()
            assertEquals("# Netscape HTTP Cookie File", lines[0])
            assertEquals("# made by a test", lines[1])
            assertEquals(".example.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc", lines[2])
            assertEquals(".example.com\tTRUE\t/\tTRUE\t0\tk\tv", lines[3])
            assertEquals(4, lines.size)
            assertFalse(File(dir, "jar.txt.tmp").exists())

            CookieJarFile.write(target, ".example.com", "second write", listOf("only" to "one"))
            assertEquals(3, target.readLines().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
