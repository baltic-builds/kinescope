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
    val randomResult: RandomReport? = null
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

    internal fun starting(background: Boolean = false, mandatory: Boolean = false, random: Boolean = false) {
        mutableState.value = DpiSearchUiState(running = true, background = background, mandatory = mandatory, random = random)
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
                    val count = intent?.getIntExtra(EXTRA_COUNT, RandomCheckPolicy.MANUAL_PICKS) ?: RandomCheckPolicy.MANUAL_PICKS
                    val force = intent?.getBooleanExtra(EXTRA_FORCE, true) ?: true
                    stopRequested.set(false)
                    mandatoryRun = mode == MODE_INITIAL
                    DpiSearchController.starting(
                        background = mode == MODE_CHECK,
                        mandatory = mandatoryRun,
                        random = mode == MODE_RANDOM
                    )
                    // Patch 47: when the system refuses the foreground start (Android 12+ restricts it), nothing would
                    // ever end the "running" state: Home would show a re-check forever, downloads would wait for a
                    // search that never runs and no later search could start. Give the state back and stop instead.
                    val foreground = runCatching { startForegroundCompat(buildNotification(null)) }
                        .onFailure { AppLog.e("DpiSearchService", "Could not enter the foreground", it) }
                        .isSuccess
                    if (foreground) {
                        executor.execute { if (mode == MODE_RANDOM) runRandom(count, force) else runSearch(mode, only) }
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
     * Patch 55: a search stops at [SearchGoal.STANDARD] (five working strategies, three of them perfect) instead of scanning the list.
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
            // Patch 55: the first launch and the first launch after an update look at the current community list.
            if (!testOnly && (background || mode == MODE_INITIAL)) AppUpdate.refreshStrategyListOncePerVersion(context)
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
                // Patch 55: a check ends when it has found five working strategies, three of them perfect (a strategy test: its one line).
                goal = if (testOnly) null else SearchGoal.STANDARD,
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
     * Patch 56: a random check of the connection the phone is on: [count] ways picked at random and tested ([RandomCheck.run]). The
     * refresh button of Home asks for five and always runs ([force]); the app opening asks for two and only when the twelve-hour
     * calendar says it is due. A check that was started and is not due after all (another trigger ran first) leaves no trace.
     */
    private fun runRandom(count: Int, force: Boolean) {
        val context = applicationContext
        try {
            val cls = NetworkState.currentClass(context)
            if (!force && !RandomCheck.dueNow(context, cls)) {
                DpiSearchController.idle()
                return
            }
            val report = RandomCheck.run(
                context,
                cls,
                active = BypassVpnController.activeLine.value,
                count = count,
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
            AppLog.e("DpiSearchService", "Random check failed", e)
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
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_FORCE = "force"
        private const val MODE_MANUAL = "manual"
        private const val MODE_CHECK = "check"
        private const val MODE_TEST = "test"
        private const val MODE_INITIAL = "initial"
        private const val MODE_RANDOM = "random"

        fun start(context: Context) = dispatch(context, MODE_MANUAL, null)

        /** Patch 51: the mandatory first-run check of a fresh install; it cannot be stopped. */
        fun startInitial(context: Context) = dispatch(context, MODE_INITIAL, null)

        /**
         * Patch 55: the quiet check of the connection the phone is on, for a version of the app that has not checked it yet
         * (`ClassCheckPolicy`); the stored and learned strategies keep working meanwhile. (Patch 35's quiet full re-check, run by
         * patch 46's 25-day upkeep, is gone: nothing scans the whole list any more.)
         */
        fun startCheck(context: Context) = dispatch(context, MODE_CHECK, null)

        /** Patch 35: tests one strategy from Settings; the chain is not changed. */
        fun startTest(context: Context, line: String) = dispatch(context, MODE_TEST, listOf(line))

        /**
         * Patch 56: a random check of the connection the phone is on. [count] ways are picked at random and tested; the working
         * ones join the router's list by their rating, the others leave it. [force] false asks only for a check that is due
         * (`RandomCheck.dueNow`): the app opening uses it, the refresh button of Home does not.
         */
        fun startRandom(context: Context, count: Int = RandomCheckPolicy.MANUAL_PICKS, force: Boolean = true) =
            dispatch(context, MODE_RANDOM, null, count, force)

        private fun dispatch(
            context: Context,
            mode: String,
            only: List<String>?,
            count: Int = RandomCheckPolicy.MANUAL_PICKS,
            force: Boolean = true
        ) {
            val intent = Intent(context, DpiSearchService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                if (only != null) putExtra(EXTRA_ONLY, only.toTypedArray())
                putExtra(EXTRA_COUNT, count)
                putExtra(EXTRA_FORCE, force)
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
 * Patch 55: the two-a-day random check. The first time the YouTube bypass is switched on in each half day (twelve hours apart,
 * counted per connection class, see [RandomCheckPolicy]) two strategies of the connection the phone is on are picked at random and
 * tested, and what they show is recorded: a strategy that works joins the chain by its rating, one that does not is taken out of
 * the chain and, its failed test being the newest on record, sits at the very bottom of the class's ranking ([StrategyRank]),
 * where the lowest [StrategyRank.BOTTOM_COUNT] are left alone for [StrategyRank.FREEZE_MS] (75 days). It never tests the
 * strategy carrying the tunnel.
 *
 * Patch 56: the same routine ([run]) now has three triggers, because a check that runs unseen from one place looked like no check
 * at all: the tunnel coming up and, every ten minutes while it runs, the calendar (`BypassVpnService`), the app being opened
 * (`MainActivity`, through [DpiSearchService]), and the refresh button of Home, which tests five ways whenever it is pressed.
 * Every run leaves its outcome for the groups card ([RandomLast]) and a `rand` line in the log.
 */
internal object RandomCheck {
    const val DONE = "done"
    const val VOID = "void"
    const val NO_ROUTE = "no-route"
    const val NET_CHANGED = "net-changed"
    const val NOTHING = "nothing-to-test"

    /** Whether a check of [cls] is due by the calendar and has anything to build on (the first check of the install is done). */
    fun dueNow(context: Context, cls: NetClass, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        return DpiPrefs.hasRunInitialSearch(app) && RandomCheckPolicy.isDue(DpiPrefs.randomCheckAt(app, cls), now)
    }

    /**
     * Runs one random check of [cls] if one is due; for the tunnel's own thread. Blocking; returns a short word for what happened,
     * for the log and the tests. It gives way at once to anything else that needs the engine (a search, a download).
     */
    fun runIfDue(
        context: Context,
        cls: NetClass,
        active: String?,
        random: kotlin.random.Random = kotlin.random.Random.Default,
        isCancelled: () -> Boolean
    ): String {
        val app = context.applicationContext
        if (!DpiPrefs.hasRunInitialSearch(app)) return "first-check-pending"
        if (!dueNow(app, cls)) return "not-due"
        if (busy() || DpiEngine.regularBusy()) return "busy"
        return run(app, cls, active, RandomCheckPolicy.PICKS, random, { isCancelled() || busy() }, {}, {}).status
    }

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
        onProgress: (SearchProgress) -> Unit
    ): RandomReport {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val stored = DpiResultsStore.load(app, cls)
        val ratings = DpiPrefs.ratings(app, cls, now)
        val order = StrategyRank.order(DpiStrategyStore.candidates(app), ratings, stored)
        val frozen = StrategyRank.frozen(order, ratings, stored, now)
        val picks = RandomCheckPolicy.pick(RandomCheckPolicy.pool(order, frozen, active), count, random)
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
            waitIfPaused = waitIfPaused
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
        val broken = usable.filter { !it.works }.map { it.line }
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
