package com.kinescope.app

import android.content.Context

// Patch 51: what happens when the app itself is installed or updated. Patch 55: the same for both.
//
// - A FRESH INSTALL or AN UPDATE: yt-dlp is updated (`YtOfflineApp`), the community strategy list is refreshed (once per
//   version, by the search service just before its check) and the strategies are checked again, one connection class at a time:
//   the class the phone is on at the first launch, the other one the first time the phone is on it with the app open
//   (`ClassCheckPolicy`, `DpiPrefs.checkedVersion`). A check stops at five working strategies, three of them perfect. Only the
//   very first check of a fresh install is mandatory (it cannot be stopped and Home says to wait); after an update it is quiet.
// - Otherwise nothing happens at launch. (Patch 46's 25-day upkeep and patch 51's "re-check in Settings" notice are gone.)

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
        AppLog.i("AppUpdate", "launch kind=${kind.name.lowercase()} v=$current")
        return kind
    }

    /**
     * Blocking: refreshes the community strategy list from the repository, once for each version of the app (the first check of
     * a fresh install and the first check after an update both call it; the second finds the version recorded and does nothing).
     * A failed refresh is not recorded, so the next check tries again; the list that is already there keeps working.
     */
    fun refreshStrategyListOncePerVersion(context: Context) {
        val current = DpiSearchService.appVersionCode(context)
        if (current > 0L && DpiPrefs.listRefreshedVersion(context) == current) return
        val outcome = when (val result = DpiStrategyStore.update(context)) {
            is StrategyListUpdate.Updated -> {
                if (current > 0L) DpiPrefs.setListRefreshedVersion(context, current)
                "updated added=${result.added} total=${result.total}"
            }
            is StrategyListUpdate.Unchanged -> {
                if (current > 0L) DpiPrefs.setListRefreshedVersion(context, current)
                "same total=${result.total}"
            }
            is StrategyListUpdate.Failed -> "failed ${LogFormat.shortReason(result.reason)}"
        }
        AppLog.i("AppUpdate", "list $outcome")
    }
}
