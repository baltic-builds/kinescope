package com.kinescope.app

/**
 * Patch 56: what the Home screen says about each strategy group (Wi-Fi, mobile data). Patches 53 and 55 kept everything per
 * connection class but showed none of it: the only trace on Home was a small label under the automatic switch, so a reader could
 * not tell that two lists exist, how many ways each holds, or when it was last looked at. Pure, unit-tested in
 * `StrategyGroupsTest`; `StrategyGroupsCard.kt` reads the preferences and draws what this decides.
 */

/** The outcome of the last random check of one group: when it ended, how many ways it tested and how many of them work. */
internal data class RandomLast(val at: Long, val tested: Int, val works: Int) {
    /** `at,tested,works`: the form it is stored in (`DpiPrefs.setRandomLast`). */
    fun serialize(): String = "$at,$tested,$works"

    companion object {
        /** The stored form back; null for anything that is not three non-negative numbers with a positive time. */
        fun parse(text: String?): RandomLast? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 3) return null
            val at = parts[0].trim().toLongOrNull() ?: return null
            val tested = parts[1].trim().toIntOrNull() ?: return null
            val works = parts[2].trim().toIntOrNull() ?: return null
            if (at <= 0L || tested < 0 || works < 0 || works > tested) return null
            return RandomLast(at, tested, works)
        }
    }
}

/** What a group holds, in one word: nothing yet, a list borrowed from the other group, or a list tested on this connection. */
internal enum class GroupStatus { NOT_CHECKED, BORROWED, READY }

/** Everything the card shows about one group. [inRouter] is the size of the chain, [known] the ways rated at least `StrategyRating.ELIGIBLE`. */
internal data class GroupState(
    val cls: NetClass,
    /** The phone is on this connection now. */
    val current: Boolean,
    val inRouter: Int,
    val known: Int,
    val seeded: Boolean,
    /** When a search last set this group's chain (0 = never, or the chain is borrowed). */
    val checkedAt: Long,
    val random: RandomLast?
) {
    val status: GroupStatus
        get() = when {
            inRouter == 0 -> GroupStatus.NOT_CHECKED
            seeded -> GroupStatus.BORROWED
            else -> GroupStatus.READY
        }
}

/** The groups in the order the card lists them: the connection the phone is on first, the other one after it. */
internal fun groupOrder(current: NetClass): List<NetClass> = listOf(current, current.other)
