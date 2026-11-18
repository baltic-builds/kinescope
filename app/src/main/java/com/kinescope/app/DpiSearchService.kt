package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DpiSearchUiState(
    val running: Boolean = false,
    val progress: SearchProgress? = null,
    @StringRes val resultMessageRes: Int? = null,
    val resultDirectWorks: Boolean = false,
    /** Patch 55: the quiet check of one connection after an install or an update; downloads keep using the stored strategies. */
    val background: Boolean = false,
    /** Patch 51: the first-run check of a fresh install: it cannot be stopped and Home says to wait for it. */
    val mandatory: Boolean = false,
    /** Patch 56: a random check (the refresh button, the twice-a-day check): a few ways picked at random, quiet like [background]. */
    val random: Boolean = false,
    /** Patch 56: what the last random check found; set when it ends, shown on the dashboard until the next search starts. */
    val randomResult: RandomReport? = null,
    /** Patch 62: the full scan of Settings: every strategy is tested, there is no goal to reach. */
    val full: Boolean = false
)

/**
 * Holds the strategy search's state outside any screen's lifecycle, so the search itself (run by
 * [DpiSearchService]) survives navigating away from Settings or backgrounding the app -- before
 * this it ran in the Settings screen's own coroutine scope and was cancelled the moment that
 * screen left composition (see CHANGELOG.md, patch 30).
 */
internal object DpiSearchController {
    private val mutableState = MutableStateFlow(DpiSearchUiState())
    val state = mutableState.asStateFlow()

    internal fun starting(background: Boolean = false, mandatory: Boolean = false, random: Boolean = false, full: Boolean = false) {
        mutableState.value = DpiSearchUiState(running = true, background = background, mandatory = mandatory, random = random, full = full)
    }

    /** Patch 56: a start that found nothing to do (a check that was not due after all) leaves no message behind. */
    internal fun idle() {
        mutableState.value = DpiSearchUiState()
    }

    // Patch 35: a running download needs the engine, so a search steps aside between two strategies.
    private val downloadHolds = java.util.concurrent.atomic.AtomicInteger(0)

    fun holdForDownload() {
        downloadHolds.incrementAndGet()
    }

    fun releaseDownload() {
        downloadHolds.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun isHeld(): Boolean = downloadHolds.get() > 0

    /** Patch 64: the forced refresh gave up on a download that did not stop: its hold must not keep every search waiting for ever. */
    fun clearHolds() {
        downloadHolds.set(0)
    }

    internal fun progress(progress: SearchProgress) {
        mutableState.value = mutableState.value.copy(running = true, progress = progress)
    }

    internal fun finished(@StringRes messageRes: Int, directWorks: Boolean = false, randomResult: RandomReport? = null) {
        mutableState.value = DpiSearchUiState(
            resultMessageRes = messageRes,
            resultDirectWorks = directWorks,
            randomResult = randomResult
        )
    }
}

/**
 * Runs the strategy search as a foreground service so it keeps testing every remaining candidate
 * when the user leaves Settings or backgrounds the app entirely, instead of the search being tied
 * to that screen's own composition.
 */
class DpiSearchService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinescope-strategy-search").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)

    // Patch 51: set while the first-run check runs: Stop is ignored and the notification has no Stop button.
    @Volatile
    private var mandatoryRun = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Patch 51: the first-run check is mandatory; a stop request (a stale notification, an old intent) is ignored.
            ACTION_STOP -> if (!DpiSearchController.state.value.mandatory) stopRequested.set(true)
            ACTION_START -> {
                if (!DpiSearchController.state.value.running) {
                    val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_MANUAL
                    val only = intent?.getStringArrayExtra(EXTRA_ONLY)?.toList()
                    stopRequested.set(false)
                    // Patch 63: nothing is mandatory any more (the first check is a question, see FirstCheckPrompt.kt); the flag is inert.
                    mandatoryRun = false
                    DpiSearchController.starting(
                        background = mode == MODE_CHECK,
                        mandatory = mandatoryRun,
                        random = mode == MODE_REFRESH || mode == MODE_UPDATE,
                        full = mode == MODE_FULL
                    )
                    // Patch 47: when the system refuses the foreground start (Android 12+ restricts it), nothing would
                    // ever end the "running" state: Home would show a re-check forever, downloads would wait for a
                    // search that never runs and no later search could start. Give the state back and stop instead.
                    val foreground = runCatching { startForegroundCompat(buildNotification(null)) }
                        .onFailure { AppLog.e("DpiSearchService", "Could not enter the foreground", it) }
                        .isSuccess
                    if (foreground) {
                        executor.execute {
                            if (mode == MODE_REFRESH || mode == MODE_UPDATE) runRefresh(updateFirst = mode == MODE_UPDATE) else runSearch(mode, only)
                        }
                    } else {
                        DpiSearchController.finished(R.string.bypass_operation_failed)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Patch 35: one strategy search. [mode] is manual, initial (the first check of a fresh install), check (patch 55: the quiet
     * check of one connection after an install or an update; patch 46's 25-day upkeep is gone) or test (one strategy, [only]).
     * Patch 55: a search stops at its goal instead of scanning the list. Patch 62: the goal is [SearchGoal.GOOD] (three good strategies); the
     * full mode of Settings -> Test strategies has none and tests every strategy of the list.
     */
    private fun runSearch(mode: String, only: List<String>?) {
        val context = applicationContext
        val testOnly = only != null
        val background = mode == MODE_CHECK
        try {
            // Patch 53: a search describes the connection it ran on. The class is read once, here, and the results are
            // stored for it only if the connection is still of that class when the search ends.
            val cls = NetworkState.currentClass(context)
            // Patch 55: note the attempt first, so a check that is stopped or fails is not restarted at once (ClassCheckPolicy).
            if (background) DpiPrefs.markCheckTried(context, cls)
            // Patch 64: the full scan of Settings looks at the current community list first (it is due once a month, and after an update).
            if (!testOnly) AppUpdate.refreshStrategyListIfDue(context)
            val directWorks = DpiBypass.directConnectionWorks()
            // Patch 54: before testing a list of strategies, find out whether the engine can reach the hosts on this connection at
            // all. If it cannot, nothing a strategy does helps: say so at once instead of scanning the whole list for nothing and
            // storing "failed" for every strategy. (A first check that could not run stays pending: it is retried on the next launch.)
            val probeLine = only?.firstOrNull()
                ?: (DpiStrategyStore.verifiedChain(context, cls) + DpiStrategyStore.candidates(context)).firstOrNull()
            if (probeLine != null && DpiBypass.reach(context, cls, probeLine) { stopRequested.get() } == Reach.NO_ROUTE) {
                DpiSearchController.finished(R.string.bypass_search_no_route)
                return
            }
            val results = DpiBypass.search(
                context,
                cls,
                // Patch 62: a check ends when it has found three good strategies (real traffic, a score of 80 or more). The full scan of
                // Settings and a strategy test (its one line) have no goal: they test everything they were given.
                goal = if (testOnly || mode == MODE_FULL) null else SearchGoal.GOOD,
                only = only,
                isCancelled = { stopRequested.get() },
                waitIfPaused = { awaitDownloadsIdle() },
                onProgress = { snapshot ->
                    DpiSearchController.progress(snapshot)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                }
            )
            if (!DpiBypass.recordSearch(context, cls, results)) {
                DpiSearchController.finished(R.string.bypass_search_network_changed)
                return
            }
            when {
                testOnly -> DpiSearchController.finished(R.string.bypass_strategy_tested)
                stopRequested.get() -> {
                    // Patch 35: a stopped search used to throw away everything it had found. Keep the
                    // passes (the old chain stays behind them), as long as something really carried traffic.
                    val kept = results.any { it.fullPass && it.deepOk } &&
                        DpiBypass.applySearchResults(context, cls, results, keepProven = background, partial = true)
                    if (kept && !background) DpiPrefs.setEnabled(context, true)
                    DpiSearchController.finished(if (kept) R.string.bypass_search_partial else R.string.bypass_search_stopped)
                }
                else -> {
                    // A finished (not stopped) search counts as the one-time first check.
                    DpiPrefs.setHasRunInitialSearch(context, true)
                    DpiPrefs.setLastSearchVersion(context, appVersionCode(context))
                    DpiPrefs.setLastSearchAt(context, System.currentTimeMillis())
                    // Patch 55: this connection is checked for the running version, whether the goal was reached or the list ran out.
                    DpiPrefs.setCheckedVersion(context, cls, appVersionCode(context))
                    if (DpiBypass.applySearchResults(context, cls, results, keepProven = background)) {
                        // A quiet re-check must not switch a bypass the user turned off back on.
                        if (!background) DpiPrefs.setEnabled(context, true)
                        DpiSearchController.finished(
                            if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found,
                            directWorks
                        )
                    } else {
                        DpiSearchController.finished(R.string.bypass_search_none)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("DpiSearchService", "Strategy search failed", e)
            DpiSearchController.finished(R.string.bypass_operation_failed)
        } finally {
            stopSelf()
        }
    }

    /**
     * Patch 64: the refresh of the connection the phone is on. Every way is a candidate, taken in random order ([RandomCheck.run]), and the
     * check goes on until three of them are confirmed at 85 or more ([SearchGoal.REFRESH]) or the list is used up (the strategy in use and
     * the frozen ones are never tested). The Home button and Settings' forced refresh use it as it is. With [updateFirst] (the automatic
     * update of `LaunchUpdate`) the community list and yt-dlp are refreshed first, and the network is remembered once the refresh finished.
     */
    private fun runRefresh(updateFirst: Boolean) {
        val context = applicationContext
        try {
            val cls = NetworkState.currentClass(context)
            val networkKey = if (updateFirst) NetworkIdentity.key(context) else null
            if (updateFirst) {
                LaunchUpdate.markAttempt(context)
                AppUpdate.refreshEverything(context)
            }
            val report = RandomCheck.run(
                context,
                cls,
                active = BypassVpnController.activeLine.value,
                count = Int.MAX_VALUE,
                goal = SearchGoal.REFRESH,
                isCancelled = { stopRequested.get() },
                waitIfPaused = { awaitDownloadsIdle() },
                onProgress = { snapshot ->
                    DpiSearchController.progress(snapshot)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                }
            )
            // Patch 64: the update counts as done when it was not stopped and the engine could reach the hosts at all; a network where nothing
            // could be tested is tried again at the next launch (after the gap of `LaunchUpdatePolicy`).
            if (networkKey != null && !stopRequested.get() && report.status != RandomCheck.NO_ROUTE && report.status != RandomCheck.NET_CHANGED) {
                LaunchUpdate.markDone(context, networkKey)
            }
            DpiSearchController.finished(
                when {
                    stopRequested.get() && report.tested == 0 -> R.string.bypass_search_stopped
                    report.tested > 0 -> R.string.dash_refresh_done
                    report.status == RandomCheck.NO_ROUTE -> R.string.bypass_search_no_route
                    else -> R.string.dash_refresh_nothing
                },
                randomResult = report
            )
        } catch (e: Exception) {
            AppLog.e("DpiSearchService", "Refresh failed", e)
            DpiSearchController.finished(R.string.bypass_operation_failed)
        } finally {
            stopSelf()
        }
    }

    private fun awaitDownloadsIdle() {
        while (DpiSearchController.isHeld() && !stopRequested.get()) Thread.sleep(300L)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: SearchProgress?): Notification {
        val stopIntent = Intent(this, DpiSearchService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (progress == null) {
            getString(R.string.bypass_search_notification_starting)
        } else {
            getString(
                R.string.bypass_find_progress,
                (progress.index + 1).coerceAtMost(progress.total.coerceAtLeast(1)),
                progress.total
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kinescope)
            .setContentTitle(getString(R.string.bypass_search_notification_title))
            .setContentText(text)
            .setContentIntent(AppIntents.pendingOpenHome(this))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        // Patch 51: the first-run check cannot be stopped, so its notification offers no Stop.
        if (!mandatoryRun) builder.addAction(0, getString(R.string.stop), stopPendingIntent)
        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bypass_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val ACTION_START = "com.kinescope.app.ACTION_START_STRATEGY_SEARCH"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_STRATEGY_SEARCH"
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1102

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ONLY = "only"
        private const val MODE_MANUAL = "manual"
        private const val MODE_CHECK = "check"
        private const val MODE_TEST = "test"
        private const val MODE_REFRESH = "refresh"
        private const val MODE_UPDATE = "update"
        private const val MODE_FULL = "full"

        /** The check of Home: it ends at three good strategies ([SearchGoal.GOOD]). */
        fun start(context: Context) = dispatch(context, MODE_MANUAL, null)

        /** Patch 62: Settings -> Test strategies: every strategy of the list is tested, nothing stops it early but the Stop button. */
        fun startFull(context: Context) = dispatch(context, MODE_FULL, null)

        /**
         * Patch 64: the automatic update of `LaunchUpdate` (first launch, first launch on mobile data, a new Wi-Fi network): the list and
         * yt-dlp are refreshed, then the strategies, quietly; the stored and learned strategies keep working meanwhile.
         */
        fun startUpdate(context: Context) = dispatch(context, MODE_UPDATE, null)

        /** Patch 35: tests one strategy from Settings; the chain is not changed. */
        fun startTest(context: Context, line: String) = dispatch(context, MODE_TEST, listOf(line))

        /**
         * Patch 64 (patch 56's random check, grown): tests the ways of the connection the phone is on, in random order, until three are
         * confirmed at 85 or more or the list is used up; what works joins the router's list by its rating, what does not leaves it.
         */
        fun startRefresh(context: Context) = dispatch(context, MODE_REFRESH, null)

        private fun dispatch(context: Context, mode: String, only: List<String>?) {
            val intent = Intent(context, DpiSearchService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                if (only != null) putExtra(EXTRA_ONLY, only.toTypedArray())
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch start", it) }
        }

        fun appVersionCode(context: Context): Long = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

        fun stop(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch stop", it) }
        }
    }
}


/**
 * What one random check found: [tested] ways really ran (a way the engine could not even connect with is not counted), [works] of
 * them carried real traffic, [added] of those were not in the router's list before and are now, [removed] were in it and left it
 * because they did not work. [status] is a short word for the log (`done`, `void`, `no-route`, `net-changed`, `nothing-to-test`).
 */
internal data class RandomReport(
    val status: String,
    val tested: Int,
    val works: Int,
    val added: Int,
    val removed: Int,
    val at: Long
)

/**
 * Patch 55: tests some strategies of the connection the phone is on, picked at random, and records what they show: a strategy that works
 * joins the chain by its rating, one that does not is taken out of the chain and, its failed test being the newest on record, sits at
 * the very bottom of the class's ranking ([StrategyRank]), where the lowest [StrategyRank.BOTTOM_COUNT] are left alone for
 * [StrategyRank.FREEZE_MS] (75 days). It never tests the strategy carrying the tunnel.
 *
 * Patch 64: the only callers are now the refresh (the Home button, `ForceRefresh`) and the automatic update of `LaunchUpdate`, both through
 * [DpiSearchService.startRefresh] / [DpiSearchService.startUpdate] with every way as a candidate and [SearchGoal.REFRESH] as the goal. The
 * twice-a-day calendar and the tunnel's own trigger are gone; every run leaves its outcome for the groups card ([RandomLast]).
 */
internal object RandomCheck {
    const val DONE = "done"
    const val VOID = "void"
    const val NO_ROUTE = "no-route"
    const val NET_CHANGED = "net-changed"
    const val NOTHING = "nothing-to-test"

    /**
     * Picks [count] ways of [cls] at random (never a frozen one, never [active]) and tests them. Blocking. [waitIfPaused] is called
     * before each way (a download that needs the engine makes the check wait); [onProgress] sees each step. The attempt is stamped on
     * the calendar first, so a check that is cut short is not started again at once, and the outcome is stored whatever the trigger.
     */
    fun run(
        context: Context,
        cls: NetClass,
        active: String?,
        count: Int,
        random: kotlin.random.Random = kotlin.random.Random.Default,
        isCancelled: () -> Boolean,
        waitIfPaused: () -> Unit,
        onProgress: (SearchProgress) -> Unit,
        // Patch 64: with a goal the check stops as soon as it is reached (the refresh: three ways at 85 or more); without one it
        // tests every way it picked.
        goal: SearchGoal? = null
    ): RandomReport {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val stored = DpiResultsStore.load(app, cls)
        val ratings = DpiPrefs.ratings(app, cls, now)
        val order = StrategyRank.order(DpiStrategyStore.candidates(app), ratings, stored, StrategyPriority.forClass(cls))
        val frozen = StrategyRank.frozen(order, ratings, stored, now)
        // Patch 61: the strategy that last carried real traffic is never picked: a synthetic probe it fails (the app does not care about
        // it) used to take it out of the chain, and the chain was then re-ordered by ratings alone.
        val proven = DpiPrefs.provenLine(app, cls, now)
        val picks = RandomCheckPolicy.pick(RandomCheckPolicy.pool(order, frozen, active, proven), count, random)
        if (picks.isEmpty()) return RandomReport(NOTHING, 0, 0, 0, 0, now)
        DpiPrefs.setRandomCheckAt(app, cls, now)
        val startedAt = System.nanoTime()

        // The engine must reach the hosts at all, or the result says nothing about any strategy.
        if (DpiBypass.reach(app, cls, picks.first(), isCancelled) == Reach.NO_ROUTE) {
            AppLog.i("DpiSearch", "rand net=${cls.key} n=${picks.size} void=no-route")
            return RandomReport(NO_ROUTE, 0, 0, 0, 0, now)
        }
        val chainBefore = DpiStrategyStore.verifiedChain(app, cls)
        val results = DpiBypass.search(
            app,
            cls,
            isCancelled = isCancelled,
            onProgress = onProgress,
            only = picks,
            waitIfPaused = waitIfPaused,
            goal = goal
        )
        val ms = (System.nanoTime() - startedAt) / 1_000_000L
        val usable = DpiBypass.withoutReachFaults(results).filter { it.started }
        AppLog.i(
            "DpiSearch",
            "rand net=${cls.key} n=${picks.size} tested=${results.count { it.started }} works=${results.count { it.works }} " +
                "fault=${results.count { it.connectFault }} ms=$ms"
        )
        // Nothing started (the engine could not be bound) or nothing reached the hosts: nothing learned.
        if (usable.isEmpty()) return RandomReport(VOID, 0, 0, 0, 0, now)
        if (!DpiBypass.recordSearch(app, cls, results)) return RandomReport(NET_CHANGED, 0, 0, 0, 0, now)
        // What works joins the chain by its rating; what does not leaves it (its failed test is now the newest on record). A strategy
        // that reached the hosts but moved no data does not work: it neither joins nor stays.
        val candidatesForChain = results.filter { it.works || !it.fullPass }
        if (usable.any { it.works }) DpiBypass.applySearchResults(app, cls, candidatesForChain, keepProven = true, partial = true)
        val broken = usable.filter { !it.works }.map { it.line }.filter { it != proven }
        DpiPrefs.removeFromChain(app, cls, broken)
        DpiPrefs.reorderByRating(app, cls)
        val chainAfter = DpiStrategyStore.verifiedChain(app, cls)
        val worked = usable.filter { it.works }.map { it.line }
        val report = RandomReport(
            status = DONE,
            tested = usable.size,
            works = worked.size,
            added = RandomCheckPolicy.added(chainBefore, chainAfter, worked),
            removed = broken.count { it in chainBefore && it !in chainAfter },
            at = now
        )
        DpiPrefs.setRandomLast(app, cls, RandomLast(now, report.tested, report.works))
        AppLog.i(
            "DpiSearch",
            "rand net=${cls.key} kept works=${report.works}/${report.tested} added=${report.added} removed=${report.removed} chain=${chainAfter.size}"
        )
        return report
    }

    private fun busy(): Boolean = DpiSearchController.state.value.running || DpiSearchController.isHeld()
}
