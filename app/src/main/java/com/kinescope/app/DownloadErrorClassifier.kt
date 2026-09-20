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
            lower.contains("age") && (lower.contains("confirm") || lower.contains("restrict")) ->
                FailureKind.AGE_RESTRICTED

            lower.contains("unavailable") || lower.contains("video is not available") -> FailureKind.UNAVAILABLE
            lower.contains("unable to resolve host") || lower.contains("unknownhost") ||
                lower.contains("network is unreachable") -> FailureKind.NO_INTERNET

            else -> FailureKind.OTHER
        }
    }

    fun isRecoverableYoutubeBlock(raw: String?): Boolean =
        classify(raw) == FailureKind.YOUTUBE_VERIFICATION
}
