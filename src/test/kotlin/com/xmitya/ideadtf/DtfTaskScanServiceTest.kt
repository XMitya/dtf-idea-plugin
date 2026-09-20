package com.xmitya.ideadtf

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.toolwindow.DtfTaskScanService
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The asynchronous half of the tool window: the scan the panel triggers, and the stamp bookkeeping
 * that decides whether to trigger one at all.
 *
 * [DtfTaskTreePanelTest] hands the panel a finished snapshot and checks what it draws. Here the
 * snapshot comes from the service instead, so the background-progress hop and the hand-back to the
 * EDT are exercised too - the half that fails with a threading assertion rather than a wrong tree.
 */
class DtfTaskScanServiceTest : DtfFixtureTestCase() {

    fun testScanFindsTheProjectsTasksAndAnswersOnTheEdt() {
        addTask("HelloTask", "HELLO_TASK")
        addTask("ByeTask", "BYE_TASK")

        var answeredOn: Boolean? = null
        val snapshot = scan { answeredOn = SwingUtilities.isEventDispatchThread() }

        // The callback repaints the tree, so answering anywhere else is a threading violation that
        // only shows up as an intermittent assertion in a running IDE.
        assertEquals(true, answeredOn)
        assertEquals(
            listOf("BYE_TASK", "HELLO_TASK"),
            snapshot.modules.flatMap { module -> module.tasks.map { it.displayName } }.sorted(),
        )
    }

    /** The stamp travels with the snapshot, so the panel can tell what it is showing. */
    fun testSnapshotCarriesTheStampItWasTakenAt() {
        addTask("HelloTask", "HELLO_TASK")
        assertEquals(DtfTaskScanService.stampOf(project), scan().stamp)
    }

    /**
     * Going back and forth between the editor and the panel must not re-search the project each
     * time, which is the whole point of comparing stamps before scanning.
     */
    fun testASecondShowWithNothingChangedDoesNotRescan() {
        addTask("HelloTask", "HELLO_TASK")
        val panel = refreshedPanel()

        panel.refreshIfStale()

        // `refresh` puts the progress text back before anything else, so the placeholder still
        // reading "no tasks" is the visible proof that no second search was started.
        assertEquals(DtfBundle.message("dtf.toolwindow.empty"), tree(panel).emptyText.text)
    }

    fun testAnEditedProjectIsRescannedOnTheNextShow() {
        val panel = refreshedPanel()
        assertEquals(0, tree(panel).rowCount) // nothing to find yet

        addTask("HelloTask", "HELLO_TASK")
        panel.refreshIfStale()
        assertEquals(DtfBundle.message("dtf.toolwindow.progress"), tree(panel).emptyText.text)

        waitForScan(panel)
        assertEquals(3, tree(panel).rowCount) // root, module, one task
    }

    /** The toolbar button people reach for when they know the tree is behind. */
    fun testTheRefreshButtonScans() {
        addTask("HelloTask", "HELLO_TASK")
        val panel = DtfTaskTreePanel(project)
        val refresh = (panel.getActions(true).single() as DefaultActionGroup).getChildren(null).first()

        refresh.actionPerformed(TestActionEvent.createTestEvent(refresh))

        waitForScan(panel)
        assertEquals(3, tree(panel).rowCount)
    }

    /** Enter on a selected row opens it, and that goes through the data context, not the node. */
    fun testSelectedTasksAreOfferedAsNavigationTargets() {
        addTask("HelloTask", "HELLO_TASK")
        val panel = refreshedPanel()
        val tree = tree(panel)
        assertNull("nothing is selected yet", navigatablesOf(panel))

        val root = tree.model.root as DefaultMutableTreeNode
        val module = root.getChildAt(0) as DefaultMutableTreeNode
        tree.selectionPath = TreePath(arrayOf(root, module, module.getChildAt(0)))

        assertEquals(1, navigatablesOf(panel)?.size)
    }

    private fun refreshedPanel(): DtfTaskTreePanel {
        val panel = DtfTaskTreePanel(project)
        panel.refresh()
        waitForScan(panel)
        return panel
    }

    private fun scan(onResult: () -> Unit = {}): DtfTaskSnapshot {
        var result: DtfTaskSnapshot? = null
        DtfTaskScanService.getInstance(project).scan {
            onResult()
            result = it
        }
        PlatformTestUtil.waitWithEventsDispatching("the scan never finished", { result != null }, TIMEOUT_SECONDS)
        return requireNotNull(result)
    }

    /** A finished scan is one that has put the placeholder text back. */
    private fun waitForScan(panel: DtfTaskTreePanel) = PlatformTestUtil.waitWithEventsDispatching(
        "the scan never finished",
        { tree(panel).emptyText.text == DtfBundle.message("dtf.toolwindow.empty") },
        TIMEOUT_SECONDS,
    )

    private fun tree(panel: DtfTaskTreePanel) = panel.preferredFocusComponent as Tree

    private fun navigatablesOf(panel: DtfTaskTreePanel) =
        RecordingDataSink().also { panel.uiDataSnapshot(it) }[CommonDataKeys.NAVIGATABLE_ARRAY]

    private fun addTask(className: String, taskName: String) {
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

    private companion object {
        const val TIMEOUT_SECONDS = 30
    }
}
