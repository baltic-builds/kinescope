package com.kinescope.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState { QUEUED, RUNNING, PAUSED, DONE, FAILED, STOPPED }
enum class FailureKind { NO_INTERNET, YOUTUBE_VERIFICATION, OTHER }

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
 * In-process shared state between DownloadService (producer) and the
 * UI (consumer). The app is deliberately single-process, so a small
 * StateFlow remains the simplest coordination layer.
 */
object DownloadQueueBus {
    private val _jobs = MutableStateFlow<List<DownloadJobStatus>>(emptyList())
    val jobs = _jobs.asStateFlow()

    fun upsert(status: DownloadJobStatus) {
        _jobs.update { current -> current.filterNot { it.id == status.id } + status }
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    fun find(id: String): DownloadJobStatus? = _jobs.value.firstOrNull { it.id == id }
}
