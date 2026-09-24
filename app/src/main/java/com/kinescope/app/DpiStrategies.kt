package com.kinescope.app

/**
 * Parsing and validation of ByeDPI "strategies": one line of desync options such as
 * `-d1 -Atorst -r1+s`.
 *
 * Strategy text can arrive from a downloaded list, so it is never passed to the engine as-is.
 * Only an allowlist of desync options is accepted. Everything that could change where the engine
 * listens, which files it reads or writes, or where it connects (`--ip`, `--port`, `--hosts`,
 * `--cache-file`, `--connect-to`, `--daemon`, ...) is rejected, and the launcher always adds its
 * own loopback listen address.
 */
object DpiStrategyParser {
    const val FAKE_SNI_PLACEHOLDER = "{sni}"
    const val DEFAULT_FAKE_SNI = "google.com"

    private const val MAX_LINE_LENGTH = 400
    private const val MAX_TOKENS = 60

    sealed interface Parsed {
        /** [args] are normalised to attached short options such as `-s1:5+sm`. */
        data class Ok(val args: List<String>) : Parsed

        data class Rejected(val reason: String) : Parsed
    }

    private enum class Kind { FLAG, NUMERIC, LIST, FAKE_SNI, BYTE }

    private class Option(val short: Char, val long: String, val kind: Kind)

    private val options = listOf(
        Option('s', "split", Kind.NUMERIC),
        Option('d', "disorder", Kind.NUMERIC),
        Option('o', "oob", Kind.NUMERIC),
        Option('q', "disoob", Kind.NUMERIC),
        Option('f', "fake", Kind.NUMERIC),
        Option('t', "ttl", Kind.NUMERIC),
        Option('O', "fake-offset", Kind.NUMERIC),
        Option('r', "tlsrec", Kind.NUMERIC),
        Option('R', "round", Kind.NUMERIC),
        Option('V', "pf", Kind.NUMERIC),
        Option('T', "timeout", Kind.NUMERIC),
        Option('u', "cache-ttl", Kind.NUMERIC),
        Option('m', "tlsminor", Kind.NUMERIC),
        Option('a', "udp-fake", Kind.NUMERIC),
        Option('g', "def-ttl", Kind.NUMERIC),
        Option('W', "await-int", Kind.NUMERIC),
        Option('A', "auto", Kind.LIST),
        Option('L', "auto-mode", Kind.LIST),
        Option('K', "proto", Kind.LIST),
        Option('M', "mod-http", Kind.LIST),
        Option('Q', "fake-tls-mod", Kind.LIST),
        Option('n', "fake-sni", Kind.FAKE_SNI),
        Option('e', "oob-data", Kind.BYTE),
        Option('S', "md5sig", Kind.FLAG),
        Option('Y', "drop-sack", Kind.FLAG),
        Option('F', "tfo", Kind.FLAG),
        Option('Z', "wait-send", Kind.FLAG)
    )
    private val byShort = options.associateBy { it.short }
    private val byLong = options.associateBy { it.long }

    // The engine reads options with getopt_long_only(), where a single-dash token such as `-de` can
    // match a long option by prefix. Lower-case options are therefore only accepted with a value
    // that starts with a digit or sign, and upper-case options cannot collide because no long
    // option name starts with an upper-case letter.
    private val numericValue = Regex("^[0-9+\\-][0-9A-Za-z:,+\\-]{0,63}$")
    private val listValue = Regex("^[A-Za-z][A-Za-z0-9_=,]{0,63}$")
    private val byteValue = Regex("^(?:[A-Za-z0-9]|\\\\x[0-9A-Fa-f]{2})$")
    private val fakeSniValue =
        Regex("^(\\{sni\\}|[A-Za-z0-9?#*_-]{1,63}(\\.[A-Za-z0-9?#*_-]{1,63})+)$")

    fun parse(line: String): Parsed {
        val text = line.trim()
        if (text.isEmpty()) return Parsed.Rejected("empty strategy")
        if (text.length > MAX_LINE_LENGTH) return Parsed.Rejected("strategy too long")
        val tokens = text.split(Regex("\\s+"))
        if (tokens.size > MAX_TOKENS) return Parsed.Rejected("too many options")

        val args = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index++]
            val option: Option
            var attached: String?
            when {
                token.startsWith("--") -> {
                    val name = token.substring(2).substringBefore('=')
                    option = byLong[name] ?: return Parsed.Rejected("unsupported option --$name")
                    attached = if (token.contains('=')) token.substringAfter('=') else null
                }
                token.startsWith("-") && token.length >= 2 -> {
                    option = byShort[token[1]] ?: return Parsed.Rejected("unsupported option -${token[1]}")
                    attached = token.substring(2).ifEmpty { null }
                }
                else -> return Parsed.Rejected("unexpected text '${token.take(20)}'")
            }

            if (option.kind == Kind.FLAG) {
                if (attached != null) return Parsed.Rejected("option -${option.short} takes no value")
                args += "-${option.short}"
                continue
            }

            val value = attached ?: tokens.getOrNull(index++)
                ?: return Parsed.Rejected("option -${option.short} needs a value")
            val grammar = when (option.kind) {
                Kind.NUMERIC -> numericValue
                Kind.LIST -> listValue
                Kind.BYTE -> byteValue
                Kind.FAKE_SNI -> fakeSniValue
                Kind.FLAG -> error("flag handled above")
            }
            if (!grammar.matches(value)) {
                return Parsed.Rejected("bad value for -${option.short}: '${value.take(20)}'")
            }
            val finalValue = if (option.kind == Kind.FAKE_SNI) {
                value.replace(FAKE_SNI_PLACEHOLDER, DEFAULT_FAKE_SNI)
            } else {
                value
            }
            args += "-${option.short}$finalValue"
        }
        return Parsed.Ok(args)
    }

    /**
     * Valid, de-duplicated strategy lines from a text file (one strategy per line, blank lines and
     * `#` comments ignored). Lines are returned with normalised whitespace but otherwise as written,
     * so the `{sni}` placeholder stays visible in the UI.
     */
    fun parseList(text: String, maxItems: Int = 200): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty() || line.startsWith("#")) continue
            if (parse(line) is Parsed.Ok) seen += line
            if (seen.size >= maxItems) break
        }
        return seen.toList()
    }
}

/**
 * Strategies that ship with the app, so the feature works offline and before any list update.
 * They only use options documented in the ByeDPI README; which one works, if any, depends on the
 * network in front of the phone, which is what the search in Settings is for.
 */
object DpiBuiltInStrategies {
    val lines: List<String> = listOf(
        "-d1 -Atorst -r1+s",
        "-d1",
        "-s1+s -d3+s",
        "-f-1 -t8",
        "-f-1 -S",
        "-d1 -f-1 -S",
        "-d1 -f-1 -t8",
        "-r1+s -s1+s",
        "-q3 -d7",
        "-o1 -d1",
        "-n {sni} -f-1 -t8",
        "-f-1 -t10 -Assl_err -f-1 -t5"
    )
}

/**
 * Full engine command line: the listen address is always loopback and chosen by the app, never by
 * a strategy. Attached forms (`-p1234`) because a bare `-p` is an ambiguous abbreviation of several
 * long options for getopt_long_only.
 */
internal fun dpiLaunchArguments(host: String, port: Int, strategyArgs: List<String>): List<String> =
    listOf("-i$host", "-p$port") + strategyArgs
