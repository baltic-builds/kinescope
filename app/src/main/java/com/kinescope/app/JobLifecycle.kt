package com.kinescope.app

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.ConcurrentHashMap

internal enum class ControlAction { PAUSE, STOP }

internal class ControlledStop(val action: ControlAction) : RuntimeException()

internal class AlreadyHandledFailure : RuntimeException()

/**
 * Pause and Stop requests that arrived while a job was queued or running, keyed by job id. Process-wide on
 * purpose: the service instance can be recreated while a request is in flight.
 */
internal object JobControls {
    val requested = ConcurrentHashMap<String, ControlAction>()
}

/**
 * Patch 40: every journal + queue transition of a job (Preparing, Paused, Interrupted, Failed, ...) and the
 * handling of an honored Pause/Stop, in one place. A [ContextWrapper] so the moved code runs unchanged.
 */
internal class JobLifecycle(base: Context) : ContextWrapper(base) {
    fun finishControlled(job: StoredDownloadJob, action: ControlAction) {
        JobControls.requested.remove(job.id)
        when (action) {
            ControlAction.PAUSE -> {
                transition(job, JobState.PAUSED, getString(R.string.status_paused))
                AppLog.i("DownloadService", "Paused job=${job.id}; workspace kept for resume")
            }
            ControlAction.STOP -> {
                DownloadJobStore.pendingUri(job)?.let { MediaStorage.deletePending(this, it) }
                DownloadJobStore.remove(this, job.id)
                DownloadJobStore.cleanupWorkspace(this, job.id)
                DownloadQueueBus.remove(job.id)
                AppLog.i("DownloadService", "Stopped job=${job.id}; workspace removed")
            }
        }
    }

    fun pauseForVerification(
        job: StoredDownloadJob,
        message: String,
        kind: FailureKind = FailureKind.YOUTUBE_VERIFICATION
    ) {
        transition(
            job,
            JobState.PAUSED,
            message,
            failureKind = kind
        )
        AppLog.w("DownloadService", "Job=${job.id} paused kind=$kind; resumable")
    }

    fun interruptJob(job: StoredDownloadJob, kind: FailureKind, message: String) {
        transition(job, JobState.INTERRUPTED, message, failureKind = kind)
        AppLog.w("DownloadService", "Job=${job.id} interrupted kind=$kind")
    }

    fun failJob(job: StoredDownloadJob, kind: FailureKind, message: String) {
        transition(job, JobState.FAILED, message, failureKind = kind)
        AppLog.e("DownloadService", "Job=${job.id} failed kind=$kind")
    }

    fun transition(
        job: StoredDownloadJob,
        state: JobState,
        text: String,
        failureKind: FailureKind? = job.failureKind
    ): StoredDownloadJob {
        val updated = DownloadJobStore.update(this, job.id) { current ->
            current.copy(state = state, failureKind = failureKind)
        } ?: job.copy(state = state, failureKind = failureKind)
        DownloadQueueBus.upsert(
            DownloadJobStatus(
                id = updated.id,
                url = updated.canonicalUrl,
                qualityLabel = getString(qualityPreset(updated.qualityId).labelRes),
                state = state,
                progressText = text,
                progressFraction = null,
                failureKind = failureKind
            )
        )
        return updated
    }
}
