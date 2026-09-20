package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowRect
import com.xmitya.ideadtf.flow.layout.FlowSize

/**
 * The arrangement, asserted rather than eyeballed.
 *
 * The layout is pure - it is handed sizes and returns coordinates - so none of this needs a project,
 * a toolkit or a fixture, which is what makes it worth asserting to the pixel.
 */
class DtfFlowLayoutTest : UsefulTestCase() {

    private val style = DtfFlowLayoutStyle()
    private val boxSize = FlowSize(100, 40)

    /** A chain reads left to right, one column per hop. */
    fun testChainIsLaidOutInColumns() {
        val layout = layout(graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c")))

        val xs = listOf("a", "b", "c").map { layout.nodes.getValue(it).x }
        assertEquals(listOf(style.padding, style.padding + 100 + style.rankGap, style.padding + 2 * (100 + style.rankGap)), xs)
    }

    /** Two starting points are both starting points, so they share the first column. */
    fun testTwoEntryPointsShareTheFirstColumn() {
        val layout = layout(graph(listOf("a", "b", "target"), edge("a", "target"), edge("b", "target")))

        assertEquals(layout.nodes.getValue("a").x, layout.nodes.getValue("b").x)
        assertTrue(layout.nodes.getValue("target").x > layout.nodes.getValue("a").x)
    }

    /** Boxes in one column never sit on top of one another. */
    fun testBoxesInAColumnDoNotOverlap() {
        val layout = layout(graph(listOf("a", "b", "c", "target"), edge("a", "target"), edge("b", "target"), edge("c", "target")))

        val column = listOf("a", "b", "c").map { layout.nodes.getValue(it) }.sortedBy { it.y }
        column.zipWithNext().forEach { (upper, lower) ->
            assertTrue("$upper overlaps $lower", lower.y >= upper.bottom + style.nodeGap)
        }
    }

    /** A flow that loops back has to terminate, and every box still gets a place. */
    fun testCycleIsBrokenAndEveryNodeIsPlaced() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b"), edge("b", "a")))

        assertEquals(setOf("a", "b"), layout.nodes.keys)
        assertEquals(1, layout.edges.count { it.reversed })
    }

    /** An arrow that skips a column is routed around what is in between, not through it. */
    fun testEdgeSpanningTwoColumnsGetsWaypoints() {
        val layout = layout(graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c"), edge("a", "c")))

        val long = layout.edges.single { it.edge.fromId == "a" && it.edge.toId == "c" }
        assertTrue("expected waypoints, got ${long.points}", long.points.size > 2)
    }

    /** A task that reschedules itself gets a loop of its own rather than a second box. */
    fun testSelfLoopIsRoutedAroundItsOwnBox() {
        val layout = layout(graph(listOf("a"), edge("a", "a")))

        val loop = layout.edges.single()
        assertEquals(5, loop.points.size)
        assertFalse(loop.reversed)
        assertEquals(1, layout.nodes.size)
    }

    /** The slot the next version paints message types into is already measured. */
    fun testLabelSlotIsReservedOnlyForAnEdgeThatHasSomethingToSay() {
        val plain = layout(graph(listOf("a", "b"), edge("a", "b")))
        assertNull(plain.edges.single().labelSlot)

        val labelled = layout(graph(listOf("a", "b"), edge("a", "b", messageType = "OrderDto")))
        val route = labelled.edges.single()
        val slot = requireNotNull(route.labelSlot)
        assertEquals(style.labelSlot.width, slot.width)
        assertEquals(route.labelAnchor.x, slot.x + slot.width / 2)
    }

    /** The anchor sits on the arrow, which is where a label has to hang from. */
    fun testLabelAnchorIsOnTheMiddleSegment() {
        val route = layout(graph(listOf("a", "b"), edge("a", "b"))).edges.single()

        val (from, to) = route.points.first() to route.points.last()
        assertTrue(route.labelAnchor.x in from.x..to.x)
    }

    /** Clicking a box has to find it, and clicking the gap between boxes has to find nothing. */
    fun testHitTestingFindsABoxAndMissesTheGap() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b")))

        val box = layout.nodes.getValue("a")
        assertEquals("a", layout.nodeAt(FlowPoint(box.centerX, box.centerY)))
        assertNull(layout.nodeAt(FlowPoint(box.right + style.rankGap / 2, box.centerY - 1000)))
    }

    /** Arrows are hit-testable too, which is what a click-through on one will need. */
    fun testEdgeHitTestingRespectsItsTolerance() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b")))

        val route = layout.edges.single()
        val onTheLine = route.points.first()
        assertNotNull(layout.edgeAt(onTheLine, tolerance = 4))
        assertNull(layout.edgeAt(FlowPoint(onTheLine.x, onTheLine.y + 500), tolerance = 4))
    }

    /** The same graph laid out twice is the same picture; nothing here may depend on iteration order. */
    fun testLayoutIsDeterministic() {
        val input = graph(listOf("a", "b", "c", "target"), edge("a", "target"), edge("b", "target"), edge("c", "target"))

        assertEquals(layout(input).nodes, layout(input).nodes)
    }

    fun testEmptyGraphHasNothingToArrange() {
        val layout = layout(DtfFlowGraph("empty", emptyList(), emptyList()))

        assertTrue(layout.nodes.isEmpty())
        assertEquals(FlowSize.EMPTY, layout.size)
    }

    /** The canvas asks the layout how big it has to be, so the answer has to cover everything. */
    fun testSizeCoversEveryBox() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b")))

        val furthest: FlowRect = layout.nodes.values.maxBy { it.right }
        assertEquals(furthest.right + style.padding, layout.size.width)
    }

    /**
     * A self-reschedule loops over the top of its box. With everything in one row there is nothing
     * above it, so without room reserved the loop is drawn off the canvas and simply vanishes.
     */
    fun testASelfLoopInTheFirstRowIsNotClipped() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b"), edge("a", "a")))

        val loop = layout.edges.single { it.edge.isSelfLoop }
        assertTrue("the loop is above the canvas: ${loop.points}", loop.points.all { it.y >= 0 })
        assertTrue("every box must still be on the canvas", layout.nodes.values.all { it.y >= 0 })
    }

    /** The room is reserved once, from the arrangement, so laying out again does not creep downwards. */
    fun testHeadroomIsStableAcrossRepeatedLayouts() {
        val input = graph(listOf("a", "b"), edge("a", "b"), edge("a", "a"))

        assertEquals(layout(input).nodes, layout(input).nodes)
    }

    /** No self-loop, no reserved room: an ordinary chain still starts at the padding. */
    fun testAChainWithoutLoopsKeepsItsPadding() {
        val layout = layout(graph(listOf("a", "b"), edge("a", "b")))

        assertEquals(style.padding, layout.nodes.values.minOf { it.y })
    }

    private fun layout(graph: DtfFlowGraph) = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style)

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String, messageType: String? = null) =
        DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE, messageType = messageType)
}
