package com.kinescope.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Patch 40: turns a finished workspace file into a library item: picks the output, commits it through MediaStorage
 * (two-phase, honoring a Pause/Stop that arrives while saving) and clears the journal entry.
 */
internal class DownloadPublisher(
    base: Context,
    private val lifecycle: JobLifecycle,
    private val notifier: DownloadNotifier
) : ContextWrapper(base) {
    fun publishCompletedOutput(job: StoredDownloadJob, preset: QualityPreset, outputFile: File) {
        lifecycle.transition(job, JobState.SAVING, getString(R.string.status_saving))
        // Download bytes/merge are already complete. Remove the notification's
        // Stop action while committing the file so a normal user cannot create
        // a new control request in the tiny finalization window.
        notifier.update(getString(R.string.status_saving), null)
        JobControls.requested.remove(job.id)?.let {
            lifecycle.finishControlled(job, it)
            return
        }

        val publishedUri = MediaStorage.publish(
            context = this,
            tempFile = outputFile,
            preferredMimeType = preset.preferredMimeType,
            subfolder = job.destinationSubfolder,
            displayName = outputFile.name,
            onPendingUri = { uri ->
                DownloadJobStore.update(this, job.id) { current -> current.copy(pendingUri = uri.toString()) }
            },
            shouldCancel = { JobControls.requested.containsKey(job.id) }
        )

        JobControls.requested.remove(job.id)?.let { action ->
            if (publishedUri != null && !MediaStorage.deleteUri(this, publishedUri)) {
                lifecycle.failJob(job, FailureKind.STORAGE, getString(R.string.error_control_cleanup_failed))
                return
            }
            lifecycle.finishControlled(job, action)
            return
        }

        if (publishedUri != null) {
            DownloadJobStore.remove(this, job.id)
            DownloadJobStore.cleanupWorkspace(this, job.id)
            DownloadQueueBus.complete(job.id)
            notifier.showCompletion(job.id)
            AppLog.i("DownloadService", "Completed job=${job.id}")
        } else {
            lifecycle.failJob(job, FailureKind.STORAGE, getString(R.string.error_failed_to_save))
        }
    }

    fun findFinalOutput(workspace: File): File? = workspace
        .listFiles { file ->
            file.isFile && INTERMEDIATE_SUFFIXES.none { suffix -> file.name.endsWith(suffix, ignoreCase = true) }
        }
        ?.maxByOrNull { it.lastModified() }

    private companion object {
        private val INTERMEDIATE_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".ffmpeg")
    }
}
