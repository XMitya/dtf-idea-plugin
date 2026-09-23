package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowHighlight
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode

/**
 * Which part of a diagram one box takes part in.
 *
 * Pure graph walking, asserted on its own so that the canvas tests only have to show that it is
 * painted.
 */
class DtfFlowHighlightTest : UsefulTestCase() {

    /** Upstream and downstream as far as the arrows go - and nothing from a flow alongside. */
    fun testTheWholeChainThroughABoxIsTakenIn() {
        val graph = graph(listOf("x", "a", "b", "c", "p", "q"), edge("x", "a"), edge("a", "b"), edge("b", "c"), edge("p", "q"))

        val highlight = requireNotNull(DtfFlowHighlight.of(graph, "b"))

        assertEquals(setOf("x", "a", "b", "c"), highlight.nodeIds)
        assertEquals(setOf(edge("x", "a").key, edge("a", "b").key, edge("b", "c").key), highlight.edgeKeys)
    }

    /** Something else feeding a later task is that task's upstream, not this box's. */
    fun testASideBranchIntoALaterTaskIsLeftOut() {
        val graph = graph(listOf("a", "b", "c", "y"), edge("a", "b"), edge("b", "c"), edge("y", "c"))

        val highlight = requireNotNull(DtfFlowHighlight.of(graph, "a"))

        assertEquals(setOf("a", "b", "c"), highlight.nodeIds)
        assertFalse(highlight.contains(edge("y", "c")))
    }

    /** An arrow that skips the box asked about is not a path through it, even between two lit boxes. */
    fun testAnArrowBypassingTheBoxIsNotLit() {
        val bypass = edge("s", "b")
        val graph = graph(listOf("s", "a", "b"), edge("s", "a"), edge("a", "b"), bypass)

        val highlight = requireNotNull(DtfFlowHighlight.of(graph, "a"))

        assertEquals(setOf("s", "a", "b"), highlight.nodeIds)
        assertFalse(highlight.contains(bypass))
        assertTrue(highlight.contains(edge("a", "b")))
    }

    /** A loop back, and a task that reschedules itself, end the walk rather than running it forever. */
    fun testCyclesAndSelfLoopsTerminate() {
        val graph = graph(listOf("a", "b"), edge("a", "b"), edge("b", "a"), edge("a", "a"))

        val highlight = requireNotNull(DtfFlowHighlight.of(graph, "a"))

        assertEquals(setOf("a", "b"), highlight.nodeIds)
        assertEquals(3, highlight.edgeKeys.size)
    }

    /** A selection that outlived the graph it was made in highlights nothing. */
    fun testAnUnknownBoxHasNoFlow() {
        assertNull(DtfFlowHighlight.of(graph(listOf("a")), "gone"))
    }

    private fun graph(ids: List<String>, vararg edges: DtfFlowEdge) = DtfFlowGraph("test", ids.map(::node), edges.toList())

    private fun node(id: String): DtfFlowNode =
        DtfFlowTaskNode(id, id.uppercase(), id, id, isJoinTarget = false, isCron = false, target = null)

    private fun edge(from: String, to: String) = DtfFlowEdge(from, to, DtfFlowEdgeKind.SCHEDULE)
}
