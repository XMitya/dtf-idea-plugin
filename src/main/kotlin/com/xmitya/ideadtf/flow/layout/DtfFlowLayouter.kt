package com.xmitya.ideadtf.flow.layout

import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowGraph
import kotlin.math.abs

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
    /**
     * Points where this arrow crosses one drawn before it, so the painter can hop over rather than
     * through. Only one of any two crossing arrows carries the hop; otherwise both would bulge and
     * the crossing would read as a knot.
     */
    val hops: List<FlowPoint> = emptyList(),
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
 * Arranges a flow graph.
 *
 * Sugiyama's steps, kept small. The graph is first split into the flows it is made of, and each is
 * arranged on its own: break the cycles, rank by longest path, reduce crossings with a couple of
 * median sweeps, pull every box opposite the ones it connects to, and give every arrow that turns in
 * a gap a track of its own. The finished flows are then tiled by [DtfFlowPacking]. A real DTF chain
 * is three to six boxes and a whole module a few dozen such chains, so none of this needs to be
 * clever - and all of it is deterministic, which is what lets the result be asserted rather than
 * eyeballed.
 *
 * Pure. It is handed node sizes rather than measuring them, so it needs no toolkit, no scaling and
 * no PSI.
 */
object DtfFlowLayouter {

    /**
     * @param orientation which way the flow reads.
     * @param pinned boxes the reader has dragged somewhere, in final coordinates. They keep their
     *   place and everything else is arranged around them, which is what makes pulling one node out
     *   of a tangle a local edit rather than a reshuffle of the whole picture.
     */
    fun layout(
        graph: DtfFlowGraph,
        sizes: Map<String, FlowSize>,
        style: DtfFlowLayoutStyle = DtfFlowLayoutStyle(),
        orientation: DtfFlowOrientation = DtfFlowOrientation.LEFT_TO_RIGHT,
        pinned: Map<String, FlowPoint> = emptyMap(),
    ): DtfFlowLayout {
        if (graph.nodes.isEmpty()) return DtfFlowLayout.EMPTY

        val ids = graph.nodes.map { it.id }
        val present = ids.toSet()
        val selfEdges = graph.edges.filter { it.isSelfLoop && it.fromId in present }
        val realEdges = graph.edges.filter { !it.isSelfLoop && it.fromId in present && it.toId in present }

        // Arranged, tracked and tiled without the dragged boxes: none of that may change because one
        // box was moved, or dragging would stop being a local edit.
        val arrangements = partsOf(ids, realEdges, selfEdges).map { arrange(it, sizes, style, orientation) }
        val corners = DtfFlowPacking.packed(arrangements.map { it.size }, style, orientation)
        val natural = HashMap<String, FlowRect>()
        val arranged = HashMap<DtfFlowEdge, List<FlowPoint>>()
        val reversed = HashSet<String>()
        arrangements.forEachIndexed { index, part ->
            val corner = corners[index]
            part.rects.forEach { (id, rect) -> natural[id] = rect.translated(corner.x, corner.y) }
            part.routes.forEach { (edge, points) -> arranged[edge] = points.map { it.translated(corner.x, corner.y) } }
            reversed += part.reversed
        }

        val rects = LinkedHashMap<String, FlowRect>()
        ids.forEach { id ->
            val placed = natural.getValue(id)
            rects[id] = pinned[id]?.let { FlowRect(it.x, it.y, placed.width, placed.height) } ?: placed
        }
        // In the graph's own order rather than flow by flow: which of two crossing arrows hops is
        // decided by that order.
        val routes = realEdges.map { edge ->
            // The tracks were chosen for where the arrangement put these boxes. Once one has been
            // dragged they describe a path around nothing, so a moved edge goes straight instead.
            val detached = edge.fromId in pinned || edge.toId in pinned
            val points = if (detached) elbowOf(rects.getValue(edge.fromId), rects.getValue(edge.toId)) else arranged.getValue(edge)
            routeOf(edge, points, edge.key in reversed, style)
        } + selfEdges.mapNotNull { selfRoute(it, rects[it.fromId], style) }

        return DtfFlowLayout(rects, withLineJumps(routes), sizeOf(rects, routes, style))
    }

    /**
     * Marks every place one arrow crosses another.
     *
     * The crossing belongs to whichever arrow is drawn later, so that exactly one of the pair hops;
     * and it is marked once however many arrows share the run being crossed, or a line passing a
     * bus would get a bridge per arrow on it, all in the same spot. Quadratic in the number of
     * segments, but only for arrows whose extents overlap - with flows tiled apart, most do not -
     * and capped anyway: past [MAX_JUMP_EDGES] a diagram is unreadable for reasons no hop will fix.
     */
    private fun withLineJumps(routes: List<FlowEdgeRoute>): List<FlowEdgeRoute> {
        if (routes.size > MAX_JUMP_EDGES) return routes
        val bounds = routes.map { FlowRect.around(it.points) }
        return routes.mapIndexed { index, route ->
            val hops = LinkedHashSet<FlowPoint>()
            for (earlier in 0 until index) {
                if (!bounds[index].intersects(bounds[earlier])) continue
                for ((a1, a2) in route.points.zipWithNext()) {
                    for ((b1, b2) in routes[earlier].points.zipWithNext()) {
                        crossingOf(a1, a2, b1, b2)?.let { hops += it }
                    }
                }
            }
            if (hops.isEmpty()) {
                route
            } else {
                FlowEdgeRoute(route.edge, route.points, route.labelAnchor, route.labelSlot, route.reversed, hops.toList())
            }
        }
    }

    // --- step 0: independent flows --------------------------------------------------------------

    private class Part(val ids: List<String>, val edges: List<DtfFlowEdge>, val selfEdges: List<DtfFlowEdge>)

    /**
     * The flows the graph is made of: boxes joined by any chain of arrows, whichever way those
     * point.
     *
     * In the order their first box appears in the graph, each keeping the graph's order inside it.
     * A module is dozens of these, and ranking them together is what used to stack every task of a
     * module in one column with its callers somewhere else entirely.
     */
    private fun partsOf(ids: List<String>, edges: List<DtfFlowEdge>, selfEdges: List<DtfFlowEdge>): List<Part> {
        val leader = ids.associateWithTo(HashMap()) { it }
        fun find(id: String): String {
            var current = id
            while (leader.getValue(current) != current) current = leader.getValue(current)
            return current
        }
        edges.forEach { edge ->
            val from = find(edge.fromId)
            val to = find(edge.toId)
            if (from != to) leader[to] = from
        }
        val members = LinkedHashMap<String, MutableList<String>>()
        ids.forEach { members.getOrPut(find(it)) { mutableListOf() } += it }
        return members.values.map { part ->
            val inside = part.toSet()
            Part(part, edges.filter { it.fromId in inside }, selfEdges.filter { it.fromId in inside })
        }
    }

    /** One flow, arranged in a frame of its own: the top-left of everything it draws is (0, 0). */
    private class Arrangement(
        val rects: Map<String, FlowRect>,
        val routes: Map<DtfFlowEdge, List<FlowPoint>>,
        val reversed: Set<String>,
        val size: FlowSize,
    )

    private fun arrange(part: Part, sizes: Map<String, FlowSize>, style: DtfFlowLayoutStyle, orientation: DtfFlowOrientation): Arrangement {
        val reversed = backEdgesOf(part.ids, part.edges)
        val ranks = pullSourcesForward(part.edges, reversed, rank(part.ids, part.edges, reversed))
        val columns = columnsOf(part.ids, ranks, part.edges, reversed)
        val links = adjacency(columns, part.edges, reversed)
        orderColumns(columns, links, style)

        val axis = placeAcross(columns, links, sizes, style, orientation)
        val geometry = geometryOf(columns, axis, gapsOf(columns, axis, part.edges, reversed, style), style)
        columns.rankOf.forEach { (id, rank) -> axis[id] = axis.getValue(id).copy(along = geometry.start[rank]) }

        val rects = LinkedHashMap<String, FlowRect>()
        part.ids.forEach { rects[it] = toRect(axis.getValue(it), orientation, geometry.totalAlong) }
        val routes = HashMap<DtfFlowEdge, List<FlowPoint>>()
        part.edges.forEach { edge ->
            routes[edge] = channelRoute(edge, columns, axis, geometry, reversed).map { toPoint(it, orientation, geometry.totalAlong) }
        }
        // A task that reschedules itself loops over its own top, which for a box on the edge of the
        // flow is outside everything else it draws - over the next flow, or off the canvas. So the
        // loop counts towards the flow's extent, and the room is made for it here.
        val loops = part.selfEdges.mapNotNull { selfRoute(it, rects[it.fromId], style) }.flatMap { it.points }
        val corners = rects.values.flatMap { listOf(FlowPoint(it.x, it.y), FlowPoint(it.right, it.bottom)) }
        val bounds = FlowRect.around(corners + routes.values.flatten() + loops)
        return Arrangement(
            rects.mapValues { it.value.translated(-bounds.x, -bounds.y) },
            routes.mapValues { (_, points) -> points.map { it.translated(-bounds.x, -bounds.y) } },
            reversed,
            FlowSize(bounds.width, bounds.height),
        )
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

    /**
     * Moves every starting point up to just before the first box it leads to.
     *
     * Longest path puts every source in the first column, so the caller of a task three hops down a
     * chain lands among the callers of the first one, with a long arrow over everything in between.
     * Next to the box it calls is where a reader looks for it. Only sources move: anything else has
     * an arrow coming in that moving it would stretch.
     */
    private fun pullSourcesForward(edges: List<DtfFlowEdge>, reversed: Set<String>, ranks: Map<String, Int>): Map<String, Int> {
        val forward = edges.map { orient(it, reversed) }
        val targets = forward.mapTo(HashSet()) { it.second }
        val successors = forward.groupBy({ it.first }, { it.second })
        return ranks.mapValues { (id, rank) ->
            if (id in targets) rank else successors[id]?.minOf { ranks.getValue(it) - 1 } ?: rank
        }
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
    private fun columnsOf(ids: List<String>, rankOf: Map<String, Int>, edges: List<DtfFlowEdge>, reversed: Set<String>): Columns {
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
        return Columns(ranks, dummies, extended)
    }

    /** The boxes an arrow passes through, in the direction ranking gave it: source, waypoints, target. */
    private fun chainOf(edge: DtfFlowEdge, columns: Columns, reversed: Set<String>): List<String> {
        val (from, to) = orient(edge, reversed)
        return listOf(from) + columns.dummiesOf(edge) + listOf(to)
    }

    /**
     * Median sweeps, keeping the best arrangement seen.
     *
     * Median rather than barycentre because a fan-out is a star, and a star is exactly the shape a
     * barycentre smears. Counting crossings after each sweep and keeping the best makes "reducing
     * crossings never makes it worse" true rather than hoped for.
     */
    private fun orderColumns(columns: Columns, links: Adjacency, style: DtfFlowLayoutStyle) {
        val ranks = columns.ranks
        if (ranks.size < 2) return

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
        val pairs = edges.flatMap { chainOf(it, columns, reversed).zipWithNext() }
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

    /**
     * A box in rank space: [along] runs in the direction the flow reads, [across] is the offset
     * within its rank. Only [toRect] turns that into pixels, which is the whole of what an
     * orientation change costs.
     */
    private data class AxisRect(val along: Int, val across: Int, val alongSize: Int, val acrossSize: Int) {
        val centre: Int get() = across + acrossSize / 2
    }

    /** A point in rank space, for the same reason. */
    private data class AxisPoint(val along: Int, val across: Int)

    /** Each rank stacked in the order chosen, then pulled opposite what it connects to. [along] comes later. */
    private fun placeAcross(
        columns: Columns,
        links: Adjacency,
        sizes: Map<String, FlowSize>,
        style: DtfFlowLayoutStyle,
        orientation: DtfFlowOrientation,
    ): MutableMap<String, AxisRect> {
        val rects = HashMap<String, AxisRect>()
        for (rank in columns.ranks) {
            var across = 0
            for (id in rank) {
                val size = sizes[id] ?: FlowSize(style.dummyHeight, style.dummyHeight)
                val alongSize = if (orientation.isHorizontal) size.width else size.height
                val acrossSize = if (orientation.isHorizontal) size.height else size.width
                rects[id] = AxisRect(0, across, alongSize, acrossSize)
                across += acrossSize + style.nodeGap
            }
        }
        centreOnNeighbours(columns, links, rects, style.nodeGap)
        return rects
    }

    /**
     * Pulls each box opposite the boxes it is connected to, then settles each rank so that none
     * overlap.
     *
     * Connected ones, not the whole neighbouring rank: pulled towards the middle of everything next
     * to it, every box of a rank wants the same spot, and a caller ends up wherever its rank happened
     * to be stacked rather than next to the task it calls. Two passes each way are enough to make a
     * chain read as a straight line and a fan-out as a symmetric bundle.
     *
     * The last pass pulls towards what leads in, so that is what each box finally sits opposite: a
     * task in the middle of its own callers, a fan-out's targets around their root. Ending the other
     * way lets two tasks that schedule the same next one squeeze together in front of it, away from
     * the callers each of them has.
     */
    private fun centreOnNeighbours(columns: Columns, links: Adjacency, rects: MutableMap<String, AxisRect>, gap: Int) {
        repeat(CENTRING_ROUNDS) {
            for (direction in listOf(-1, 1)) {
                val indices = if (direction == 1) 1..columns.ranks.lastIndex else columns.ranks.lastIndex - 1 downTo 0
                for (index in indices) {
                    val rank = columns.ranks[index]
                    val wanted = rank.associateWith { id ->
                        val centres = links.neighboursOf(id, backwards = direction == 1).map { rects.getValue(it).centre }
                        if (centres.isEmpty()) rects.getValue(id).centre else medianOf(centres)
                    }
                    settle(rank, wanted, rects, gap)
                }
            }
        }
    }

    /**
     * Puts each box of a rank as near its wanted centre as it can go without overlapping another,
     * keeping their order.
     *
     * Pool adjacent violators: boxes that would overlap are merged into one block, which sits where
     * its members want it on average. Unlike pushing every overlap downwards, that leaves a fan-in
     * centred on its target instead of hanging below it.
     */
    private fun settle(rank: List<String>, wanted: Map<String, Int>, rects: MutableMap<String, AxisRect>, gap: Int) {
        val blocks = ArrayList<Block>()
        for (id in rank) {
            val rect = rects.getValue(id)
            var block = Block(mutableListOf(id), (wanted.getValue(id) - rect.acrossSize / 2).toLong(), 1, rect.acrossSize)
            while (blocks.isNotEmpty() && blocks.last().let { it.top + it.extent + gap > block.top }) {
                val previous = blocks.removeLast()
                // What the later members want, restated as where they would put the merged block's top.
                previous.sum += block.sum - block.count * (previous.extent + gap).toLong()
                previous.count += block.count
                previous.extent += gap + block.extent
                previous.ids += block.ids
                block = previous
            }
            blocks += block
        }
        for (block in blocks) {
            var across = block.top
            for (id in block.ids) {
                val rect = rects.getValue(id)
                rects[id] = rect.copy(across = across)
                across += rect.acrossSize + gap
            }
        }
    }

    /** Consecutive boxes that sit together; [sum] over [count] is where they want its top. */
    private class Block(val ids: MutableList<String>, var sum: Long, var count: Int, var extent: Int) {
        val top: Int get() = Math.floorDiv(sum, count.toLong()).toInt()
    }

    /** The tracks each gap needs, from the arrows that step across it. */
    private fun gapsOf(
        columns: Columns,
        axis: Map<String, AxisRect>,
        edges: List<DtfFlowEdge>,
        reversed: Set<String>,
        style: DtfFlowLayoutStyle,
    ): List<DtfFlowChannels.Gap> {
        val hops = List(columns.ranks.size) { LinkedHashSet<DtfFlowChannels.Hop>() }
        for (edge in edges) {
            val back = edge.key in reversed
            for ((from, to) in chainOf(edge, columns, reversed).zipWithNext()) {
                val rank = columns.rankOf.getValue(from)
                if (columns.rankOf.getValue(to) == rank + 1) hops[rank] += DtfFlowChannels.Hop(from, to, back)
            }
        }
        return hops.map { DtfFlowChannels.gapOf(it, { id -> axis.getValue(id).centre }, style.trackSpacing) }
    }

    /**
     * Where each rank starts along the flow, now that every gap knows how many tracks it needs.
     *
     * A gap is [DtfFlowLayoutStyle.rankGap] wide unless its tracks need more room than that. A plain
     * chain needs none at all, so it keeps exactly the spacing it always had.
     */
    private class Geometry(val start: IntArray, val thickness: IntArray, val gapSize: IntArray, val gaps: List<DtfFlowChannels.Gap>) {
        val totalAlong: Int get() = start.last() + thickness.last()

        /** The tracks share their gap evenly, so a lone track sits where the one elbow always did. */
        fun trackAlong(rank: Int, slot: Int): Int = start[rank] + thickness[rank] + (slot + 1) * gapSize[rank] / (gaps[rank].tracks + 1)
    }

    private fun geometryOf(
        columns: Columns,
        axis: Map<String, AxisRect>,
        gaps: List<DtfFlowChannels.Gap>,
        style: DtfFlowLayoutStyle,
    ): Geometry {
        val count = columns.ranks.size
        val thickness = IntArray(count) { rank -> columns.ranks[rank].maxOfOrNull { axis.getValue(it).alongSize } ?: 0 }
        val gapSize = IntArray(count) { rank -> maxOf(style.rankGap, (gaps[rank].tracks + 1) * style.trackSpacing) }
        val start = IntArray(count)
        for (rank in 1 until count) start[rank] = start[rank - 1] + thickness[rank - 1] + gapSize[rank - 1]
        return Geometry(start, thickness, gapSize, gaps)
    }

    /** The one place rank space becomes pixels. */
    private fun toRect(rect: AxisRect, orientation: DtfFlowOrientation, totalAlong: Int): FlowRect {
        val along = if (orientation.isReversed) totalAlong - rect.along - rect.alongSize else rect.along
        return if (orientation.isHorizontal) {
            FlowRect(along, rect.across, rect.alongSize, rect.acrossSize)
        } else {
            FlowRect(rect.across, along, rect.acrossSize, rect.alongSize)
        }
    }

    /** [toRect] for a point, mirrored the same way so that a route still meets its boxes. */
    private fun toPoint(point: AxisPoint, orientation: DtfFlowOrientation, totalAlong: Int): FlowPoint {
        val along = if (orientation.isReversed) totalAlong - point.along else point.along
        return if (orientation.isHorizontal) FlowPoint(along, point.across) else FlowPoint(point.across, along)
    }

    // --- step 5: routes -------------------------------------------------------------------------

    /**
     * An arranged arrow: along the flow out of its source, across on its own track in every gap it
     * has to change lanes in, and along the flow again into its target.
     *
     * Every run is straight and every corner square, so the square style draws exactly this and the
     * curved one leaves and arrives along the flow. Walked in the direction ranking gave the edge and
     * only then turned round for one that had to be reversed - so a loop back still starts at the box
     * it comes from and meets its waypoints in order, rather than doubling back through them.
     */
    private fun channelRoute(
        edge: DtfFlowEdge,
        columns: Columns,
        axis: Map<String, AxisRect>,
        geometry: Geometry,
        reversed: Set<String>,
    ): List<AxisPoint> {
        val back = edge.key in reversed
        val chain = chainOf(edge, columns, reversed)
        val source = axis.getValue(chain.first())
        val target = axis.getValue(chain.last())
        val points = mutableListOf(AxisPoint(source.along + source.alongSize, source.centre))
        for ((from, to) in chain.zipWithNext()) {
            val lane = axis.getValue(from).centre
            val next = axis.getValue(to).centre
            if (lane == next) continue
            val rank = columns.rankOf.getValue(from)
            val track = geometry.trackAlong(rank, geometry.gaps[rank].slotOf(DtfFlowChannels.Hop(from, to, back)) ?: 0)
            points += AxisPoint(track, lane)
            points += AxisPoint(track, next)
        }
        points += AxisPoint(target.along, target.centre)
        val simple = simplified(points)
        return if (back) simple.asReversed() else simple
    }

    /** Without repeated points, and without corners that are no corner - a waypoint passed straight through. */
    private fun simplified(points: List<AxisPoint>): List<AxisPoint> {
        val result = ArrayList<AxisPoint>(points.size)
        for (point in points) {
            if (result.lastOrNull() == point) continue
            if (result.size >= 2 && collinear(result[result.size - 2], result.last(), point)) result.removeAt(result.lastIndex)
            result += point
        }
        return result
    }

    private fun collinear(first: AxisPoint, middle: AxisPoint, last: AxisPoint): Boolean =
        (first.along == middle.along && middle.along == last.along) || (first.across == middle.across && middle.across == last.across)

    /**
     * Where an arrow leaves one box and enters the next.
     *
     * Decided from where the boxes actually are rather than from the orientation, so that an arrow
     * still meets a box on a sensible side after it has been dragged somewhere the arrangement never
     * put it.
     */
    private fun anchorsOf(from: FlowRect, to: FlowRect): Pair<FlowPoint, FlowPoint> {
        val dx = to.centerX - from.centerX
        val dy = to.centerY - from.centerY
        return if (abs(dx) >= abs(dy)) {
            if (dx >= 0) {
                FlowPoint(from.right, from.centerY) to FlowPoint(to.x, to.centerY)
            } else {
                FlowPoint(from.x, from.centerY) to FlowPoint(to.right, to.centerY)
            }
        } else {
            if (dy >= 0) {
                FlowPoint(from.centerX, from.bottom) to FlowPoint(to.centerX, to.y)
            } else {
                FlowPoint(from.centerX, from.y) to FlowPoint(to.centerX, to.bottom)
            }
        }
    }

    /** An arrow to or from a dragged box: out of the facing side, with one elbow halfway. */
    private fun elbowOf(from: FlowRect, to: FlowRect): List<FlowPoint> {
        val (start, end) = anchorsOf(from, to)
        return buildList {
            add(start)
            if (start.x != end.x && start.y != end.y) {
                // A plain diagonal reads as a mistake; an elbow halfway across the gap reads as a
                // connection. Which way it bends follows the side the arrow left from.
                if (abs(end.x - start.x) >= abs(end.y - start.y)) {
                    val middle = start.x + (end.x - start.x) / 2
                    add(FlowPoint(middle, start.y))
                    add(FlowPoint(middle, end.y))
                } else {
                    val middle = start.y + (end.y - start.y) / 2
                    add(FlowPoint(start.x, middle))
                    add(FlowPoint(end.x, middle))
                }
            }
            add(end)
        }
    }

    private fun routeOf(edge: DtfFlowEdge, points: List<FlowPoint>, reversed: Boolean, style: DtfFlowLayoutStyle): FlowEdgeRoute {
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

    private const val CENTRING_ROUNDS = 2

    private const val MAX_JUMP_EDGES = 1000
}
