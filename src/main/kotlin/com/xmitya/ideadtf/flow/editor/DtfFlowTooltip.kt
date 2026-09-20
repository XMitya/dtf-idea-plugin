package com.xmitya.ideadtf.flow.editor

import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode

/**
 * What hovering a box says.
 *
 * Pure, so that the wording can be asserted without a mouse or a toolkit - the same reason the tree's
 * row text lives outside its renderer.
 */
object DtfFlowTooltip {

    fun of(graph: DtfFlowGraph, nodeId: String): String? = when (val node = graph.node(nodeId)) {
        is DtfFlowTaskNode -> node.qualifiedName
        is DtfFlowCallerNode -> listOfNotNull(node.className, node.location).joinToString(" - ").ifEmpty { null }
        is DtfFlowTimerNode -> node.expression?.let { DtfBundle.message("dtf.flow.tooltip.cron", it) }
        is DtfFlowGatewayNode -> gatewayText(graph, node)
        null -> null
    }

    private fun gatewayText(graph: DtfFlowGraph, node: DtfFlowGatewayNode): String {
        val branches = graph.edges.count { it.toId == node.id && it.kind == DtfFlowEdgeKind.JOIN_BRANCH }
        val joins = DtfBundle.message("dtf.flow.tooltip.join", branches)
        return if (node.approximate) joins + " - " + DtfBundle.message("dtf.flow.tooltip.approximate") else joins
    }
}
