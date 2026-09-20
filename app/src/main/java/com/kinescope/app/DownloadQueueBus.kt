package com.kinescope.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState {
    QUEUED,
    PREPARING,
    RUNNING,
    PROCESSING,
    SAVING,
    PAUSED,
    INTERRUPTED,
    DONE,
    FAILED,
    STOPPED
}

enum class FailureKind {
    NO_INTERNET,
    YOUTUBE_VERIFICATION,
    PRIVATE_VIDEO,
    AGE_RESTRICTED,
    UNAVAILABLE,
    STORAGE,
    ENGINE,
    CANCELLED,
    OTHER
}

data class DownloadJobStatus(
    val id: String,
    val url: String,
    val qualityLabel: String,
    val state: JobState,
    val progressText: String,
    val progressFraction: Float? = null,
    val failureKind: FailureKind? = null
)

/**
 * In-process projection of the durable download journal. DownloadJobStore is
 * the source of truth across process death; this flow only drives Compose UI.
 */
object DownloadQueueBus {
    private val _jobs = MutableStateFlow<List<DownloadJobStatus>>(emptyList())
    val jobs = _jobs.asStateFlow()

    fun replace(statuses: List<DownloadJobStatus>) {
        _jobs.value = statuses
    }

    fun upsert(status: DownloadJobStatus) {
        _jobs.update { current -> current.filterNot { it.id == status.id } + status }
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) {
        _jobs.update { current -> current.filterNot { it.id == id } }
    }

    fun find(id: String): DownloadJobStatus? = _jobs.value.firstOrNull { it.id == id }
}
