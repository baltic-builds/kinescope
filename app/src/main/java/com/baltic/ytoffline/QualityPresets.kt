package com.baltic.ytoffline

import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * One yt-dlp option set per quality choice, plus the info needed to
 * publish the result (expected final extension + MIME type), since
 * yt-dlp itself decides the real filename only once it's running.
 *
 * Lives in its own file (moved out of MainActivity in Phase 4) so
 * DownloadService can use the same list without duplicating it.
 */
data class QualityPreset(
    val label: String,
    val expectedExtension: String,
    val mimeType: String,
    val apply: YoutubeDLRequest.() -> Unit
)

val qualityPresets = listOf(
    QualityPreset("1080p", "mp4", "video/mp4") {
        addOption("-f", "bv*[height<=1080]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("720p", "mp4", "video/mp4") {
        addOption("-f", "bv*[height<=720]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("480p", "mp4", "video/mp4") {
        addOption("-f", "bv*[height<=480]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("Audio only (MP3)", "mp3", "audio/mpeg") {
        addOption("-x")
        addOption("--audio-format", "mp3")
    }
)
