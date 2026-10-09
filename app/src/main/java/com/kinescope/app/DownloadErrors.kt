package com.kinescope.app

import android.content.Context

/** Patch 40: the user-facing text of a failed download (was `DownloadService.friendlyError`). */
internal fun Context.friendlyDownloadError(raw: String?, source: MediaSource = MediaSource.YOUTUBE): String {
    val message = raw.orEmpty()
    val kind = if (source == MediaSource.INSTAGRAM) {
        DownloadErrorClassifier.classifyInstagram(raw)
    } else {
        DownloadErrorClassifier.classify(raw)
    }
    return when (kind) {
        FailureKind.INSTAGRAM_LOGIN -> getString(R.string.error_instagram_login)
        FailureKind.YOUTUBE_VERIFICATION -> if (NetworkState.systemVpnActive(this)) {
            getString(R.string.error_verification_vpn)
        } else {
            getString(R.string.error_youtube_verification)
        }
        FailureKind.PRIVATE_VIDEO -> getString(R.string.error_private_video)
        FailureKind.AGE_RESTRICTED -> getString(R.string.error_age_restricted)
        FailureKind.UNAVAILABLE -> getString(R.string.error_video_unavailable)
        FailureKind.NO_INTERNET -> getString(R.string.error_no_internet)
        FailureKind.CONNECTION_BLOCKED -> getString(
            R.string.error_connection_blocked,
            if (source == MediaSource.INSTAGRAM) "Instagram" else "YouTube"
        )
        else -> if (message.isBlank()) {
            getString(R.string.error_unknown)
        } else {
            // AppLog retains the technical detail in redacted form. The UI
            // gets only a bounded message to avoid full yt-dlp dumps.
            getString(R.string.error_download_generic, message.lineSequence().first().take(180))
        }
    }
}
