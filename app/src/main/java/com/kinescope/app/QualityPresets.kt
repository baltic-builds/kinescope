package com.kinescope.app

import com.yausername.youtubedl_android.YoutubeDLRequest

/** Stable preset identity survives display-order changes and persisted settings. */
enum class QualityId(val persistedValue: String) {
    VIDEO_1080("VIDEO_1080"),
    VIDEO_720("VIDEO_720"),
    VIDEO_480("VIDEO_480"),
    AUDIO_MP3("AUDIO_MP3");

    companion object {
        fun fromPersisted(value: String?): QualityId? = entries.firstOrNull { it.persistedValue == value }
    }
}

data class QualityPreset(
    val id: QualityId,
    val labelRes: Int,
    val preferredMimeType: String,
    val apply: YoutubeDLRequest.() -> Unit
)

val qualityPresets = listOf(
    QualityPreset(QualityId.VIDEO_1080, R.string.quality_1080p, "video/mp4") {
        addOption(
            "-f",
            "bv*[vcodec^=avc][height<=1080]+ba[acodec^=mp4a]/" +
                "bv*[height<=1080]+ba/b[height<=1080]"
        )
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(QualityId.VIDEO_720, R.string.quality_720p, "video/mp4") {
        addOption(
            "-f",
            "bv*[vcodec^=avc][height<=720]+ba[acodec^=mp4a]/" +
                "bv*[height<=720]+ba/b[height<=720]"
        )
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(QualityId.VIDEO_480, R.string.quality_480p, "video/mp4") {
        addOption(
            "-f",
            "bv*[vcodec^=avc][height<=480]+ba[acodec^=mp4a]/" +
                "bv*[height<=480]+ba/b[height<=480]"
        )
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(QualityId.AUDIO_MP3, R.string.quality_audio_mp3, "audio/mpeg") {
        addOption("-x")
        addOption("--audio-format", "mp3")
    }
)

fun qualityPreset(id: QualityId): QualityPreset =
    qualityPresets.firstOrNull { it.id == id } ?: qualityPresets.first()

fun qualityPresetIndex(id: QualityId): Int =
    qualityPresets.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0

/**
 * Patch 37: Instagram has no YouTube-style quality ladder. The video presets only cap the height
 * (see [InstagramFormats]) and the audio preset still extracts MP3, so the chips keep working
 * unchanged and the Add screen needs no Instagram-specific control.
 */
fun YoutubeDLRequest.applyInstagramFormat(id: QualityId) {
    val cap: Int? = when (id) {
        QualityId.VIDEO_1080 -> 1080
        QualityId.VIDEO_720 -> 720
        QualityId.VIDEO_480 -> 480
        QualityId.AUDIO_MP3 -> null
    }
    if (cap == null) {
        addOption("-x")
        addOption("--audio-format", "mp3")
    } else {
        addOption("-f", InstagramFormats.selector(cap))
        addOption("--merge-output-format", "mp4")
    }
}
