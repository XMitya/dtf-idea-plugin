package com.xmitya.ideadtf.flow

/**
 * The part of a diagram one box takes part in: everything that leads to it and everything it leads
 * to, with the arrows that walk takes.
 *
 * Only those arrows, not every arrow between two highlighted boxes. A caller that schedules both the
 * first task of a chain and, directly, its last one has that second arrow bypass the box asked
 * about - lighting it up would show a path through the box that does not go through it.
 */
class DtfFlowHighlight(val nodeIds: Set<String>, val edgeKeys: Set<String>) {

    fun contains(edge: DtfFlowEdge): Boolean = edge.key in edgeKeys

    companion object {
        /** Null when there is no such box, which is what a stale selection after a refresh is. */
        fun of(graph: DtfFlowGraph, nodeId: String): DtfFlowHighlight? {
            if (graph.node(nodeId) == null) return null
            val nodes = LinkedHashSet<String>()
            val edges = LinkedHashSet<String>()
            walk(nodeId, graph.edges.groupBy { it.toId }, nodes, edges) { it.fromId }
            walk(nodeId, graph.edges.groupBy { it.fromId }, nodes, edges) { it.toId }
            return DtfFlowHighlight(nodes, edges)
        }

        /** Breadth first along [links], one direction at a time; a cycle ends where it was entered. */
        private fun walk(
            start: String,
            links: Map<String, List<DtfFlowEdge>>,
            nodes: MutableSet<String>,
            edges: MutableSet<String>,
            next: (DtfFlowEdge) -> String,
        ) {
            val seen = hashSetOf(start)
            val queue = ArrayDeque(listOf(start))
            nodes += start
            while (queue.isNotEmpty()) {
                for (edge in links[queue.removeFirst()].orEmpty()) {
                    edges += edge.key
                    val id = next(edge)
                    if (seen.add(id)) {
                        nodes += id
                        queue += id
                    }
                }
            }
        }
    }
}
