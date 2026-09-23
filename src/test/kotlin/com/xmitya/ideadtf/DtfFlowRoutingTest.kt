package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.layout.DtfFlowChannels
import com.xmitya.ideadtf.flow.layout.DtfFlowLayout
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation
import com.xmitya.ideadtf.flow.layout.FlowEdgeRoute
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowSize
import kotlin.math.max
import kotlin.math.min

/**
 * Where arrows run, and where they turn.
 *
 * The arrows of a busy diagram can only be followed if two of them never share a line unless they
 * share a box: arrows into one task may run together, arrows into two different tasks may not.
 */
class DtfFlowRoutingTest : UsefulTestCase() {

    private val style = DtfFlowLayoutStyle()
    private val boxSize = FlowSize(100, 40)

    /** Each caller calls both tasks: the arrows into one and into the other turn on different lines. */
    fun testFanInsToDifferentTasksRunOnDifferentTracks() {
        val callers = listOf("c1", "c2", "c3")
        val layout = layout(graph(callers + listOf("t1", "t2"), *callers.flatMap { listOf(edge(it, "t1"), edge(it, "t2")) }.toTypedArray()))

        val intoFirst = verticalXs(layout.edges.filter { it.edge.toId == "t1" })
        val intoSecond = verticalXs(layout.edges.filter { it.edge.toId == "t2" })
        assertTrue("both turn somewhere", intoFirst.isNotEmpty() && intoSecond.isNotEmpty())
        assertTrue("shared turning line: $intoFirst / $intoSecond", intoFirst.intersect(intoSecond).isEmpty())
    }

    /** The invariant behind the last test, on a tangle: a shared run always means a shared box. */
    fun testArrowsThatShareARunShareABox() {
        val routes = layout(tangle()).edges.filter { !it.edge.isSelfLoop }

        for (i in routes.indices) {
            for (j in i + 1 until routes.size) {
                val first = routes[i]
                val second = routes[j]
                val shared = setOf(first.edge.fromId, first.edge.toId).intersect(setOf(second.edge.fromId, second.edge.toId))
                if (shared.isEmpty()) {
                    assertFalse("${first.edge.key} and ${second.edge.key} share a run", overlapping(first, second))
                }
            }
        }
    }

    /** Square arrows are square: every run is horizontal or vertical, whichever way the flow reads. */
    fun testArrangedRoutesAreAxisAligned() {
        for (orientation in DtfFlowOrientation.entries) {
            for (route in layout(tangle(), orientation).edges) {
                route.points.zipWithNext().forEach { (from, to) ->
                    assertTrue("$orientation: ${route.edge.key} runs diagonally ${route.points}", from.x == to.x || from.y == to.y)
                }
            }
        }
    }

    /** An arrow leaves and enters along the flow - which is also what a curved arrow takes its tangents from. */
    fun testRoutesLeaveAndEnterAlongTheFlow() {
        for (route in layout(tangle()).edges.filter { !it.edge.isSelfLoop }) {
            val points = route.points
            assertEquals("${route.edge.key} leaves sideways: $points", points[0].y, points[1].y)
            assertEquals("${route.edge.key} arrives sideways: $points", points[points.size - 2].y, points.last().y)
        }
    }

    /** A loop back runs back: it starts at the box it comes from and never doubles back on itself. */
    fun testABackArrowDoesNotDoubleBack() {
        val layout = layout(graph(listOf("a", "b", "c", "d"), edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("d", "a")))

        val back = layout.edges.single { it.edge.fromId == "d" }
        val xs = back.points.map { it.x }
        assertTrue(back.reversed)
        assertEquals("it leaves d", layout.nodes.getValue("d").x, xs.first())
        assertEquals("it enters a", layout.nodes.getValue("a").right, xs.last())
        assertEquals("it doubles back: ${back.points}", xs.sortedDescending(), xs)
    }

    /** An arrow skipping a column goes around the box in it, not through it. */
    fun testALongArrowMissesTheBoxItSkips() {
        val layout = layout(graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c"), edge("a", "c")))

        val box = layout.nodes.getValue("b")
        val long = layout.edges.single { it.edge.fromId == "a" && it.edge.toId == "c" }
        long.points.zipWithNext().forEach { (from, to) ->
            val left = min(from.x, to.x)
            val right = max(from.x, to.x)
            val top = min(from.y, to.y)
            val bottom = max(from.y, to.y)
            val inside = left < box.right && right > box.x && top < box.bottom && bottom > box.y
            assertFalse("the arrow runs through $box: ${long.points}", inside)
        }
    }

    /** Only a gap more arrows turn in than [DtfFlowLayoutStyle.rankGap] has room for is widened. */
    fun testAGapWidensOnlyForManyTracks() {
        val narrow = DtfFlowLayoutStyle(rankGap = 20, trackSpacing = 10)
        val graph = graph(listOf("c1", "c2", "t1", "t2"), edge("c1", "t1"), edge("c1", "t2"), edge("c2", "t1"), edge("c2", "t2"))

        val layout = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, narrow)

        // Two arrows cross between the rows, each on a track of its own: three spacings, not the minimum.
        assertEquals(narrow.padding + boxSize.width + 3 * narrow.trackSpacing, layout.nodes.getValue("t1").x)
    }

    /** Arrows into one task meet in a bus; joining it is not a crossing. */
    fun testABusJunctionIsNotACrossing() {
        val layout = layout(graph(listOf("c1", "c2", "c3", "t"), edge("c1", "t"), edge("c2", "t"), edge("c3", "t")))

        assertTrue(layout.edges.all { it.hops.isEmpty() })
    }

    /** A line crossing several arrows that run together crosses them once, and gets one bridge. */
    fun testAnArrowCrossingABusHopsOnce() {
        val graph = graph(listOf("s1", "s2", "t", "p", "q"), edge("s1", "t"), edge("s2", "t"), edge("p", "q"))
        val pinned = mapOf(
            "s1" to FlowPoint(0, 0),
            "s2" to FlowPoint(0, 100),
            "t" to FlowPoint(400, 300),
            "p" to FlowPoint(100, 180),
            "q" to FlowPoint(600, 180),
        )

        val layout = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style, pinned = pinned)

        assertEquals(listOf(FlowPoint(250, 200)), layout.edges.single { it.edge.fromId == "p" }.hops)
    }

    fun testRoutesAreDeterministic() {
        assertEquals(layout(tangle()).edges.map { it.points }, layout(tangle()).edges.map { it.points })
    }

    /** Arrows into one box share a track, and are told apart from arrows out of another. */
    fun testFanInsAreGroupedBeforeFanOuts() {
        val across = mapOf("a" to 0, "b" to 100, "t" to 50, "u" to 200)
        val intoT = listOf(hop("a", "t"), hop("b", "t"))
        val outOfA = hop("a", "u")

        val gap = DtfFlowChannels.gapOf(intoT + outOfA, across::getValue, 10)

        assertEquals(gap.slotOf(intoT[0]), gap.slotOf(intoT[1]))
        assertFalse(gap.slotOf(outOfA) == gap.slotOf(intoT[0]))
        assertEquals(2, gap.tracks)
    }

    /** Runs that never come near each other can share one line; a tall column needs few tracks. */
    fun testDisjointBundlesShareATrack() {
        val across = mapOf("a" to 0, "b" to 40, "c" to 400, "d" to 440)

        val gap = DtfFlowChannels.gapOf(listOf(hop("a", "b"), hop("c", "d")), across::getValue, 10)

        assertEquals(1, gap.tracks)
        assertEquals(0, gap.slotOf(hop("a", "b")))
        assertEquals(0, gap.slotOf(hop("c", "d")))
    }

    /**
     * Two arrows going down, the second starting inside the first's run: turning the lower one first
     * lets both pass without crossing, which the other order cannot.
     */
    fun testStaggeredArrowsAreOrderedNotToCross() {
        val across = mapOf("a1" to 0, "b1" to 100, "a2" to 50, "b2" to 150)
        val upper = hop("a1", "b1")
        val lower = hop("a2", "b2")

        val gap = DtfFlowChannels.gapOf(listOf(upper, lower), across::getValue, 10)

        assertEquals(0, gap.slotOf(lower))
        assertEquals(1, gap.slotOf(upper))
    }

    fun testAStraightArrowNeedsNoTrack() {
        val gap = DtfFlowChannels.gapOf(listOf(hop("a", "b")), mapOf("a" to 20, "b" to 20)::getValue, 10)

        assertEquals(0, gap.tracks)
        assertNull(gap.slotOf(hop("a", "b")))
    }

    /** Fan-ins, fan-outs, a long arrow, a loop back across three columns, and a self-reschedule. */
    private fun tangle() = graph(
        listOf("c1", "c2", "c3", "t1", "t2", "z", "w"),
        edge("c1", "t1"),
        edge("c2", "t1"),
        edge("c2", "t2"),
        edge("c3", "t2"),
        edge("t1", "z"),
        edge("t2", "z"),
        edge("c1", "z"),
        edge("z", "w"),
        edge("w", "c2"),
        edge("t2", "t2"),
    )

    private fun verticalXs(routes: List<FlowEdgeRoute>): Set<Int> =
        routes.flatMap { route -> route.points.zipWithNext().filter { (a, b) -> a.x == b.x && a.y != b.y }.map { it.first.x } }.toSet()

    /** Whether the two share a stretch of line, not merely a point. */
    private fun overlapping(first: FlowEdgeRoute, second: FlowEdgeRoute): Boolean =
        first.points.zipWithNext().any { (a1, a2) -> second.points.zipWithNext().any { (b1, b2) -> overlap(a1, a2, b1, b2) } }

    private fun overlap(a1: FlowPoint, a2: FlowPoint, b1: FlowPoint, b2: FlowPoint): Boolean = when {
        a1.x == a2.x && b1.x == b2.x && a1.x == b1.x -> min(max(a1.y, a2.y), max(b1.y, b2.y)) > max(min(a1.y, a2.y), min(b1.y, b2.y))
        a1.y == a2.y && b1.y == b2.y && a1.y == b1.y -> min(max(a1.x, a2.x), max(b1.x, b2.x)) > max(min(a1.x, a2.x), min(b1.x, b2.x))
        else -> false
    }

    private fun hop(from: String, to: String) = DtfFlowChannels.Hop(from, to, reversed = false)

    private fun layout(graph: DtfFlowGraph, orientation: DtfFlowOrientation = DtfFlowOrientation.LEFT_TO_RIGHT): DtfFlowLayout =
        DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style, orientation)

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String) = DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE)
}
