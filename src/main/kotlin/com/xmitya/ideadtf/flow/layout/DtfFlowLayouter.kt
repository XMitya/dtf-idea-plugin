package com.xmitya.ideadtf.flow.layout

import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowGraph

/**
 * One arrow, routed.
 *
 * @param points at least two: the source border, any waypoints, the target border.
 * @param labelAnchor the middle of the middle segment - where a label would hang.
 * @param labelSlot the rectangle kept clear for [DtfFlowEdge] labels, null while an edge has nothing
 *   to say. Computed now so that turning message types and conditions on is a paint change rather
 *   than a re-layout.
 * @param reversed whether ranking had to flip this edge to break a cycle. The arrowhead still points
 *   the way the code does; this only says the arrangement had to cheat.
 */
class FlowEdgeRoute(
    val edge: DtfFlowEdge,
    val points: List<FlowPoint>,
    val labelAnchor: FlowPoint,
    val labelSlot: FlowRect?,
    val reversed: Boolean,
)

/**
 * A finished arrangement.
 *
 * Hit testing lives here rather than in the canvas, so that "a double-click on this box opens that
 * task" can be asserted without a mouse.
 */
class DtfFlowLayout(val nodes: Map<String, FlowRect>, val edges: List<FlowEdgeRoute>, val size: FlowSize) {

    fun nodeAt(point: FlowPoint): String? = nodes.entries.firstOrNull { it.value.contains(point) }?.key

    fun edgeAt(point: FlowPoint, tolerance: Int): FlowEdgeRoute? = edges.firstOrNull { route ->
        route.points.zipWithNext().any { (from, to) -> distanceToSegment(point, from, to) <= tolerance }
    }

    companion object {
        val EMPTY = DtfFlowLayout(emptyMap(), emptyList(), FlowSize.EMPTY)
    }
}

/**
 * Arranges a flow graph left to right.
 *
 * Sugiyama's three steps, kept small: break the cycles, rank by longest path, then reduce crossings
 * with a couple of median sweeps. That is more than enough for graphs of this size - real DTF chains
 * are three to six nodes and a whole module is a few dozen - and it is deterministic, which is what
 * lets the result be asserted rather than eyeballed.
 *
 * Pure. It is handed node sizes rather than measuring them, so it needs no toolkit, no scaling and
 * no PSI.
 */
object DtfFlowLayouter {

    fun layout(graph: DtfFlowGraph, sizes: Map<String, FlowSize>, style: DtfFlowLayoutStyle = DtfFlowLayoutStyle()): DtfFlowLayout {
        if (graph.nodes.isEmpty()) return DtfFlowLayout.EMPTY

        val ids = graph.nodes.map { it.id }
        val present = ids.toSet()
        val selfEdges = graph.edges.filter { it.fromId == it.toId && it.fromId in present }
        val realEdges = graph.edges.filter { it.fromId != it.toId && it.fromId in present && it.toId in present }

        val reversed = backEdgesOf(ids, realEdges)
        val ranks = rank(ids, realEdges, reversed)
        val columns = columnsOf(ids, ranks, realEdges, reversed, style)
        orderColumns(columns, realEdges, reversed, style)

        val rects = place(columns, sizes, style)
        val routes = realEdges.map { route(it, rects, columns.dummiesOf(it), reversed.contains(it.key), style) } +
            selfEdges.mapNotNull { selfRoute(it, rects[it.fromId], style) }

        return DtfFlowLayout(rects, routes, sizeOf(rects, routes, style))
    }

    // --- step 1: break cycles -------------------------------------------------------------------

    /**
     * Edge keys that close a cycle, found by depth-first search.
     *
     * Iterative rather than recursive: the graphs are small, but a stack overflow in a layout pass is
     * an ugly way to find that out. Sources go first so that a flow's entry points anchor the walk
     * and the reversed edge is the one that genuinely loops back.
     */
    private fun backEdgesOf(ids: List<String>, edges: List<DtfFlowEdge>): Set<String> {
        val outgoing = edges.groupBy { it.fromId }
        val indegree = ids.associateWith { id -> edges.count { it.toId == id } }
        val state = HashMap<String, Int>()
        val back = LinkedHashSet<String>()

        val roots = ids.sortedWith(compareBy({ indegree.getValue(it) != 0 }, { it }))
        for (root in roots) {
            if (state[root] != null) continue
            val stack = ArrayDeque<Frame>()
            stack += Frame(root, outgoing[root].orEmpty())
            state[root] = GREY
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame.next >= frame.edges.size) {
                    state[frame.id] = BLACK
                    stack.removeLast()
                    continue
                }
                val edge = frame.edges[frame.next++]
                when (state[edge.toId]) {
                    GREY -> back += edge.key

                    BLACK -> Unit

                    else -> {
                        state[edge.toId] = GREY
                        stack += Frame(edge.toId, outgoing[edge.toId].orEmpty())
                    }
                }
            }
        }
        return back
    }

    private class Frame(val id: String, val edges: List<DtfFlowEdge>) {
        var next = 0
    }

    private const val GREY = 1
    private const val BLACK = 2

    // --- step 2: rank ---------------------------------------------------------------------------

    /** Longest path from the sources, which puts every node as far right as its deepest caller. */
    private fun rank(ids: List<String>, edges: List<DtfFlowEdge>, reversed: Set<String>): Map<String, Int> {
        val forward = edges.map { orient(it, reversed) }
        val indegree = HashMap<String, Int>()
        ids.forEach { indegree[it] = 0 }
        forward.forEach { indegree[it.second] = indegree.getValue(it.second) + 1 }

        val result = HashMap<String, Int>()
        ids.forEach { result[it] = 0 }
        val ready = ArrayDeque(ids.filter { indegree.getValue(it) == 0 }.sorted())
        // A graph that is still cyclic after the reversal cannot empty the queue; whatever is left
        // keeps the rank it has, which is a readable arrangement rather than an endless loop.
        val outgoing = forward.groupBy({ it.first }, { it.second })
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            for (next in outgoing[id].orEmpty()) {
                result[next] = maxOf(result.getValue(next), result.getValue(id) + 1)
                indegree[next] = indegree.getValue(next) - 1
                if (indegree.getValue(next) == 0) ready += next
            }
        }
        return result
    }

    private fun orient(edge: DtfFlowEdge, reversed: Set<String>): Pair<String, String> =
        if (edge.key in reversed) edge.toId to edge.fromId else edge.fromId to edge.toId

    // --- step 3: columns, dummies and ordering --------------------------------------------------

    private class Columns(val ranks: List<MutableList<String>>, val dummies: Map<String, List<String>>, val rankOf: Map<String, Int>) {
        fun dummiesOf(edge: DtfFlowEdge): List<String> = dummies[edge.key].orEmpty()
    }

    /**
     * The nodes of each column, with a chain of invisible waypoints inserted for every edge that
     * skips a column - so that a long arrow is routed around the boxes in between rather than
     * through them.
     */
    private fun columnsOf(
        ids: List<String>,
        rankOf: Map<String, Int>,
        edges: List<DtfFlowEdge>,
        reversed: Set<String>,
        style: DtfFlowLayoutStyle,
    ): Columns {
        val count = (rankOf.values.maxOrNull() ?: 0) + 1
        val ranks = List(count) { mutableListOf<String>() }
        ids.sortedWith(compareBy({ rankOf.getValue(it) }, { it })).forEach { ranks[rankOf.getValue(it)] += it }

        val dummies = LinkedHashMap<String, List<String>>()
        val extended = rankOf.toMutableMap()
        for (edge in edges) {
            val (from, to) = orient(edge, reversed)
            val fromRank = rankOf.getValue(from)
            val toRank = rankOf.getValue(to)
            if (toRank - fromRank <= 1) continue
            val chain = ((fromRank + 1) until toRank).map { rankIndex ->
                val id = "$DUMMY${edge.key}:$rankIndex"
                ranks[rankIndex] += id
                extended[id] = rankIndex
                id
            }
            dummies[edge.key] = chain
        }
        // Style is threaded through so that a labelled edge can claim a wider waypoint later.
        require(style.dummyHeight >= 0)
        return Columns(ranks, dummies, extended)
    }

    /**
     * Median sweeps, keeping the best arrangement seen.
     *
     * Median rather than barycentre because a fan-out is a star, and a star is exactly the shape a
     * barycentre smears. Counting crossings after each sweep and keeping the best makes "reducing
     * crossings never makes it worse" true rather than hoped for.
     */
    private fun orderColumns(columns: Columns, edges: List<DtfFlowEdge>, reversed: Set<String>, style: DtfFlowLayoutStyle) {
        val ranks = columns.ranks
        if (ranks.size < 2) return

        val links = adjacency(columns, edges, reversed)
        var best = ranks.map { it.toList() }
        var bestCrossings = crossings(ranks, links)

        repeat(style.orderingSweeps) { sweep ->
            val indices = if (sweep % 2 == 0) 1..ranks.lastIndex else ranks.lastIndex - 1 downTo 0
            for (index in indices) {
                val neighbours = if (sweep % 2 == 0) ranks[index - 1] else ranks[index + 1]
                val positions = neighbours.withIndex().associate { (position, id) -> id to position }
                val current = ranks[index].withIndex().associate { (position, id) -> id to position }
                ranks[index].sortWith(
                    compareBy(
                        {
                            medianOf(links.neighboursOf(it, backwards = sweep % 2 == 0).mapNotNull(positions::get)).takeIf { m -> m >= 0 }
                                ?: current.getValue(it)
                        },
                        { current.getValue(it) },
                    ),
                )
            }
            val now = crossings(ranks, links)
            if (now < bestCrossings) {
                bestCrossings = now
                best = ranks.map { it.toList() }
            }
        }
        ranks.forEachIndexed { index, rank ->
            rank.clear()
            rank += best[index]
        }
    }

    private class Adjacency(val forward: Map<String, List<String>>, val backward: Map<String, List<String>>) {
        fun neighboursOf(id: String, backwards: Boolean): List<String> = if (backwards) backward[id].orEmpty() else forward[id].orEmpty()
    }

    /** The edge set as the ordering sees it: every long edge already split at its waypoints. */
    private fun adjacency(columns: Columns, edges: List<DtfFlowEdge>, reversed: Set<String>): Adjacency {
        val pairs = mutableListOf<Pair<String, String>>()
        for (edge in edges) {
            val (from, to) = orient(edge, reversed)
            val chain = listOf(from) + columns.dummiesOf(edge) + listOf(to)
            chain.zipWithNext().forEach { pairs += it }
        }
        return Adjacency(
            forward = pairs.groupBy({ it.first }, { it.second }),
            backward = pairs.groupBy({ it.second }, { it.first }),
        )
    }

    private fun crossings(ranks: List<List<String>>, links: Adjacency): Int {
        var total = 0
        for (index in 0 until ranks.lastIndex) {
            val next = ranks[index + 1].withIndex().associate { (position, id) -> id to position }
            val outgoing = ranks[index].flatMap { id -> links.neighboursOf(id, backwards = false).mapNotNull(next::get).sorted() }
            for (i in outgoing.indices) {
                for (j in i + 1 until outgoing.size) {
                    if (outgoing[i] > outgoing[j]) total++
                }
            }
        }
        return total
    }

    // --- step 4: coordinates --------------------------------------------------------------------

    private fun place(columns: Columns, sizes: Map<String, FlowSize>, style: DtfFlowLayoutStyle): Map<String, FlowRect> {
        val rects = LinkedHashMap<String, FlowRect>()
        var x = style.padding
        for (rank in columns.ranks) {
            val widest = rank.maxOfOrNull { sizeOf(it, sizes, style).width } ?: 0
            var y = style.padding
            for (id in rank) {
                val size = sizeOf(id, sizes, style)
                rects[id] = FlowRect(x, y, size.width, size.height)
                y += size.height + style.nodeGap
            }
            x += widest + style.rankGap
        }
        centreOnNeighbours(columns, rects, style)
        return rects
    }

    /**
     * Pulls each box opposite the ones it connects to, then pushes the column apart again where that
     * made two overlap. Two passes each way is enough to make a chain read as a straight line and a
     * fan-out as a symmetric bundle.
     */
    private fun centreOnNeighbours(columns: Columns, rects: MutableMap<String, FlowRect>, style: DtfFlowLayoutStyle) {
        repeat(2) {
            for (direction in listOf(1, -1)) {
                val indices = if (direction == 1) 1..columns.ranks.lastIndex else columns.ranks.lastIndex - 1 downTo 0
                for (index in indices) {
                    val neighbourRank = columns.ranks[index - direction]
                    val neighbourCentres = neighbourRank.mapNotNull { rects[it]?.centerY }
                    if (neighbourCentres.isEmpty()) continue
                    for (id in columns.ranks[index]) {
                        val rect = rects[id] ?: continue
                        val wanted = medianOf(neighbourCentres)
                        rects[id] = rect.copy(y = wanted - rect.height / 2)
                    }
                    separate(columns.ranks[index], rects, style)
                }
            }
        }
        val top = rects.values.minOfOrNull { it.y } ?: style.padding
        val shift = style.padding - top
        if (shift != 0) rects.keys.toList().forEach { rects[it] = rects.getValue(it).translated(0, shift) }
    }

    private fun separate(rank: List<String>, rects: MutableMap<String, FlowRect>, style: DtfFlowLayoutStyle) {
        var previousBottom: Int? = null
        for (id in rank) {
            val rect = rects[id] ?: continue
            val minimumY = previousBottom?.plus(style.nodeGap)
            val y = if (minimumY != null) clampMin(rect.y, minimumY) else rect.y
            rects[id] = rect.copy(y = y)
            previousBottom = y + rect.height
        }
    }

    private fun sizeOf(id: String, sizes: Map<String, FlowSize>, style: DtfFlowLayoutStyle): FlowSize =
        sizes[id] ?: FlowSize(0, style.dummyHeight)

    // --- step 5: routes -------------------------------------------------------------------------

    private fun route(
        edge: DtfFlowEdge,
        rects: Map<String, FlowRect>,
        dummies: List<String>,
        reversed: Boolean,
        style: DtfFlowLayoutStyle,
    ): FlowEdgeRoute {
        val from = rects.getValue(edge.fromId)
        val to = rects.getValue(edge.toId)
        val leftToRight = from.right <= to.x

        val start = if (leftToRight) FlowPoint(from.right, from.centerY) else FlowPoint(from.x, from.centerY)
        val end = if (leftToRight) FlowPoint(to.x, to.centerY) else FlowPoint(to.right, to.centerY)
        val waypoints = dummies.mapNotNull { rects[it] }.map { FlowPoint(it.centerX, it.centerY) }

        val points = buildList {
            add(start)
            if (waypoints.isEmpty() && start.y != end.y) {
                // A plain diagonal between two adjacent columns reads as a mistake; an elbow halfway
                // across the gap reads as a connection.
                val middle = start.x + (end.x - start.x) / 2
                add(FlowPoint(middle, start.y))
                add(FlowPoint(middle, end.y))
            }
            addAll(waypoints)
            add(end)
        }
        val anchor = anchorOf(points)
        val slot = if (edge.hasLabel) {
            FlowRect(anchor.x - style.labelSlot.width / 2, anchor.y - style.labelSlot.height, style.labelSlot.width, style.labelSlot.height)
        } else {
            null
        }
        return FlowEdgeRoute(edge, points, anchor, slot, reversed)
    }

    /** A task that reschedules itself gets a loop over its own top, out of everyone else's way. */
    private fun selfRoute(edge: DtfFlowEdge, rect: FlowRect?, style: DtfFlowLayoutStyle): FlowEdgeRoute? {
        if (rect == null) return null
        val out = style.rankGap / 3
        val up = rect.height / 2 + style.nodeGap / 2
        val points = listOf(
            FlowPoint(rect.right, rect.centerY - rect.height / 4),
            FlowPoint(rect.right + out, rect.centerY - rect.height / 4),
            FlowPoint(rect.right + out, rect.y - up),
            FlowPoint(rect.centerX, rect.y - up),
            FlowPoint(rect.centerX, rect.y),
        )
        return FlowEdgeRoute(edge, points, FlowPoint(rect.right + out, rect.y - up), null, reversed = false)
    }

    private fun anchorOf(points: List<FlowPoint>): FlowPoint {
        val index = (points.size - 1) / 2
        val from = points[index]
        val to = points[index + 1]
        return FlowPoint((from.x + to.x) / 2, (from.y + to.y) / 2)
    }

    private fun sizeOf(rects: Map<String, FlowRect>, routes: List<FlowEdgeRoute>, style: DtfFlowLayoutStyle): FlowSize {
        val xs = rects.values.map { it.right } + routes.flatMap { route -> route.points.map { it.x } }
        val ys = rects.values.map { it.bottom } + routes.flatMap { route -> route.points.map { it.y } }
        return FlowSize(highest(xs) + style.padding, highest(ys) + style.padding)
    }

    private const val DUMMY = "dummy:"
}
