package com.baltic.ytoffline

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Wraps the youtubedl-android library's self-update mechanism, so the
 * bundled yt-dlp (and therefore the extractor) can be refreshed
 * without an app rebuild. See ROADMAP.md Phase 5 and the "no custom
 * extractor" ground rule in CLAUDE.md — this update mechanism is the
 * intended way to keep extraction working as YouTube changes things
 * over time, instead of us reverse-engineering anything ourselves.
 *
 * NOTE: the exact return type of `updateYoutubeDL()` (an enum with
 * values roughly like DONE / ALREADY_UP_TO_DATE in the versions of
 * this library Claude has seen) was not verified against a real
 * compile — same caveat as the other youtubedl-android calls
 * elsewhere in this project (see ROADMAP.md "open risks"). The
 * try/catch here is deliberately broad so a wrong assumption fails
 * soft (logged, non-fatal) rather than crashing app startup.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"

    /** Blocking — call this from a background thread, not the main thread. */
    fun updateBlocking(context: Context): String {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            Log.i(TAG, "yt-dlp update result: $status")
            status.toString()
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            "Update check failed: ${e.message}"
        }
    }
}
