package com.kinescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiStrategyParserTest {
    private fun args(line: String): List<String>? = (DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok)?.args

    @Test
    fun normalisesDocumentedSyntaxToAttachedShortOptions() {
        assertEquals(listOf("-f-1", "-t8", "-s1+s", "-d3+s", "-a1"), args("--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1"))
        assertEquals(listOf("-d1", "-Atorst", "-r1+s"), args("--disorder 1 --auto=torst --tlsrec 1+s"))
        assertEquals(listOf("-f-1", "-S"), args("--fake -1 --md5sig"))
        assertEquals(listOf("-d1", "-e1", "-m1"), args("-d1 -e1 -m1"))
        assertEquals(listOf("-e\\x00"), args("--oob-data \\x00"))
        assertEquals(listOf("-s1:5+sm", "-At,r,s", "-Mh,d,r", "-Qr"), args("-s 1:5+sm -At,r,s -M h,d,r -Q r"))
    }

    @Test
    fun substitutesTheFakeSniPlaceholder() {
        assertEquals(listOf("-ngoogle.com", "-f-1"), args("-n {sni} -f-1"))
        assertEquals(listOf("-nwww.iana.org"), args("-n www.iana.org"))
    }

    @Test
    fun rejectsOptionsThatChangeListenAddressFilesOrTargets() {
        val hostile = listOf(
            "-p1081", "--port 1081", "-i0.0.0.0", "--ip 0.0.0.0", "-I::1", "-H /etc/hosts", "--hosts=:a",
            "-j :1.2.3.4", "-y /tmp/x", "-C 1.2.3.4", "-B 1", "-P /x", "-l /etc/passwd", "-D", "-w /tmp/p",
            "-E", "-x2", "-b99999999", "-c1", "-N", "-U", "-e aa", "-e /", "-# note", "-/ x", "-d1 -p2"
        )
        for (line in hostile) assertTrue("should reject: $line", DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Rejected)
    }

    @Test
    fun rejectsSingleDashTokensThatGetoptCouldReadAsLongOptionAbbreviations() {
        // getopt_long_only() would read these as --daemon, --debug and --no-domain / --no-ipv6.
        for (line in listOf("-daemon", "-de", "-nno-domain", "-n o-ipv6", "-n no", "-sport")) {
            assertTrue("should reject: $line", DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Rejected)
        }
    }

    @Test
    fun rejectsMalformedAndShellLikeInput() {
        val bad = listOf("", "   ", "-s1;rm", "-s\$(id)", "--split", "-S1", "-d1 stray", "stray", "-", "--", "--fake=", "-s 1 2")
        for (line in bad) assertTrue("should reject: '$line'", DpiStrategyParser.parse(line) is DpiStrategyParser.Parsed.Rejected)
        assertTrue(DpiStrategyParser.parse("-d1 " + "-d1 ".repeat(80)) is DpiStrategyParser.Parsed.Rejected)
        assertTrue(DpiStrategyParser.parse("-s" + "1".repeat(500)) is DpiStrategyParser.Parsed.Rejected)
    }

    @Test
    fun listParsingSkipsCommentsBlankInvalidAndDuplicateLines() {
        val text = """
            # community list
            -d1   -Atorst

            -d1 -Atorst
            -p1081
            -f-1 -t8
        """.trimIndent()
        assertEquals(listOf("-d1 -Atorst", "-f-1 -t8"), DpiStrategyParser.parseList(text))
        assertEquals(1, DpiStrategyParser.parseList("-d1\n-f-1\n-s1", maxItems = 1).size)
    }

    @Test
    fun everyBuiltInStrategyIsValidAndUnique() {
        for (line in DpiBuiltInStrategies.lines) assertTrue("built-in should parse: $line", args(line) != null)
        assertEquals(DpiBuiltInStrategies.lines.size, DpiBuiltInStrategies.lines.toSet().size)
    }

    @Test
    fun launchArgumentsAlwaysListenOnLoopbackOnTheChosenPort() {
        val launch = dpiLaunchArguments("127.0.0.1", 41234, listOf("-d1", "-Atorst"))
        assertEquals(listOf("-i127.0.0.1", "-p41234", "-d1", "-Atorst"), launch)
        assertFalse(launch.any { it == "-i" || it == "-p" })
    }
}
