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
        addOption("-f", "bv*[height<=1080]+ba/b[height<=1080]")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(QualityId.VIDEO_720, R.string.quality_720p, "video/mp4") {
        addOption("-f", "bv*[height<=720]+ba/b[height<=720]")
        addOption("--merge-output-format", "mp4")
    },
    QualityPreset(QualityId.VIDEO_480, R.string.quality_480p, "video/mp4") {
        addOption("-f", "bv*[height<=480]+ba/b[height<=480]")
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
