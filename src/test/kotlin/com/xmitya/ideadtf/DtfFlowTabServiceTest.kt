package com.xmitya.ideadtf

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.editor.DtfFlowFileEditor
import com.xmitya.ideadtf.flow.editor.DtfFlowTabService
import com.xmitya.ideadtf.flow.editor.DtfFlowVirtualFile

/**
 * Opening diagrams: the background build, and the rule that one subject means one tab.
 *
 * [DtfFlowGraphBuilderTest] checks what the graph contains; here the interest is the hop off the EDT
 * and back, and the registry that keeps a second invocation from opening a second copy.
 */
class DtfFlowTabServiceTest : DtfFlowFixtureTestCase() {

    fun testBuildAnswersWithTheGraphOnTheEdt() {
        addJavaTask("HelloTask", "HELLO")

        val graph = buildInBackground(DtfFlowScope.Task("HelloTask", "HELLO"))

        assertEquals(listOf("HELLO"), graph.nodes.map { (it as com.xmitya.ideadtf.flow.DtfFlowTaskNode).displayName })
    }

    fun testShowOpensOneTab() {
        addJavaTask("HelloTask", "HELLO")

        val file = service().show(DtfFlowScope.Task("HelloTask", "HELLO"))

        assertEquals(1, openFlowFiles().size)
        assertEquals(DtfBundle.message("dtf.flow.tab.title", "HELLO"), file.name)
        assertTrue(FileEditorManager.getInstance(project).getEditors(file).any { it is DtfFlowFileEditor })
    }

    /** Asking for the same diagram again brings the tab forward rather than opening another. */
    fun testAskingTwiceReusesTheSameTab() {
        addJavaTask("HelloTask", "HELLO")
        val scope = DtfFlowScope.Task("HelloTask", "HELLO")

        val first = service().show(scope)
        val second = service().show(DtfFlowScope.Task("HelloTask", "HELLO"))

        assertSame(first, second)
        assertEquals(1, openFlowFiles().size)
    }

    fun testADifferentSubjectOpensItsOwnTab() {
        addJavaTask("HelloTask", "HELLO")
        addJavaTask("ByeTask", "BYE")

        service().show(DtfFlowScope.Task("HelloTask", "HELLO"))
        service().show(DtfFlowScope.Task("ByeTask", "BYE"))

        assertEquals(2, openFlowFiles().size)
    }

    /** Closing the tab has to forget it, or reopening would focus a tab that is no longer there. */
    fun testClosingTheTabDropsItFromTheRegistry() {
        addJavaTask("HelloTask", "HELLO")
        val scope = DtfFlowScope.Task("HelloTask", "HELLO")
        val first = service().show(scope)

        FileEditorManager.getInstance(project).closeFile(first)
        val second = service().show(scope)

        assertNotSame(first, second)
    }

    private fun service() = DtfFlowTabService.getInstance(project)

    private fun openFlowFiles() = FileEditorManager.getInstance(project).openFiles.filterIsInstance<DtfFlowVirtualFile>()

    private fun buildInBackground(scope: DtfFlowScope): DtfFlowGraph {
        var result: DtfFlowGraph? = null
        service().build(scope) { result = it }
        PlatformTestUtil.waitWithEventsDispatching("the flow was never built", { result != null }, TIMEOUT_SECONDS)
        return requireNotNull(result)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60
    }
}
