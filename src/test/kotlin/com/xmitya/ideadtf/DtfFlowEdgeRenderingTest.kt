package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.editor.DtfFlowEdgePainter
import com.xmitya.ideadtf.flow.editor.DtfFlowEdgeStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowSize
import java.awt.geom.PathIterator

/**
 * Telling a crossing from a connection.
 *
 * Two arrows that merely pass over each other look exactly like two that meet, which is the one
 * thing a flow diagram must never be ambiguous about. A small bridge at the crossing settles it.
 */
class DtfFlowEdgeRenderingTest : UsefulTestCase() {

    private val style = DtfFlowLayoutStyle()
    private val boxSize = FlowSize(100, 40)

    /**
     * Arrows that genuinely cross get exactly one bridge between them, not two.
     *
     * Forced by pinning the boxes into an X, because the arrangement's own crossing reduction is
     * rather good at removing the ones it is responsible for - the crossings that survive are the
     * ones the reader made by dragging a box, which is exactly this shape.
     */
    fun testCrossingArrowsGetOneBridge() {
        val graph = graph(listOf("a", "b", "c", "d"), edge("a", "b"), edge("c", "d"))
        // One arrow straight across, the other straight down through it.
        val pinned = mapOf(
            "a" to FlowPoint(0, 100),
            "b" to FlowPoint(400, 100),
            "c" to FlowPoint(200, 0),
            "d" to FlowPoint(200, 250),
        )

        val routes = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style, pinned = pinned).edges

        assertEquals("exactly one of the pair carries the hop: " + routes.map { it.points }, 1, routes.count { it.hops.isNotEmpty() })
        assertEquals(FlowPoint(250, 120), routes.first { it.hops.isNotEmpty() }.hops.single())
    }

    /** Arrows leaving the same box share that point; that is a fan-out, not a crossing. */
    fun testAFanOutIsNotACrossing() {
        val graph = graph(listOf("root", "a", "b", "c"), edge("root", "a"), edge("root", "b"), edge("root", "c"))

        assertTrue(layout(graph).edges.all { it.hops.isEmpty() })
    }

    fun testAPlainChainHasNothingToHopOver() {
        val graph = graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c"))

        assertTrue(layout(graph).edges.all { it.hops.isEmpty() })
    }

    /** A bridge is an arc, so the painted shape gains curve segments a plain polyline has not. */
    fun testABridgeAddsAnArcToTheShape() {
        val points = listOf(FlowPoint(0, 50), FlowPoint(100, 50))

        val plain = segmentKinds(DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, emptyList(), 4))
        val hopped = segmentKinds(DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, listOf(FlowPoint(50, 50)), 4))

        assertFalse(plain.contains(PathIterator.SEG_CUBICTO))
        assertTrue("the hop must bend the line", hopped.contains(PathIterator.SEG_CUBICTO))
    }

    /** The bridge goes over the crossing, never through it. */
    fun testABridgeBulgesClearOfTheLine() {
        val points = listOf(FlowPoint(0, 50), FlowPoint(100, 50))

        val bounds = DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, listOf(FlowPoint(50, 50)), 4).bounds

        assertTrue("expected the arc above the line, got $bounds", bounds.y < 50)
    }

    /** Several crossings on one run each get their own bridge, in the order they are met. */
    fun testEveryCrossingOnARunGetsItsOwnBridge() {
        val points = listOf(FlowPoint(0, 50), FlowPoint(200, 50))
        val hops = listOf(FlowPoint(150, 50), FlowPoint(50, 50))

        val kinds = segmentKinds(DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, hops, 4))

        assertEquals(2, kinds.count { it == PathIterator.SEG_CUBICTO } / ARCS_PER_SEMICIRCLE)
    }

    /** Arrows side by side crossed at once hop once: a second bridge on top of the first would be a smudge. */
    fun testCoincidentHopsDrawOneBridge() {
        val points = listOf(FlowPoint(0, 50), FlowPoint(200, 50))
        val hops = listOf(FlowPoint(100, 50), FlowPoint(100, 50), FlowPoint(102, 50))

        val kinds = segmentKinds(DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, hops, 4))

        assertEquals(1, kinds.count { it == PathIterator.SEG_CUBICTO } / ARCS_PER_SEMICIRCLE)
    }

    /** A hop that belongs to another arrow's run must not bend this one. */
    fun testAHopOffTheLineIsIgnored() {
        val points = listOf(FlowPoint(0, 50), FlowPoint(100, 50))

        val kinds = segmentKinds(DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.ORTHOGONAL, listOf(FlowPoint(50, 300)), 4))

        assertFalse(kinds.contains(PathIterator.SEG_CUBICTO))
    }

    fun testCurvedArrowsAreOneSweepRatherThanCorners() {
        val elbow = listOf(FlowPoint(0, 0), FlowPoint(50, 0), FlowPoint(50, 100), FlowPoint(100, 100))

        val square = segmentKinds(DtfFlowEdgePainter.pathOf(elbow, DtfFlowEdgeStyle.ORTHOGONAL, emptyList(), 4))
        val curved = segmentKinds(DtfFlowEdgePainter.pathOf(elbow, DtfFlowEdgeStyle.CURVED, emptyList(), 4))

        assertEquals(listOf(PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO, PathIterator.SEG_LINETO, PathIterator.SEG_LINETO), square)
        assertEquals(listOf(PathIterator.SEG_MOVETO, PathIterator.SEG_CUBICTO), curved)
    }

    /** A curve still has to start and finish exactly on the boxes it connects. */
    fun testACurveKeepsItsEnds() {
        val points = listOf(FlowPoint(10, 20), FlowPoint(60, 20), FlowPoint(110, 90))

        val path = DtfFlowEdgePainter.pathOf(points, DtfFlowEdgeStyle.CURVED, emptyList(), 4)

        assertEquals(10.0, path.currentPoint.let { path.bounds2D.minX })
        val end = endOf(path)
        assertEquals(110, end.x)
        assertEquals(90, end.y)
    }

    fun testAnArrowWithNowhereToGoDrawsNothing() {
        assertTrue(
            DtfFlowEdgePainter.pathOf(listOf(FlowPoint(1, 1)), DtfFlowEdgeStyle.ORTHOGONAL, emptyList(), 4).getPathIterator(null).isDone,
        )
    }

    private fun endOf(path: java.awt.geom.Path2D.Double): FlowPoint {
        val point = requireNotNull(path.currentPoint)
        return FlowPoint(point.x.toInt(), point.y.toInt())
    }

    private fun segmentKinds(path: java.awt.geom.Path2D.Double): List<Int> {
        val kinds = mutableListOf<Int>()
        val iterator = path.getPathIterator(null)
        val coordinates = DoubleArray(6)
        while (!iterator.isDone) {
            kinds += iterator.currentSegment(coordinates)
            iterator.next()
        }
        return kinds
    }

    private fun layout(graph: DtfFlowGraph) = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style)

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String) = DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE)

    private companion object {
        /** Java2D approximates a half-circle with two cubics. */
        const val ARCS_PER_SEMICIRCLE = 2
    }
}
