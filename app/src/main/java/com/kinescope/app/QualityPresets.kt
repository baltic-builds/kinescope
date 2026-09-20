package com.kinescope.app

import com.yausername.youtubedl_android.YoutubeDLRequest

/** One yt-dlp option set per quality choice. */
data class QualityPreset(
    val labelRes: Int,
    val mimeType: String,
    val apply: YoutubeDLRequest.() -> Unit
)

val qualityPresets = listOf(
    QualityPreset(R.string.quality_1080p, "video/mp4") {
        addOption("-f", "bv*[height<=1080]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(R.string.quality_720p, "video/mp4") {
        addOption("-f", "bv*[height<=720]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(R.string.quality_480p, "video/mp4") {
        addOption("-f", "bv*[height<=480]+ba/b")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(R.string.quality_audio_mp3, "audio/mpeg") {
        addOption("-x")
        addOption("--audio-format", "mp3")
    }
)
