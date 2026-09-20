package com.xmitya.ideadtf

import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ReadAction
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup
import com.xmitya.ideadtf.toolwindow.DtfTaskScanService
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel

/**
 * That the panel can be built at all.
 *
 * Everything in its constructor is platform UI plumbing - speed search, the two source-opening
 * handlers, the action toolbar - and a misuse of any of it throws only when the tool window is first
 * opened, which is a place no other test reaches.
 */
class DtfTaskTreePanelTest : DtfFixtureTestCase() {

    fun testPanelIsBuiltWithATreeTopAndBottom() {
        val panel = DtfTaskTreePanel(project)
        assertNotNull(panel.toolbar)
        assertNotNull(panel.content)
        assertTrue(panel.preferredFocusComponent is Tree)
    }

    /** The cheap comparison the panel makes on every show, so it had better be stable. */
    fun testStampIsStableWhileNothingChanges() {
        assertEquals(DtfTaskScanService.stampOf(project), DtfTaskScanService.stampOf(project))
    }

    fun testStampMovesWhenAFileIsAdded() {
        val before = DtfTaskScanService.stampOf(project)
        myFixture.addFileToProject("Whatever.java", "public class Whatever {}")
        assertTrue(DtfTaskScanService.stampOf(project) != before)
    }

    /**
     * With no tasks the root has to get out of the way, or the placeholder never shows and the panel
     * reads as a project row saying "no tasks" - which looks like a bug rather than an answer.
     */
    fun testEmptyProjectShowsThePlaceholderRatherThanALoneRoot() {
        val panel = DtfTaskTreePanel(project)
        panel.show(DtfTaskSnapshot(project.name, emptyList(), stamp = 1L))
        val tree = panel.preferredFocusComponent as Tree
        assertFalse(tree.isRootVisible)
        assertEquals(DtfBundle.message("dtf.toolwindow.empty"), tree.emptyText.text)
    }

    /** A single module is the common case and is opened, so the panel is useful on sight. */
    fun testTheOnlyModuleIsExpanded() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = DtfTaskTreePanel(project)
        val snapshot = snapshot()
        panel.show(snapshot)
        val tree = panel.preferredFocusComponent as Tree
        assertTrue(tree.isRootVisible)
        assertEquals(1, tree.model.getChildCount(tree.model.root))
        // Root, the module, and the task under it: two rows would mean it stayed shut.
        assertEquals(3, tree.rowCount)
    }

    /** Many modules stay shut: 55 expanded at once is a wall, not an overview. */
    fun testSeveralModulesStayCollapsed() {
        val panel = DtfTaskTreePanel(project)
        panel.show(twoModules())
        val tree = panel.preferredFocusComponent as Tree
        assertEquals(2, tree.model.getChildCount(tree.model.root))
        assertEquals(3, tree.rowCount) // root plus two collapsed modules
    }

    private fun addJavaTask(className: String, taskName: String) {
        myFixture.addFileToProject(
            "$className.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class $className implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("$taskName", String.class); }
            }
            """.trimIndent(),
        )
    }

    private fun snapshot(): DtfTaskSnapshot = ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
        DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
    }

    /** The toolbar the Project view taught people to expect. */
    fun testToolbarOffersRefreshExpandAndCollapse() {
        val group = DtfTaskTreePanel(project).getActions(true).single() as DefaultActionGroup
        assertEquals(
            listOf("Refresh", "Expand All", "Collapse All"),
            group.getChildren(null).mapNotNull { it.templatePresentation.text },
        )
    }

    /**
     * And that they have something to act on. A tree whose modules were all leaves, or one whose
     * root was collapsed away, would leave both buttons permanently greyed out.
     */
    fun testTheTreeCanBeExpandedAndCollapsed() {
        val panel = DtfTaskTreePanel(project)
        panel.show(twoModules())
        val tree = panel.preferredFocusComponent as Tree
        val expander = DefaultTreeExpander(tree)

        assertEquals(3, tree.rowCount) // root plus two collapsed modules
        assertTrue(expander.canExpand())
        assertTrue(expander.canCollapse())

        expander.expandAll()
        assertEquals(5, tree.rowCount)

        expander.collapseAll()
        assertTrue("collapse left the tree expanded", tree.rowCount < 5)
    }

    private fun twoModules(): DtfTaskSnapshot {
        addJavaTask("HelloTask", "HELLO_TASK")
        addJavaTask("OtherTask", "OTHER_TASK")
        val tasks = snapshot().modules.single().tasks
        return DtfTaskSnapshot(
            project.name,
            listOf(
                DtfTaskModuleGroup("alpha", listOf(tasks.first())),
                DtfTaskModuleGroup("beta", listOf(tasks.last())),
            ),
            stamp = 1L,
        )
    }
}
