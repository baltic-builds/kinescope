package com.kinescope.app

/** Pure classification layer so yt-dlp text matching is testable and localized UI stays separate. */
object DownloadErrorClassifier {
    fun classify(raw: String?): FailureKind {
        val lower = raw.orEmpty().lowercase()
        return when {
            lower.contains("sign in to confirm") ||
                lower.contains("not a bot") ||
                lower.contains("http error 403") ||
                lower.contains("http error 429") ||
                lower.contains("too many requests") ||
                lower.contains("requested format is not available") -> FailureKind.YOUTUBE_VERIFICATION

            lower.contains("private video") -> FailureKind.PRIVATE_VIDEO

            // Patch 31: was `lower.contains("age") && (lower.contains("confirm") ||
            // lower.contains("restrict"))`. "webpage" contains "age", and yt-dlp's generic
            // "...Confirm you are on the latest version using yt-dlp -U" trailer (printed on
            // many unrelated extractor errors) contains "confirm" -- so any ordinary failure
            // that happens to mention "webpage" (a very common phrase: "Unable to download
            // webpage") was misclassified as age-restricted. Match the real yt-dlp phrases
            // instead: "Sign in to confirm your age. This video may be inappropriate for some
            // users." (already caught by "sign in to confirm" above) and the newer
            // "This video is age-restricted; some formats may be missing without
            // authentication." warning.
            lower.contains("age-restricted") ||
                lower.contains("inappropriate for some users") ||
                lower.contains("confirm your age") -> FailureKind.AGE_RESTRICTED

            lower.contains("unavailable") || lower.contains("video is not available") -> FailureKind.UNAVAILABLE
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ||
                lower.contains("network is unreachable") -> FailureKind.NO_INTERNET

            // Patch 33: the proxy or the socket could not carry the connection (a SOCKS5 "Host
            // unreachable", a reset, a timeout). Previously this fell through to OTHER and the
            // user saw a raw yt-dlp line, or nothing at all while the run kept retrying.
            isTransportFailureLower(lower) -> FailureKind.CONNECTION_BLOCKED

            else -> FailureKind.OTHER
        }
    }

    /** True for failures of the connection itself rather than of YouTube's answer. Patch 33. */
    fun isTransportFailure(raw: String?): Boolean = isTransportFailureLower(raw.orEmpty().lowercase())

    private fun isTransportFailureLower(lower: String): Boolean =
        TRANSPORT_MARKERS.any { lower.contains(it) }

    private val TRANSPORT_MARKERS = listOf(
        "proxyerror",
        "host unreachable",
        "connection refused",
        "connection reset",
        "connection aborted",
        "remote end closed",
        "timed out",
        "timeout",
        "eof occurred",
        "unable to connect to proxy",
        "socks",
        "bypass transport failure"
    )

    fun isRecoverableYoutubeBlock(raw: String?): Boolean =
        classify(raw) in RECOVERABLE_KINDS

    // Patch 31: previously only YOUTUBE_VERIFICATION continued the 4-profile recovery chain
    // (DEFAULT -> DEFAULT_AFTER_REFRESH -> WEB_SAFARI_IPV4 -> ANDROID_VR_LOGGED_OUT); every
    // other failure kind broke out after the very first attempt. AGE_RESTRICTED and OTHER now
    // also keep going: a real age-gate can still succeed on a different client profile
    // (ANDROID_VR_LOGGED_OUT is a known yt-dlp technique for some age-restricted videos without
    // login), and OTHER covers transient bypass/network hiccups -- like the "Host unreachable"
    // case that was misclassified as AGE_RESTRICTED above -- that a later attempt, possibly
    // through a different verified strategy, can genuinely recover from. PRIVATE_VIDEO,
    // UNAVAILABLE and NO_INTERNET stay non-recoverable: none of them depend on which client
    // profile is used.
    private val RECOVERABLE_KINDS = setOf(
        FailureKind.YOUTUBE_VERIFICATION,
        FailureKind.AGE_RESTRICTED,
        FailureKind.OTHER,
        // Patch 33: still retried through the profile chain when no bypass ladder ran (a plain
        // connection dropping is transient). When the ladder ran and every strategy plus the
        // direct fallback failed, DownloadService stops the chain itself (BypassTransportException).
        FailureKind.CONNECTION_BLOCKED
    )
}
