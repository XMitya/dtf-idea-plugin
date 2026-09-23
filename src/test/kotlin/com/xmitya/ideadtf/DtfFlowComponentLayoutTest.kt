package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.layout.DtfFlowLayout
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation
import com.xmitya.ideadtf.flow.layout.DtfFlowPacking
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowRect
import com.xmitya.ideadtf.flow.layout.FlowSize
import kotlin.math.abs

/**
 * A diagram of many flows, and a flow with many callers.
 *
 * What a module-wide diagram needs to be readable: each flow kept together and tiled rather than
 * merged into one set of columns, and each box placed opposite the boxes it is actually connected
 * to rather than the middle of the rank next to it.
 */
class DtfFlowComponentLayoutTest : UsefulTestCase() {

    private val style = DtfFlowLayoutStyle()
    private val boxSize = FlowSize(100, 40)

    /** Two flows sharing nothing get a region each, and every arrow stays inside its own. */
    fun testIndependentFlowsDoNotOverlap() {
        val layout = layout(twoFanIns())

        val first = extentOf(layout, setOf("c1", "c2", "t1"))
        val second = extentOf(layout, setOf("c3", "c4", "t2"))
        assertFalse("$first overlaps $second", first.intersects(second))
    }

    fun testCallersStayBesideTheirOwnTask() {
        val layout = layout(twoFanIns())

        mapOf("c1" to "t1", "c2" to "t1", "c3" to "t2", "c4" to "t2").forEach { (caller, own) ->
            val other = if (own == "t1") "t2" else "t1"
            assertTrue(
                "$caller is nearer $other than its own $own",
                distance(layout, caller, own) < distance(layout, caller, other),
            )
        }
    }

    /**
     * The shape of a module: many small flows. Tiled into rows towards a screen's proportions, not
     * stacked into a strip as tall as all of them together.
     */
    fun testManyFlowsAreTiledRatherThanStacked() {
        val ids = (1..12).flatMap { listOf("a$it", "b$it") }
        val edges = (1..12).map { edge("a$it", "b$it") }.toTypedArray()

        val layout = layout(graph(ids, *edges))

        val heads = (1..12).map { layout.nodes.getValue("a$it").x }.toSet()
        assertTrue("every flow starts in one column: $heads", heads.size >= 2)
        assertTrue("stacked rather than tiled: ${layout.size}", layout.size.height < 12 * boxSize.height + 11 * style.nodeGap)
    }

    /** A flow looks the same whatever else is on the diagram: its arrangement is its own. */
    fun testAFlowLaysOutTheSameWhateverElseIsDrawn() {
        val alone = layout(graph(listOf("a", "b", "c"), edge("a", "b"), edge("a", "c")))
        val together = layout(graph(listOf("a", "b", "c", "x", "y", "z"), edge("a", "b"), edge("a", "c"), edge("x", "y"), edge("x", "z")))

        assertEquals(offsetsWithin(alone, "a", "b", "c"), offsetsWithin(together, "a", "b", "c"))
    }

    /** A self-reschedule's loop is part of its flow's extent, so the next flow is tiled clear of it. */
    fun testASelfLoopKeepsClearOfTheNextFlow() {
        val layout = layout(graph(listOf("a", "x", "y"), edge("a", "a"), edge("x", "y")))

        val loop = layout.edges.single { it.edge.isSelfLoop }.points
        val boxes = listOf("x", "y").map { layout.nodes.getValue(it) }
        loop.forEach { point -> assertTrue("$point lands on another flow", boxes.none { it.contains(point) }) }
        assertTrue(loop.all { it.x >= 0 && it.y >= 0 })
    }

    /**
     * Two tasks, each with callers of its own, feeding one next task. Each task has to end up in
     * the middle of its own callers - not both squeezed in front of the task they share, and not all
     * of them wherever the middle of the neighbouring column happens to be.
     */
    fun testCentringFollowsConnectionsNotTheWholeRank() {
        val callers1 = listOf("c1", "c2")
        val callers2 = listOf("c3", "c4", "c5", "c6")
        val edges = callers1.map { edge(it, "t1") } + callers2.map { edge(it, "t2") } + listOf(edge("t1", "z"), edge("t2", "z"))

        val layout = layout(graph(callers1 + callers2 + listOf("t1", "t2", "z"), *edges.toTypedArray()))

        assertWithinSpan(layout, "t1", callers1)
        assertWithinSpan(layout, "t2", callers2)
        assertWithinSpan(layout, "z", listOf("t1", "t2"))
    }

    /** A caller of a task deep in a chain sits in the column just before it, not among the first callers. */
    fun testASourceSitsNextToWhatItCalls() {
        val layout = layout(graph(listOf("a", "b", "c", "x"), edge("a", "b"), edge("b", "c"), edge("x", "c")))

        assertEquals(layout.nodes.getValue("b").x, layout.nodes.getValue("x").x)
    }

    /** Packed, mirrored and looped: whatever the direction, everything is on the canvas. */
    fun testEveryOrientationCoversAPackedPicture() {
        val graph = graph(
            listOf("a", "b", "c", "x", "y", "p"),
            edge("a", "b"),
            edge("b", "c"),
            edge("a", "c"),
            edge("c", "a"),
            edge("b", "b"),
            edge("x", "y"),
            edge("p", "p"),
        )

        for (orientation in DtfFlowOrientation.entries) {
            val layout = layout(graph, orientation)
            val points = layout.nodes.values.flatMap { listOf(FlowPoint(it.x, it.y), FlowPoint(it.right, it.bottom)) } +
                layout.edges.flatMap { it.points }
            assertTrue(
                "$orientation leaves something off the canvas",
                points.all { it.x in 0..layout.size.width && it.y in 0..layout.size.height },
            )
        }
    }

    /** Mirroring a flow must not lose the padding on the side it now starts from. */
    fun testRightToLeftKeepsItsPadding() {
        val layout = layout(graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c")), DtfFlowOrientation.RIGHT_TO_LEFT)

        assertEquals(style.padding, layout.nodes.values.minOf { it.x })
        assertEquals(layout.size.width - style.padding, layout.nodes.values.maxOf { it.right })
    }

    fun testOneFlowIsPlacedAtTheOrigin() {
        assertEquals(listOf(FlowPoint(0, 0)), DtfFlowPacking.shelvesOf(listOf(FlowSize(300, 90)), 10, 1.6))
    }

    /** Tiled flows never overlap, and a row is only wider than the target when one flow alone is. */
    fun testShelvesDoNotOverlapAndKeepToTheTargetWidth() {
        val sizes = listOf(FlowSize(300, 60), FlowSize(200, 120), FlowSize(250, 40), FlowSize(120, 40), FlowSize(500, 80))

        val corners = DtfFlowPacking.shelvesOf(sizes, 10, 1.6)

        val rects = sizes.indices.map { FlowRect(corners[it].x, corners[it].y, sizes[it].width, sizes[it].height) }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                assertFalse("${rects[i]} overlaps ${rects[j]}", rects[i].intersects(rects[j]))
            }
        }
        assertEquals("rows start at the left", 0, corners.minOf { it.x })
        assertTrue("more than one row", corners.map { it.y }.toSet().size > 1)
    }

    /** Bottom to top stacks the rows upwards: the first row is the lowest. */
    fun testBottomToTopStacksRowsUpwards() {
        val sizes = List(6) { FlowSize(300, 60) }

        val corners = DtfFlowPacking.packed(sizes, style, DtfFlowOrientation.BOTTOM_TO_TOP)

        assertEquals(corners.maxOf { it.y }, corners.first().y)
    }

    private fun twoFanIns() = graph(
        listOf("c1", "c2", "t1", "c3", "c4", "t2"),
        edge("c1", "t1"),
        edge("c2", "t1"),
        edge("c3", "t2"),
        edge("c4", "t2"),
    )

    /** Everything drawn for these boxes: the boxes, and the arrows leaving them. */
    private fun extentOf(layout: DtfFlowLayout, ids: Set<String>): FlowRect {
        val corners = ids.map { layout.nodes.getValue(it) }.flatMap { listOf(FlowPoint(it.x, it.y), FlowPoint(it.right, it.bottom)) }
        return FlowRect.around(corners + layout.edges.filter { it.edge.fromId in ids }.flatMap { it.points })
    }

    private fun distance(layout: DtfFlowLayout, from: String, to: String): Int {
        val a = layout.nodes.getValue(from)
        val b = layout.nodes.getValue(to)
        return abs(a.centerY - b.centerY)
    }

    private fun offsetsWithin(layout: DtfFlowLayout, origin: String, vararg others: String): List<FlowPoint> {
        val base = layout.nodes.getValue(origin)
        return others.map { layout.nodes.getValue(it).let { rect -> FlowPoint(rect.x - base.x, rect.y - base.y) } }
    }

    private fun assertWithinSpan(layout: DtfFlowLayout, id: String, around: List<String>) {
        val centre = layout.nodes.getValue(id).centerY
        val centres = around.map { layout.nodes.getValue(it).centerY }
        assertTrue("$id at $centre is outside ${around.zip(centres)}", centre in centres.min()..centres.max())
    }

    private fun layout(graph: DtfFlowGraph, orientation: DtfFlowOrientation = DtfFlowOrientation.LEFT_TO_RIGHT) =
        DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style, orientation)

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String) = DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE)
}
