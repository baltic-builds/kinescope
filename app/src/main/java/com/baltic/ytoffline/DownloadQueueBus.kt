package com.baltic.ytoffline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    // ROADMAP.md Step 4 [MEDIUM, fixed]: upsert() runs from the
    // main/binder thread (enqueue) and update() runs from the worker
    // thread (progress ticks) -- both used to do a plain
    // read-`_jobs.value`-then-write, which genuinely races between
    // those two threads. `MutableStateFlow.update {}` is an atomic
    // compare-and-set retry loop instead.
    fun upsert(status: DownloadJobStatus) {
        _jobs.update { current -> current.filterNot { it.id == status.id } + status }
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }
}
