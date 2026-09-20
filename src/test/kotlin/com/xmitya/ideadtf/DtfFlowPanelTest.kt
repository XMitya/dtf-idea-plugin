package com.xmitya.ideadtf

import com.intellij.openapi.util.Disposer
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.editor.DtfFlowPanel

/**
 * What the tab shows around the diagram.
 *
 * Three states, and each of them is the only thing the user sees when it happens: still building,
 * nothing found, and "this is only part of the chain".
 */
class DtfFlowPanelTest : DtfFlowFixtureTestCase() {

    fun testPanelIsBuiltWithAToolbarAndContent() {
        val panel = panel()

        assertNotNull(panel.toolbar)
        assertNotNull(panel.content)
        assertNotNull(panel.preferredFocusComponent)
        Disposer.dispose(panel)
    }

    fun testAGraphIsHandedToTheCanvas() {
        addJavaTask("HelloTask", "HELLO")
        val panel = panel()
        val graph = buildFlowOf("HelloTask")

        panel.show(graph)

        assertEquals(graph.nodes.size, panel.flowCanvas().currentGraph().nodes.size)
        Disposer.dispose(panel)
    }

    /** Nothing found has to say so; an empty canvas looks like a bug. */
    fun testAnEmptyGraphShowsThePlaceholder() {
        val panel = panel()

        panel.show(DtfFlowGraph.empty("nothing"))

        assertTrue(panel.flowCanvas().currentGraph().isEmpty)
        Disposer.dispose(panel)
    }

    /** A capped walk must not pass part of a chain off as all of it. */
    fun testATruncatedGraphIsAnnounced() {
        addJavaTask("HelloTask", "HELLO")
        val panel = panel()
        val complete = buildFlowOf("HelloTask")

        panel.show(DtfFlowGraph(complete.title, complete.nodes, complete.edges, truncated = true))

        assertEquals(complete.nodes.size, panel.flowCanvas().currentGraph().nodes.size)
        Disposer.dispose(panel)
    }

    /** Refreshing rebuilds; disposing has to leave no build running behind it. */
    fun testRefreshAndDisposeAreSafe() {
        addJavaTask("HelloTask", "HELLO")
        val panel = panel()

        panel.refresh()
        Disposer.dispose(panel)
    }

    private fun panel() = DtfFlowPanel(project, DtfFlowScope.Task("HelloTask", "HELLO"))
}
