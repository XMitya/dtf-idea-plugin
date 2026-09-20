package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowSize

/**
 * Reading the same flow in four directions, and moving a box by hand.
 *
 * A layered arrangement cannot untangle every graph — some crossings belong to the flow rather than
 * to the algorithm — so these two are what the reader is given instead. Both are pure geometry, and
 * asserted as such.
 */
class DtfFlowOrientationTest : UsefulTestCase() {

    private val style = DtfFlowLayoutStyle()
    private val boxSize = FlowSize(100, 40)

    /** A chain reads along the direction it was asked for, in each of the four. */
    fun testChainProgressesAlongTheChosenAxis() {
        val chain = graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c"))

        val leftToRight = layout(chain, DtfFlowOrientation.LEFT_TO_RIGHT).nodes
        assertTrue(leftToRight.getValue("a").x < leftToRight.getValue("b").x)
        assertTrue(leftToRight.getValue("b").x < leftToRight.getValue("c").x)

        val rightToLeft = layout(chain, DtfFlowOrientation.RIGHT_TO_LEFT).nodes
        assertTrue(rightToLeft.getValue("a").x > rightToLeft.getValue("b").x)
        assertTrue(rightToLeft.getValue("b").x > rightToLeft.getValue("c").x)

        val topToBottom = layout(chain, DtfFlowOrientation.TOP_TO_BOTTOM).nodes
        assertTrue(topToBottom.getValue("a").y < topToBottom.getValue("b").y)
        assertTrue(topToBottom.getValue("b").y < topToBottom.getValue("c").y)

        val bottomToTop = layout(chain, DtfFlowOrientation.BOTTOM_TO_TOP).nodes
        assertTrue(bottomToTop.getValue("a").y > bottomToTop.getValue("b").y)
        assertTrue(bottomToTop.getValue("b").y > bottomToTop.getValue("c").y)
    }

    /** Turning the flow must not stack two boxes of one rank on top of each other. */
    fun testARankStillSeparatesWhenTheFlowRunsDownwards() {
        val fanOut = graph(listOf("root", "a", "b", "c"), edge("root", "a"), edge("root", "b"), edge("root", "c"))

        val nodes = layout(fanOut, DtfFlowOrientation.TOP_TO_BOTTOM).nodes
        val rank = listOf("a", "b", "c").map { nodes.getValue(it) }.sortedBy { it.x }

        rank.zipWithNext().forEach { (left, right) ->
            assertTrue("$left overlaps $right", right.x >= left.right + style.nodeGap)
        }
    }

    fun testEveryOrientationCoversTheWholePicture() {
        val chain = graph(listOf("a", "b", "c"), edge("a", "b"), edge("b", "c"))

        for (orientation in DtfFlowOrientation.entries) {
            val layout = layout(chain, orientation)
            assertTrue(
                "$orientation clips the diagram",
                layout.nodes.values.all { it.right <= layout.size.width && it.bottom <= layout.size.height },
            )
            assertTrue("$orientation puts a box off the canvas", layout.nodes.values.all { it.x >= 0 && it.y >= 0 })
        }
    }

    /** A box the reader has dragged stays where it was put, and the rest arranges around it. */
    fun testAPinnedBoxKeepsItsPlace() {
        val chain = graph(listOf("a", "b"), edge("a", "b"))
        val moved = FlowPoint(500, 300)

        val layout = DtfFlowLayouter.layout(
            chain,
            chain.nodes.associate { it.id to boxSize },
            style,
            DtfFlowOrientation.LEFT_TO_RIGHT,
            mapOf("b" to moved),
        )

        assertEquals(moved.x, layout.nodes.getValue("b").x)
        assertEquals(moved.y, layout.nodes.getValue("b").y)
        assertEquals(style.padding, layout.nodes.getValue("a").x)
    }

    /** The arrow has to follow the box, and meet it on the side it now faces. */
    fun testAnArrowFollowsAMovedBox() {
        val chain = graph(listOf("a", "b"), edge("a", "b"))

        val below = DtfFlowLayouter.layout(
            chain,
            chain.nodes.associate { it.id to boxSize },
            style,
            DtfFlowOrientation.LEFT_TO_RIGHT,
            mapOf("b" to FlowPoint(0, 400)),
        )

        val route = below.edges.single()
        val target = below.nodes.getValue("b")
        // Straight down rather than across, so the arrow enters the top edge.
        assertEquals(target.y, route.points.last().y)
        assertEquals(target.centerX, route.points.last().x)
    }

    /** The picture has to grow to hold a box dragged past its edge, or it would be clipped. */
    fun testTheCanvasGrowsToHoldAMovedBox() {
        val chain = graph(listOf("a", "b"), edge("a", "b"))

        val layout = DtfFlowLayouter.layout(
            chain,
            chain.nodes.associate { it.id to boxSize },
            style,
            DtfFlowOrientation.LEFT_TO_RIGHT,
            mapOf("b" to FlowPoint(900, 700)),
        )

        assertTrue(layout.size.width >= 900 + boxSize.width)
        assertTrue(layout.size.height >= 700 + boxSize.height)
    }

    private fun layout(graph: DtfFlowGraph, orientation: DtfFlowOrientation) =
        DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to boxSize }, style, orientation)

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String) = DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE)
}
