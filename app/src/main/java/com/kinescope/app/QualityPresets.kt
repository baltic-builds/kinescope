package com.kinescope.app

import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * One yt-dlp option set per quality choice, plus the MIME type needed
 * to publish the result to MediaStore. yt-dlp itself decides the real
 * filename (including title and extension) only once it's running --
 * see DownloadService.runJob(), which locates the output file by its
 * embedded job-id tag afterward rather than assuming a fixed name
 * (ROADMAP.md Step 4), so an `expectedExtension` field here is no
 * longer needed.
 *
 * Lives in its own file (moved out of MainActivity in Phase 4) so
 * DownloadService can use the same list without duplicating it.
 */
data class QualityPreset(
    val label: String,
    val mimeType: String,
    val apply: YoutubeDLRequest.() -> Unit
)

val qualityPresets = listOf(
    QualityPreset("1080p", "video/mp4") {
        addOption("-f", "bv*[height<=1080]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("720p", "video/mp4") {
        addOption("-f", "bv*[height<=720]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("480p", "video/mp4") {
        addOption("-f", "bv*[height<=480]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset("Audio only (MP3)", "audio/mpeg") {
        addOption("-x")
        addOption("--audio-format", "mp3")
    }
)
