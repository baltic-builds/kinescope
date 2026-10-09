package com.kinescope.app

import android.content.Context
import android.content.ContextWrapper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlin.random.Random

internal enum class RecoveryProfile(
    val extractorArgs: String?,
    val forceIpv4: Boolean,
    val useCookies: Boolean
) {
    DEFAULT(null, false, true),
    DEFAULT_AFTER_REFRESH(null, false, true),
    WEB_SAFARI_IPV4("youtube:player_client=web_safari", true, true),
    ANDROID_VR_LOGGED_OUT("youtube:player_client=android_vr", false, false)
}

/**
 * Patch 40: the bounded recovery chain of one download: plain run, nightly yt-dlp refresh, then the YouTube
 * client fallbacks (Instagram stops after the refresh). A failure that only a sign-in can fix parks the job as a
 * resumable Pause. Each attempt runs through [RouteLadder].
 */
internal class RecoveryChain(
    base: Context,
    private val ladder: RouteLadder,
    private val lifecycle: JobLifecycle,
    private val notifier: DownloadNotifier
) : ContextWrapper(base) {
    fun executeWithRecovery(
        job: StoredDownloadJob,
        preset: QualityPreset,
        outputTemplate: String
    ) {
        val source = job.mediaSource
        val instagramSession = source == MediaSource.INSTAGRAM && InstagramAuth.hasSavedSession(this)
        // Patch 37: the YouTube player-client profiles mean nothing for Instagram. Its chain is the
        // plain run plus one retry after a nightly yt-dlp refresh (extractor fixes land there), and
        // only when DownloadErrorClassifier.isRecoverableInstagram says a retry can help.
        val attempts = if (source == MediaSource.INSTAGRAM) {
            listOf(RecoveryProfile.DEFAULT, RecoveryProfile.DEFAULT_AFTER_REFRESH)
        } else {
            listOf(
                RecoveryProfile.DEFAULT,
                RecoveryProfile.DEFAULT_AFTER_REFRESH,
                RecoveryProfile.WEB_SAFARI_IPV4,
                RecoveryProfile.ANDROID_VR_LOGGED_OUT
            )
        }
        var nightlyRefreshAttempted = false
        var lastError: String? = null

        for ((index, profile) in attempts.withIndex()) {
            JobControls.requested.remove(job.id)?.let { throw ControlledStop(it) }

            if (profile == RecoveryProfile.DEFAULT_AFTER_REFRESH && !nightlyRefreshAttempted) {
                nightlyRefreshAttempted = true
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_update))
                }
                AppLog.w("DownloadService", "Refreshing yt-dlp nightly before recovery job=${job.id}")
                runCatching { EngineController.updateNightly(this) }
                    .onFailure { AppLog.e("DownloadService", "Recovery updater failed job=${job.id}", it) }
            }

            if (index > 0) {
                DownloadQueueBus.update(job.id) {
                    it.copy(progressText = getString(R.string.status_recovering_retry, index + 1, attempts.size))
                }
                try {
                    Thread.sleep(Random.nextLong(1_500L, 3_001L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }

            JobControls.requested.remove(job.id)?.let { throw ControlledStop(it) }
            lifecycle.transition(job, JobState.RUNNING, getString(R.string.status_preparing_download), failureKind = null)
            notifier.update(getString(R.string.status_preparing_download), job.id)

            try {
                // Patch 27: an optional local DPI-bypass engine (see DpiBypass.kt). It is off by default;
                // when the user has switched it on in Settings, this starts a fresh engine process for
                // exactly this attempt and always tears it down afterwards, success or failure, so no
                // orphaned ":dpi" process survives a crash or a cancelled attempt.
                ladder.executeWithBypassFallback(job, preset, outputTemplate, profile)
                return
            } catch (e: YoutubeDL.CanceledException) {
                val action = JobControls.requested.remove(job.id) ?: ControlAction.STOP
                throw ControlledStop(action)
            } catch (e: YoutubeDLException) {
                lastError = e.message.orEmpty()
                val recoverable = if (source == MediaSource.INSTAGRAM) {
                    DownloadErrorClassifier.isRecoverableInstagram(lastError, instagramSession)
                } else {
                    DownloadErrorClassifier.isRecoverableYoutubeBlock(lastError)
                }
                AppLog.e(
                    "DownloadService",
                    "Attempt ${index + 1}/${attempts.size} failed job=${job.id} profile=$profile recoverable=$recoverable",
                    e
                )
                if (!recoverable || index == attempts.lastIndex) break
            } catch (e: BypassTransportException) {
                // Patch 33: the ladder already tried every strategy and a direct connection. A
                // different yt-dlp client profile cannot fix a connection that does not work.
                lastError = e.message.orEmpty()
                AppLog.e("DownloadService", "Attempt ${index + 1}/${attempts.size} could not reach YouTube job=${job.id}", e)
                break
            }
        }

        val kind = if (source == MediaSource.INSTAGRAM) {
            DownloadErrorClassifier.classifyInstagram(lastError)
        } else {
            DownloadErrorClassifier.classify(lastError)
        }
        // Both a YouTube bot check and an Instagram login request park the job as a resumable
        // Pause: signing in (Settings or the Home banner) resumes it.
        if (kind == FailureKind.YOUTUBE_VERIFICATION || kind == FailureKind.INSTAGRAM_LOGIN) {
            lifecycle.pauseForVerification(job, friendlyDownloadError(lastError, source), kind)
            throw AlreadyHandledFailure()
        }
        lifecycle.failJob(job, kind, friendlyDownloadError(lastError, source))
        throw AlreadyHandledFailure()
    }
}
