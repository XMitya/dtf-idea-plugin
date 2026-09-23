package com.xmitya.ideadtf.flow.layout

/**
 * Where, inside the gap between two ranks, each arrow turns.
 *
 * A square arrow from one rank to the next runs along, turns across, and runs along again. With a
 * single turning line per gap - its midpoint - every arrow crossing that gap shares it, and a column
 * of callers fanning into two tasks becomes one vertical bar nobody can read back into who calls
 * what. So each arrow is given a track of its own, and a track is only shared where sharing says
 * something true: arrows into the same box, or out of the same box.
 *
 * Pure and in rank space: "along" is the direction the flow reads, "across" the other one. Which of
 * the two is x is the layouter's business, and crossings do not change when a picture is mirrored.
 */
internal object DtfFlowChannels {

    /** One arrow's step across one gap: [from] sits in the rank before it, [to] in the rank after. */
    data class Hop(val from: String, val to: String, val reversed: Boolean)

    /**
     * The tracks of one gap.
     *
     * @param tracks how many distinct turning lines the gap needs; zero when every arrow crosses it
     *   straight.
     */
    class Gap(val tracks: Int, private val slots: Map<Hop, Int>) {
        /** Zero is the track nearest the rank the arrows come from. Null for an arrow that goes straight. */
        fun slotOf(hop: Hop): Int? = slots[hop]

        companion object {
            val EMPTY = Gap(0, emptyMap())
        }
    }

    /**
     * Arrows that turn on one track together.
     *
     * [entries] are where they come in from the rank before, [exits] where they leave for the rank
     * after; the track's own run spans [lo] to [hi].
     */
    private class Bundle(val key: String, val hops: List<Hop>, val entries: Set<Int>, val exits: Set<Int>) {
        val lo: Int = minOf(entries.min(), exits.min())
        val hi: Int = maxOf(entries.max(), exits.max())
    }

    /**
     * @param acrossOf the centre, across the flow, of a box or waypoint in either rank.
     * @param clearance how close two tracks' runs may come before they must not share a line.
     */
    fun gapOf(hops: Collection<Hop>, acrossOf: (String) -> Int, clearance: Int): Gap {
        val bent = hops.filter { acrossOf(it.from) != acrossOf(it.to) }
        if (bent.isEmpty()) return Gap.EMPTY

        val order = ordered(bundlesOf(bent, acrossOf))
        val bundleSlots = IntArray(order.size)
        val slots = HashMap<Hop, Int>()
        order.forEachIndexed { index, bundle ->
            // Runs that never come near each other can share a line: a tall column of callers, each
            // fanning into its own task, then needs a track or two rather than one per task.
            val slot = (0 until index).filter { overlaps(order[it], bundle, clearance) }.maxOfOrNull { bundleSlots[it] + 1 } ?: 0
            bundleSlots[index] = slot
            bundle.hops.forEach { slots[it] = slot }
        }
        return Gap(bundleSlots.max() + 1, slots)
    }

    /**
     * Arrows into one box first, then whatever is left grouped by the box it leaves.
     *
     * A bus therefore always has a single end - one target or one source - which is what keeps a
     * shared line from reading as "each of these goes to each of those". A reversed arrow never
     * joins a forward bus, or a loop back would read as a second arrow in.
     */
    private fun bundlesOf(hops: List<Hop>, acrossOf: (String) -> Int): List<Bundle> {
        val bundles = mutableListOf<Bundle>()
        val loose = mutableListOf<Hop>()
        hops.groupBy { it.to to it.reversed }.forEach { (target, group) ->
            if (group.size > 1) bundles += bundle("<-${target.first}:${target.second}", group, acrossOf) else loose += group
        }
        loose.groupBy { it.from to it.reversed }.forEach { (source, group) ->
            bundles += bundle("->${source.first}:${source.second}", group, acrossOf)
        }
        return bundles
    }

    private fun bundle(key: String, hops: List<Hop>, acrossOf: (String) -> Int): Bundle =
        Bundle(key, hops, hops.mapTo(LinkedHashSet()) { acrossOf(it.from) }, hops.mapTo(LinkedHashSet()) { acrossOf(it.to) })

    /**
     * Nearest track first, ordered so that as few runs cross as possible.
     *
     * Greedy, after Eades, Lin and Smyth: repeatedly take the bundle that costs least to put before
     * everything still left. Exact ordering is a feedback arc set, which is not worth solving for a
     * picture - this gets the common shapes right and is deterministic.
     */
    private fun ordered(bundles: List<Bundle>): List<Bundle> {
        val initial = bundles.sortedWith(compareBy({ it.lo }, { it.hi }, { it.key }))
        if (initial.size > MAX_ORDERED_BUNDLES) return initial

        val cost = Array(initial.size) { g -> IntArray(initial.size) { h -> if (g == h) 0 else crossings(initial[g], initial[h]) } }
        val score = IntArray(initial.size) { g -> initial.indices.sumOf { h -> cost[g][h] - cost[h][g] } }
        val taken = BooleanArray(initial.size)
        val result = ArrayList<Bundle>(initial.size)
        repeat(initial.size) {
            // Ties go to the earlier one in the initial order, which is what keeps this deterministic.
            val pick = initial.indices.filter { !taken[it] }.minBy { score[it] }
            taken[pick] = true
            result += initial[pick]
            initial.indices.filter { !taken[it] }.forEach { h -> score[h] -= cost[h][pick] - cost[pick][h] }
        }
        return result
    }

    /**
     * How many runs cross when [first]'s track is nearer the rank the arrows come from than
     * [second]'s.
     *
     * Only two kinds of run can: [first]'s exits, on their way past [second]'s track to the next
     * rank, and [second]'s entries, on their way past [first]'s. Closed at the ends, because a run
     * that stops exactly on another's corner overlaps it for the width of the gap between the two.
     */
    private fun crossings(first: Bundle, second: Bundle): Int =
        first.exits.count { it in second.lo..second.hi } + second.entries.count { it in first.lo..first.hi }

    private fun overlaps(first: Bundle, second: Bundle, clearance: Int): Boolean =
        first.lo < second.hi + clearance && second.lo < first.hi + clearance

    /** Past this, the quadratic ordering is not worth its time and the initial order has to do. */
    private const val MAX_ORDERED_BUNDLES = 200
}
