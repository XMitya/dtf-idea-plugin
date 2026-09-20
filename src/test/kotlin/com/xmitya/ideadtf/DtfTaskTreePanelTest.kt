package com.xmitya.ideadtf

import com.intellij.ide.CopyProvider
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.PopupHandler
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.action.DtfFlowDataKeys
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskDataKeys
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry
import com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup
import com.xmitya.ideadtf.toolwindow.DtfTaskScanService
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel
import java.awt.datatransfer.DataFlavor
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

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

    /** The tree answers the flow action's question about what is selected. */
    fun testASelectedTaskRowIsOfferedAsAFlowSubject() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        val tree = panel.preferredFocusComponent as Tree
        selectRow(tree) { it is DtfTaskEntry }

        val sink = RecordingDataSink().also { panel.uiDataSnapshot(it) }
        val scope = sink[DtfFlowDataKeys.FLOW_SCOPE]

        assertEquals(DtfFlowScope.Task("HelloTask", "HELLO_TASK"), scope)
    }

    fun testASelectedModuleRowIsOfferedAsAFlowSubject() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        val tree = panel.preferredFocusComponent as Tree
        selectRow(tree) { it is DtfTaskModuleGroup }

        val sink = RecordingDataSink().also { panel.uiDataSnapshot(it) }
        val scope = sink[DtfFlowDataKeys.FLOW_SCOPE]

        assertEquals(DtfFlowScope.Module(module.name, module.name), scope)
    }

    /** Two selected rows are two diagrams; guessing which one was meant is worse than offering none. */
    fun testAMultipleSelectionOffersNoFlowSubject() {
        addJavaTask("HelloTask", "HELLO_TASK")
        addJavaTask("ByeTask", "BYE_TASK")
        val panel = shownPanel()
        val tree = panel.preferredFocusComponent as Tree
        tree.selectionModel.selectionPaths = pathsOf(tree) { it is DtfTaskEntry }.toTypedArray()

        val sink = RecordingDataSink().also { panel.uiDataSnapshot(it) }

        assertNull(sink[DtfFlowDataKeys.FLOW_SCOPE])
    }

    /** Right-click has to offer something; without the handler the tree has no menu at all. */
    fun testTheTreeHasAContextMenu() {
        val tree = DtfTaskTreePanel(project).preferredFocusComponent as Tree

        assertTrue(tree.mouseListeners.any { it is PopupHandler })
    }

    private fun shownPanel(): DtfTaskTreePanel = DtfTaskTreePanel(project).also { it.show(snapshot()) }

    private fun selectRow(tree: Tree, matches: (Any?) -> Boolean) {
        tree.selectionModel.selectionPath = pathsOf(tree, matches).firstOrNull()
    }

    /**
     * Walks the model rather than the view: the panel builds a plain `DefaultTreeModel`, so every
     * row exists whether or not it has been expanded, and nothing has to be waited for.
     */
    private fun pathsOf(tree: Tree, matches: (Any?) -> Boolean): List<TreePath> {
        val found = mutableListOf<TreePath>()
        fun walk(node: DefaultMutableTreeNode) {
            if (matches(node.userObject)) found += TreePath(node.path)
            for (index in 0 until node.childCount) walk(node.getChildAt(index) as DefaultMutableTreeNode)
        }
        walk(tree.model.root as DefaultMutableTreeNode)
        return found
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

    /**
     * The bug as reported: Cmd+C used to put `DtfTaskEntry@55207c29` on the clipboard.
     *
     * Nothing here offered a `CopyProvider`, so the platform's Copy stayed disabled and the
     * keystroke fell through to Swing's tree handler, which copies `toString()` of the user object.
     */
    fun testCopyingATaskRowGivesItsTaskNameRatherThanItsToString() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        selectRow(panel.preferredFocusComponent as Tree) { it is DtfTaskEntry }

        assertEquals("HELLO_TASK", copiedFrom(panel))
    }

    /** Declining on the other rows would leave that same hash code on them. */
    fun testCopyingAModuleRowGivesTheModuleName() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        selectRow(panel.preferredFocusComponent as Tree) { it is DtfTaskModuleGroup }

        assertEquals(module.name, copiedFrom(panel))
    }

    fun testCopyingTheProjectRowGivesTheProjectName() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        selectRow(panel.preferredFocusComponent as Tree) { it is DtfTaskSnapshot }

        assertEquals(project.name, copiedFrom(panel))
    }

    fun testCopyingSeveralRowsGivesALinePerRow() {
        addJavaTask("HelloTask", "HELLO_TASK")
        addJavaTask("ByeTask", "BYE_TASK")
        val panel = shownPanel()
        val tree = panel.preferredFocusComponent as Tree
        tree.selectionModel.selectionPaths = pathsOf(tree) { it is DtfTaskEntry }.toTypedArray()

        assertEquals(setOf("HELLO_TASK", "BYE_TASK"), copiedFrom(panel)?.split("\n")?.toSet())
    }

    /** Offered but declining, so that Copy does not claim a selection it has nothing to say about. */
    fun testCopyIsOfferedAndDeclinesWithNothingSelected() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()

        assertFalse(copyProviderOf(panel).isCopyEnabled(DataContext.EMPTY_CONTEXT))
    }

    /** A tree not filled in yet: its root stands for nothing, so there is nothing to copy either. */
    fun testCopyingAnUnfilledTreeCopiesNothing() {
        val panel = DtfTaskTreePanel(project)
        val tree = panel.preferredFocusComponent as Tree
        tree.selectionModel.selectionPath = TreePath(tree.model.root)

        assertNull(copiedFrom(panel))
    }

    /** The rows are read on the EDT into the snapshot, so the copy itself need not go back there. */
    fun testTheCopyProviderRunsOffTheEventThread() {
        assertEquals(ActionUpdateThread.BGT, copyProviderOf(DtfTaskTreePanel(project)).actionUpdateThread)
    }

    /** What the tree's own menu entries act on. */
    fun testASelectedTaskRowIsOfferedToTheMenuActions() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        selectRow(panel.preferredFocusComponent as Tree) { it is DtfTaskEntry }

        val entries = RecordingDataSink().also { panel.uiDataSnapshot(it) }[DtfTaskDataKeys.TASK_ENTRIES]

        assertEquals(listOf("HELLO_TASK"), entries?.map { it.taskName })
    }

    /** A module row is not a task, and the entries that act on one must not be offered there. */
    fun testAModuleRowOffersNoTaskToTheMenuActions() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val panel = shownPanel()
        selectRow(panel.preferredFocusComponent as Tree) { it is DtfTaskModuleGroup }

        assertNull(RecordingDataSink().also { panel.uiDataSnapshot(it) }[DtfTaskDataKeys.TASK_ENTRIES])
    }

    private fun copyProviderOf(panel: DtfTaskTreePanel): CopyProvider =
        requireNotNull(RecordingDataSink().also { panel.uiDataSnapshot(it) }[PlatformDataKeys.COPY_PROVIDER])

    /** Null when the provider declines, so that "copied nothing" and "copied a blank" stay apart. */
    private fun copiedFrom(panel: DtfTaskTreePanel): String? {
        val provider = copyProviderOf(panel)
        if (!provider.isCopyEnabled(DataContext.EMPTY_CONTEXT)) return null
        provider.performCopy(DataContext.EMPTY_CONTEXT)
        return CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
    }
}
