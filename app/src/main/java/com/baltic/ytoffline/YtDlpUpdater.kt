package com.baltic.ytoffline

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Wraps the youtubedl-android library's self-update mechanism, so the
 * bundled yt-dlp (and therefore the extractor) can be refreshed
 * without an app rebuild. See ROADMAP.md Phase 5 and the "no custom
 * extractor" ground rule in CLAUDE.md — this update mechanism is the
 * intended way to keep extraction working as YouTube changes things
 * over time, instead of us reverse-engineering anything ourselves.
 *
 * ROADMAP.md Step 2 [fixed]: the library's current README (matching
 * the 0.18.1 version pinned in app/build.gradle.kts) documents
 * `updateYoutubeDL(context, updateChannel)` — a required UpdateChannel
 * argument — not the single-argument call this file used to have.
 * STABLE is used here since this app never wants nightly/pre-release
 * yt-dlp builds on a personal device. The try/catch stays
 * deliberately broad, and the result is immediately turned into a
 * String via `.toString()`, so a wrong assumption about the *exact*
 * return type (enum vs. String) still fails soft rather than crashing
 * app startup.
 *
 * ROADMAP.md Step 6.5: records a last-updated timestamp on success
 * only (not on failure), for Settings' Extractor section.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"

    /** Blocking — call this from a background thread, not the main thread. */
    fun updateBlocking(context: Context): String {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.STABLE)
            Log.i(TAG, "yt-dlp update result: $status")
            Settings.setLastUpdateTimestamp(context, System.currentTimeMillis())
            status.toString()
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            "Update check failed: ${e.message}"
        }
    }
}
