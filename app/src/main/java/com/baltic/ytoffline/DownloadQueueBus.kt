package com.baltic.ytoffline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class DownloadJobStatus(
    val id: String,
    val url: String,
    val qualityLabel: String,
    val state: JobState,
    val progressText: String
)

/**
 * In-process shared state between DownloadService (producer) and the
 * UI (consumer). No IPC/binding needed since both run in the same
 * process for this app — deliberately the simplest thing that works,
 * per CLAUDE.md.
 */
object DownloadQueueBus {
    private val _jobs = MutableStateFlow<List<DownloadJobStatus>>(emptyList())
    val jobs = _jobs.asStateFlow()

    fun upsert(status: DownloadJobStatus) {
        _jobs.value = _jobs.value.filterNot { it.id == status.id } + status
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }
}
