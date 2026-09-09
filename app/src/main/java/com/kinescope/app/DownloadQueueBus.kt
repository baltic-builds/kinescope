package com.kinescope.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class DownloadJobStatus(
    val id: String,
    val url: String,
    val qualityLabel: String,
    val state: JobState,
    val progressText: String,
    // ROADMAP.md Step 6.5 [fixed]: a real 0f..1f fraction for the
    // queue row's LinearProgressIndicator. Null while queued/
    // starting/done/failed -- only meaningful during RUNNING, between
    // the first progress callback tick and completion. progressText
    // stays the source of truth for the human-readable line; this is
    // purely for the progress bar.
    val progressFraction: Float? = null
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

    // ROADMAP.md Step 6.5: shared between DownloadService (where it's
    // set) and MainActivity (where it's checked, to decide whether to
    // show the connectivity-loss banner) so the two files can't drift
    // out of sync over a hand-typed string literal duplicated in both
    // places.
    const val NO_INTERNET_MESSAGE = "No internet connection"
}
