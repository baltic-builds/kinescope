package com.kinescope.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Patch 51: what happens when the app itself is installed or updated.
//
// - A FRESH INSTALL: the strategy check is forced. It cannot be stopped and Home says to wait for it (`MainActivity`
//   starts it, `DpiSearchService` runs it in the `initial` mode).
// - AN UPDATE: yt-dlp and the community strategy list are refreshed by themselves, in the background, and a plain notice
//   on Home recommends running the strategy check again from Settings. Nothing is forced and nothing blocks the app.
// - Otherwise nothing happens (the 25-day upkeep of patch 46 is a separate calendar).

internal enum class LaunchKind { FIRST_INSTALL, UPDATED, SAME }

internal object AppUpdate {
    /**
     * Pure rule (unit-tested). [seenVersion] is the version code this logic last saw (0 = never); [initialSearchDone]
     * is the old marker of the first-run check, which tells a build that predates this logic from a fresh install.
     */
    fun classify(seenVersion: Long, currentVersion: Long, initialSearchDone: Boolean): LaunchKind = when {
        currentVersion <= 0L -> LaunchKind.SAME
        seenVersion == currentVersion -> LaunchKind.SAME
        seenVersion == 0L && !initialSearchDone -> LaunchKind.FIRST_INSTALL
        else -> LaunchKind.UPDATED
    }

    /** Called once per start of the main process: classifies this start and remembers the version. */
    fun onProcessStart(context: Context): LaunchKind {
        val current = DpiSearchService.appVersionCode(context)
        val kind = classify(DpiPrefs.seenVersion(context), current, DpiPrefs.hasRunInitialSearch(context))
        if (current > 0L) DpiPrefs.setSeenVersion(context, current)
        UpdateNotice.load(context)
        AppLog.i("AppUpdate", "launch kind=${kind.name.lowercase()} v=$current")
        return kind
    }

    /** Blocking: refreshes the community strategy list from the repository, then raises the notice on Home. */
    fun refreshStrategyListAfterUpdate(context: Context) {
        val outcome = when (val result = DpiStrategyStore.update(context)) {
            is StrategyListUpdate.Updated -> "updated added=${result.added} total=${result.total}"
            is StrategyListUpdate.Unchanged -> "same total=${result.total}"
            is StrategyListUpdate.Failed -> "failed ${LogFormat.shortReason(result.reason)}"
        }
        AppLog.i("AppUpdate", "list $outcome")
        UpdateNotice.raise(context)
    }
}

/** The "the app was updated, re-check the strategies in Settings" notice; it survives a restart until it is dismissed. */
internal object UpdateNotice {
    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    fun load(context: Context) {
        mutablePending.value = DpiPrefs.updateNoticePending(context)
    }

    fun raise(context: Context) {
        DpiPrefs.setUpdateNoticePending(context, true)
        mutablePending.value = true
    }

    fun dismiss(context: Context) {
        DpiPrefs.setUpdateNoticePending(context, false)
        mutablePending.value = false
    }
}
