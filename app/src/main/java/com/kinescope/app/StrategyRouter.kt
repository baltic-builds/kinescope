package com.kinescope.app

/**
 * Patch 53: the strategy router -- the policy that decides which strategies a connection class uses and in which order.
 * The full account, with the numbers, is `STRATEGY_ROUTER.md`. Pure: the preferences ([DpiPrefs]) only store what this
 * decides, and the places that act on it are the tunnel (`BypassVpnService`) and the download ladder (`RouteLadder`).
 * Unit-tested in `StrategyRouterTest`.
 *
 * One chain per connection class ([NetClass]): the strategies in the order they are tried, six at most. Four events
 * change it, and each is one function here:
 *
 * 1. A SEARCH finished on the class ([afterSearch]): the strategies that passed, plus every strategy the class has good
 *    evidence for, ordered by [StrategyRating] (the test just stored and the real use before it). A pin leads.
 * 2. A strategy CARRIED real traffic ([afterSuccess]): it leads, the others stay in rating order.
 * 3. A strategy FAILED in real use while another route finished the job ([afterFailure]): it moves down to where its
 *    lowered rating puts it.
 * 4. A class has NO chain of its own ([seed]): it starts from the other class's, an untested starting point that the
 *    first real outcomes and live checks then reorder.
 *
 * What the chain is used for, in `DpiStrategyStore.chain`: the pin first, the strategies that failed in the last ten
 * minutes last ([ChainPlanner.order]).
 */
internal object StrategyRouter {
    /** The rating as the 0.0-1.0 score [ChainPlanner] orders by; a strategy with no rating sits in the middle. */
    fun scorer(ratings: Map<String, Int>): (String) -> Double =
        { line -> (ratings[line] ?: StrategyRating.NEUTRAL) / 100.0 }

    /**
     * The chain after a search on the class. [ranked] are the strategies that fully passed it, best first; [failed] those
     * that were tested and did not; [ratings] already include this search's results. See [ChainPlanner.merge] for [proven],
     * [pinned], [keepProven] and [fill] (a stopped search keeps the old chain behind what it found).
     */
    fun afterSearch(
        ranked: List<String>,
        failed: Set<String>,
        ratings: Map<String, Int>,
        proven: String?,
        pinned: String?,
        keepProven: Boolean,
        fill: List<String>,
        max: Int
    ): List<String> = ChainPlanner.merge(
        ranked = ranked,
        failed = failed,
        proven = proven,
        pinned = pinned,
        keepProven = keepProven,
        max = max,
        fill = fill,
        learned = StrategyRating.eligible(ratings),
        score = scorer(ratings)
    )

    /** The chain after [winner] carried real traffic (its success is already in [ratings]). */
    fun afterSuccess(chain: List<String>, winner: String, ratings: Map<String, Int>, max: Int): List<String> =
        ChainPlanner.promote(chain, winner, scorer(ratings), max)

    /** The chain after [loser] failed in real use (its failure is already in [ratings]). */
    fun afterFailure(chain: List<String>, loser: String, ratings: Map<String, Int>): List<String> =
        ChainPlanner.demote(chain, loser, scorer(ratings))

    /** The chain a class starts from: its own when it has one, else the other class's first [max] strategies. */
    fun seed(own: List<String>, other: List<String>, max: Int): List<String> =
        if (own.isNotEmpty()) own else other.take(max)
}

/**
 * Patch 55: the order of ALL the strategies of one connection class, best first, and which of them are left alone for a while.
 * Pure, unit-tested in `StrategyRankTest`. The chain (six strategies) is what gets used; this is the list behind it, the one
 * a check walks down when it needs more, so each class has its own ranking and its own checks.
 *
 * The order, from the top:
 *  1. strategies the class has good evidence for (rated [StrategyRating.ELIGIBLE] or more), the best rating first;
 *  2. strategies never tested here (and not used in real traffic), in the order of the list;
 *  3. strategies that did badly (rated below [StrategyRating.ELIGIBLE]), the one that failed longest ago first, so the one that
 *     failed last sits at the VERY BOTTOM.
 *
 * A failed check therefore "moves the strategy to the very bottom": its new test time is the newest of all. The [BOTTOM_COUNT]
 * lowest-ranked strategies that did badly are FROZEN: they are not looked at again until [FREEZE_MS] after their last test
 * (75 days). A search that needs more than the rest of the list can give still reaches them, because they are last, not removed.
 */
internal object StrategyRank {
    /** How many of the lowest-ranked strategies of a class are left alone. */
    const val BOTTOM_COUNT = 15

    /** How long they are left alone, counted from their last test: 75 days. */
    const val FREEZE_MS = 75L * MaintenanceSchedule.DAY_MS

    private fun bad(line: String, ratings: Map<String, Int>): Boolean = ratings[line]?.let { it < StrategyRating.ELIGIBLE } == true

    /** [candidates] in the order described above. [ratings] and [tested] are the class's (`DpiPrefs.ratings`, `DpiResultsStore`). */
    fun order(candidates: List<String>, ratings: Map<String, Int>, tested: Map<String, StoredResult>): List<String> {
        val all = candidates.distinct()
        val good = all.filter { (ratings[it] ?: -1) >= StrategyRating.ELIGIBLE }.sortedByDescending { ratings.getValue(it) }
        val untested = all.filter { it !in ratings }
        val poor = all.filter { bad(it, ratings) }.sortedBy { tested[it]?.at ?: 0L }
        return good + untested + poor
    }

    /**
     * The strategies of [order] that are frozen at [now]: among the last [BOTTOM_COUNT] of the list, those that did badly and were
     * tested less than [FREEZE_MS] ago. One that did badly in real use only (never tested here) has no test time and is not frozen.
     */
    fun frozen(order: List<String>, ratings: Map<String, Int>, tested: Map<String, StoredResult>, now: Long): Set<String> =
        order.takeLast(BOTTOM_COUNT)
            .filter { bad(it, ratings) }
            .filter { line ->
                val at = tested[line]?.at ?: 0L
                at > 0L && (now - at) in 0L until FREEZE_MS
            }
            .toSet()
}

/**
 * Patch 55: the two-a-day random check. The first time the YouTube bypass is switched on in each half day, two strategies of the
 * connection the phone is on are picked at random and tested; what they show is recorded. Pure, unit-tested in
 * `RandomCheckPolicyTest`.
 */
internal object RandomCheckPolicy {
    /** Two checks a day: one at most every twelve hours. */
    const val INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** How many strategies one check tests. */
    const val PICKS = 2

    /** True when [lastAt] (0 = never) is at least [INTERVAL_MS] ago. A time in the future (the clock moved back) counts as old. */
    fun isDue(lastAt: Long, now: Long): Boolean = lastAt <= 0L || (now - lastAt) !in 0L until INTERVAL_MS

    /**
     * What may be picked: every strategy on offer except the frozen ones and the one carrying the tunnel (real traffic is
     * already testing that one).
     */
    fun pool(candidates: List<String>, frozen: Set<String>, active: String?): List<String> =
        candidates.distinct().filter { it !in frozen && it != active }

    fun pick(pool: List<String>, count: Int, random: kotlin.random.Random): List<String> = pool.shuffled(random).take(count)
}

/**
 * Patch 55: when the strategies of one connection class are checked because of the app itself: after an install and after an
 * update, once for each class, on the first launch (or the first time the phone is on that class). The check stops at
 * [SearchGoal.STANDARD]. Pure, unit-tested in `ClassCheckPolicyTest`.
 */
internal object ClassCheckPolicy {
    /** After a check that did not finish (stopped, no network) the next one waits at least this long. */
    const val RETRY_GAP_MS = 6L * 60L * 60L * 1000L

    /**
     * [checkedVersion] is the app version under which this class was last checked (0 = never), [currentVersion] the one running
     * now, [tryAt] when a check of this class was last started (0 = never).
     */
    fun needed(checkedVersion: Long, currentVersion: Long, tryAt: Long, now: Long): Boolean =
        currentVersion > 0L &&
            checkedVersion != currentVersion &&
            (tryAt <= 0L || (now - tryAt) !in 0L until RETRY_GAP_MS)
}
