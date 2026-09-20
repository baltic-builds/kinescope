package com.kinescope.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic journal for accepted downloads; progress ticks are intentionally not persisted. */
data class StoredDownloadJob(
    val id: String,
    val canonicalUrl: String,
    val videoId: String,
    val qualityId: QualityId,
    val state: JobState,
    val failureKind: FailureKind? = null,
    val destinationSubfolder: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val pendingUri: String? = null
)

object DownloadJobStore {
    private const val FILE_NAME = "download-jobs.json"
    private const val FORMAT_VERSION = 1
    private const val MAX_JOBS = 64

    @Synchronized
    fun load(context: Context): List<StoredDownloadJob> {
        val file = journalFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val text = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
            decode(text)
        } catch (e: Exception) {
            val quarantine = File(context.filesDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
            runCatching { file.renameTo(quarantine) }
            AppLog.e("JobStore", "Job journal was corrupt; quarantined and starting empty", e)
            emptyList()
        }
    }

    @Synchronized
    fun find(context: Context, id: String): StoredDownloadJob? = load(context).firstOrNull { it.id == id }

    @Synchronized
    fun findActiveDuplicate(context: Context, videoId: String): StoredDownloadJob? =
        load(context).firstOrNull { it.videoId == videoId && it.state !in TERMINAL_STATES }

    @Synchronized
    fun upsert(context: Context, job: StoredDownloadJob) {
        val current = load(context).filterNot { it.id == job.id }.toMutableList()
        current += job
        write(context, current.sortedBy { it.createdAtMillis }.takeLast(MAX_JOBS))
    }

    @Synchronized
    fun update(
        context: Context,
        id: String,
        transform: (StoredDownloadJob) -> StoredDownloadJob
    ): StoredDownloadJob? {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = transform(current[index]).copy(updatedAtMillis = System.currentTimeMillis())
        current[index] = updated
        write(context, current)
        return updated
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, load(context).filterNot { it.id == id })
    }

    /**
     * Rebuilds the UI projection after process death. Any accepted job that
     * was queued or executing becomes INTERRUPTED, never silently restarted.
     */
    @Synchronized
    fun restoreToBus(context: Context) {
        val persisted = load(context).filterNot { it.state in TERMINAL_STATES }.sortedBy { it.createdAtMillis }
        val normalized = persisted.map { original ->
            var job = original
            pendingUri(job)?.let {
                MediaStorage.deletePending(context, it)
                job = job.copy(pendingUri = null)
            }
            if (job.state == JobState.QUEUED || job.state in EXECUTION_STATES) {
                job = job.copy(
                    state = JobState.INTERRUPTED,
                    failureKind = FailureKind.CANCELLED,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            if (job != original) upsert(context, job)
            job
        }

        // Application restores this journal on an IO coroutine. Merge each
        // recovered row into the in-process projection rather than replacing
        // the whole list: a very fast enqueue from the freshly-created UI can
        // otherwise land between the disk read and this publish step and be
        // accidentally erased from the visible queue.
        normalized.forEach { job ->
            DownloadQueueBus.upsert(
                DownloadJobStatus(
                    id = job.id,
                    url = job.canonicalUrl,
                    qualityLabel = context.getString(qualityPreset(job.qualityId).labelRes),
                    state = job.state,
                    progressText = restoredStatusText(context, job),
                    progressFraction = null,
                    failureKind = job.failureKind
                )
            )
        }
        cleanupOrphanedWorkspaces(context, normalized.mapTo(mutableSetOf()) { it.id })
        if (normalized.isNotEmpty()) AppLog.i("JobStore", "Restored ${normalized.size} recoverable job(s)")
    }

    fun workspaceDir(context: Context, id: String): File = File(context.cacheDir, "jobs/$id")

    fun cleanupWorkspace(context: Context, id: String) {
        runCatching { workspaceDir(context, id).deleteRecursively() }
            .onFailure { AppLog.e("JobStore", "Could not clean workspace job=$id", it) }
    }

    fun pendingUri(job: StoredDownloadJob): Uri? =
        job.pendingUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun cleanupOrphanedWorkspaces(context: Context, liveIds: Set<String>) {
        val root = File(context.cacheDir, "jobs")
        root.listFiles()?.filter { it.isDirectory && it.name !in liveIds }?.forEach { directory ->
            runCatching { directory.deleteRecursively() }
                .onFailure { AppLog.e("JobStore", "Could not clean orphan workspace", it) }
        }
    }

    private fun restoredStatusText(context: Context, job: StoredDownloadJob): String = when {
        job.state == JobState.PAUSED && job.failureKind == FailureKind.YOUTUBE_VERIFICATION ->
            context.getString(R.string.error_youtube_verification)
        job.state == JobState.PAUSED -> context.getString(R.string.status_paused)
        job.state == JobState.INTERRUPTED -> context.getString(R.string.status_interrupted)
        job.state == JobState.FAILED -> context.getString(R.string.status_retry_available)
        else -> context.getString(R.string.status_retry_available)
    }

    private fun write(context: Context, jobs: List<StoredDownloadJob>) {
        val atomic = AtomicFile(journalFile(context))
        val payload = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("jobs", JSONArray().apply { jobs.forEach { put(it.toJson()) } })
        }.toString()

        val stream = atomic.startWrite()
        try {
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun decode(text: String): List<StoredDownloadJob> {
        val root = JSONObject(text)
        if (root.optInt("version", -1) != FORMAT_VERSION) {
            throw IllegalStateException("Unsupported job-journal version")
        }
        val array = root.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val quality = QualityId.fromPersisted(obj.optString("qualityId")) ?: QualityId.VIDEO_1080
                val state = runCatching { JobState.valueOf(obj.getString("state")) }.getOrDefault(JobState.INTERRUPTED)
                val failure = obj.optString("failureKind").takeIf { it.isNotBlank() }
                    ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
                add(
                    StoredDownloadJob(
                        id = obj.getString("id"),
                        canonicalUrl = obj.getString("canonicalUrl"),
                        videoId = obj.getString("videoId"),
                        qualityId = quality,
                        state = state,
                        failureKind = failure,
                        destinationSubfolder = obj.optString("destinationSubfolder", Settings.DEFAULT_SUBFOLDER),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                        pendingUri = obj.optString("pendingUri").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun StoredDownloadJob.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("canonicalUrl", canonicalUrl)
        put("videoId", videoId)
        put("qualityId", qualityId.persistedValue)
        put("state", state.name)
        put("failureKind", failureKind?.name ?: "")
        put("destinationSubfolder", destinationSubfolder)
        put("createdAtMillis", createdAtMillis)
        put("updatedAtMillis", updatedAtMillis)
        put("pendingUri", pendingUri ?: "")
    }

    private fun journalFile(context: Context) = File(context.filesDir, FILE_NAME)

    private val EXECUTION_STATES = setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)
    private val TERMINAL_STATES = setOf(JobState.DONE, JobState.STOPPED)
}
