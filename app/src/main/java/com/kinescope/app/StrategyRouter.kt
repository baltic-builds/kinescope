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
