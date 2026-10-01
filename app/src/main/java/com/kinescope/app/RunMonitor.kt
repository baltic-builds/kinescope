package com.kinescope.app

/**
 * Patch 35: judges a running yt-dlp process from what it prints and from how many bytes the app
 * receives. Pure logic with an injected clock, unit-tested in RunMonitorTest.
 *
 * Why it exists. `youtubedl-android` 0.18.1 reports progress with a regex that needs `ETA mm:ss`
 * at the end of a progress line. yt-dlp prints `ETA Unknown` while it has no speed, so the
 * wrapper keeps reporting progress -1 / ETA -1 (the "ETA -1" the queue used to show) even though
 * the download is running. Here the line is parsed directly, so percent and speed are known.
 * Only stdout reaches the wrapper's callback; yt-dlp's retry warnings go to stderr.
 */
internal data class MonitorLimits(
    /** No sign of life while yt-dlp extracts (page, player API, formats). */
    val startMs: Long,
    /** yt-dlp is silent while the bundled QuickJS solves YouTube's JavaScript challenge (CPU bound). */
    val jsMs: Long,
    /** No stdout line and no network bytes while downloading. */
    val idleMs: Long,
    /** Lowest acceptable average speed in KB/s over [slowWindowMs]; 0 disables the speed judgement. */
    val minKbps: Int = 0,
    val slowWindowMs: Long = 25_000L
)

internal sealed interface RunVerdict {
    object Ok : RunVerdict
    data class Stalled(val phase: String, val idleMs: Long) : RunVerdict
    data class Slow(val kbps: Int) : RunVerdict
}

internal data class ParsedLine(val percent: Float?, val kbps: Int?)

internal object YtdlpLine {
    private val percent = Regex("""\[download]\s+(\d+(?:\.\d+)?)%""")
    private val speed = Regex("""\bat\s+~?\s*(\d+(?:\.\d+)?)\s*([KMG]?)i?B/s""")

    fun parse(line: String): ParsedLine {
        val pct = percent.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val match = speed.find(line)
        val kbps = match?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val factor = when (it.groupValues[2]) {
                "K" -> 1.0
                "M" -> 1024.0
                "G" -> 1024.0 * 1024.0
                else -> 1.0 / 1024.0
            }
            (value * factor).toInt()
        }
        return ParsedLine(pct, kbps)
    }

    fun isJsChallenge(line: String): Boolean =
        line.contains("Solving JS challenges", ignoreCase = true) || line.contains("[jsc", ignoreCase = true)

    fun isQuiet(line: String): Boolean =
        line.contains("[Merger]") || line.contains("[Fixup") || line.contains("[ExtractAudio]") ||
            line.contains("[VideoRemuxer]") || line.contains("[Metadata]") || line.contains("[MoveFiles]")
}

internal class RunMonitor(private val limits: MonitorLimits, private val now: () -> Long) {
    enum class Phase(val tag: String) { START("start"), JS("js"), DOWNLOAD("dl"), QUIET("quiet") }

    data class Snapshot(
        val phase: Phase,
        val lines: Int,
        val percent: Float,
        val kbps: Int,
        val idleMs: Long,
        val lastLine: String
    )

    private val startedAt = now()
    private var lastStdout = startedAt
    private var lastNetwork = startedAt
    private var phase = Phase.START
    private var downloadStartedAt = -1L
    private var lines = 0
    private var percent = -1f
    private var lastKbps = 0
    private var lastLine = ""
    private val samples = ArrayDeque<Pair<Long, Int>>()

    @Synchronized
    fun onLine(line: String) {
        val time = now()
        lastStdout = time
        lines++
        lastLine = line.trim()
        val parsed = YtdlpLine.parse(line)
        when {
            YtdlpLine.isQuiet(line) -> phase = Phase.QUIET
            parsed.percent != null -> {
                phase = Phase.DOWNLOAD
                percent = parsed.percent
                if (downloadStartedAt < 0) downloadStartedAt = time
                parsed.kbps?.let {
                    lastKbps = it
                    samples.addLast(time to it)
                }
            }
            YtdlpLine.isJsChallenge(line) -> phase = Phase.JS
            phase == Phase.QUIET || phase == Phase.JS -> phase = if (downloadStartedAt >= 0) Phase.DOWNLOAD else Phase.START
        }
        trim(time)
    }

    /** Called with the number of bytes the app received since the last call; a trickle does not count. */
    @Synchronized
    fun onNetworkBytes(delta: Long) {
        if (delta >= MIN_BYTES_FOR_ACTIVITY) lastNetwork = now()
    }

    @Synchronized
    fun verdict(): RunVerdict {
        val time = now()
        if (phase == Phase.QUIET) return RunVerdict.Ok
        val idle = minOf(time - lastStdout, time - lastNetwork)
        val limit = when (phase) {
            Phase.START -> limits.startMs
            Phase.JS -> limits.jsMs
            else -> limits.idleMs
        }
        if (idle > limit) return RunVerdict.Stalled(phase.tag, idle)
        if (limits.minKbps > 0 && phase == Phase.DOWNLOAD && downloadStartedAt >= 0 &&
            time - downloadStartedAt >= limits.slowWindowMs
        ) {
            trim(time)
            if (samples.size >= MIN_SAMPLES) {
                val average = samples.map { it.second }.average().toInt()
                if (average < limits.minKbps) return RunVerdict.Slow(average)
            }
        }
        return RunVerdict.Ok
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        phase, lines, percent, lastKbps, minOf(now() - lastStdout, now() - lastNetwork), lastLine
    )

    private fun trim(time: Long) {
        while (samples.isNotEmpty() && time - samples.first().first > limits.slowWindowMs) samples.removeFirst()
    }

    private companion object {
        const val MIN_BYTES_FOR_ACTIVITY = 2_000L
        const val MIN_SAMPLES = 3
    }
}
