package com.xmitya.ideadtf

import com.intellij.testFramework.UsefulTestCase
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode
import com.xmitya.ideadtf.flow.GatewayKind
import com.xmitya.ideadtf.flow.editor.DtfFlowTooltip

/** What hovering a box says - pure, so the wording can be read back without a mouse. */
class DtfFlowTooltipTest : UsefulTestCase() {

    fun testTaskShowsItsQualifiedName() {
        val node = DtfFlowTaskNode("a.b.HelloTask", "HELLO", "HelloTask", "a.b.HelloTask", false, false, null)

        assertEquals("a.b.HelloTask", tooltip(DtfFlowGraph("t", listOf(node), emptyList()), node.id))
    }

    fun testCallerShowsWhereItIs() {
        val node = DtfFlowCallerNode("caller:1", "start", "Caller", "Caller.java:42", null)

        assertEquals("Caller - Caller.java:42", tooltip(DtfFlowGraph("t", listOf(node), emptyList()), node.id))
    }

    fun testTimerShowsItsCron() {
        val node = DtfFlowTimerNode("timer:1", "0 0 1 * * *", null)

        val graph = DtfFlowGraph("t", listOf(node), emptyList())

        assertEquals(DtfBundle.message("dtf.flow.tooltip.cron", "0 0 1 * * *"), tooltip(graph, node.id))
    }

    fun testGatewayCountsItsBranches() {
        val gateway = DtfFlowGatewayNode("join:1", GatewayKind.JOIN, approximate = false, target = null)
        val left = DtfFlowTaskNode("Left", "LEFT", "Left", "Left", false, false, null)
        val graph = DtfFlowGraph(
            "t",
            listOf(gateway, left),
            listOf(DtfFlowEdge("Left", gateway.id, DtfFlowEdgeKind.JOIN_BRANCH)),
        )

        assertEquals(DtfBundle.message("dtf.flow.tooltip.join", 1), tooltip(graph, gateway.id))
    }

    /** A guessed gateway has to say so, or the diagram reads as more certain than it is. */
    fun testApproximateGatewaySaysSo() {
        val gateway = DtfFlowGatewayNode("join:1", GatewayKind.JOIN, approximate = true, target = null)

        val text = tooltip(DtfFlowGraph("t", listOf(gateway), emptyList()), gateway.id)

        assertTrue(text.orEmpty().contains(DtfBundle.message("dtf.flow.tooltip.approximate")))
    }

    fun testUnknownNodeHasNothingToSay() {
        assertNull(tooltip(DtfFlowGraph("t", emptyList(), emptyList()), "nope"))
    }

    private fun tooltip(graph: DtfFlowGraph, id: String) = DtfFlowTooltip.of(graph, id)
}
