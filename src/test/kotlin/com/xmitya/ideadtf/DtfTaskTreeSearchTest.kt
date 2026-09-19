package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.ui.SpeedSearchBase
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel
import com.xmitya.ideadtf.toolwindow.DtfTaskTreeSearchText
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * What typing into the panel matches.
 *
 * The default `TreeSpeedSearch` matches `toString()` of the node object, which here reads
 * `com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup@1f2e3d`. That makes every row match a "c" and no
 * row match "ca" - the cursor walks rows that visibly do not match, then the search goes red on the
 * second character. These assertions are all about that never coming back.
 */
class DtfTaskTreeSearchTest : DtfFixtureTestCase() {

    /** The reported case, verbatim: typing "ca" has to find the callback-processing module. */
    fun testModuleIsFoundByPartOfItsName() {
        val text = searchTextOf(DtfTaskModuleGroup("rustore-mono.callback-processing.main", emptyList()))
        assertNotNull(text)
        assertTrue(text!!.contains("ca"))
        assertEquals("rustore-mono.callback-processing.main", text)
    }

    /** The bug itself: anything derived from the object's identity matches nearly every query. */
    fun testSearchTextNeverLeaksTheNodeClassName() {
        val texts = listOf(
            searchTextOf(DtfTaskModuleGroup("app", emptyList())),
            searchTextOf(DtfTaskSnapshot("proj", emptyList(), stamp = 1L)),
            searchTextOf(firstTaskAfterAdding("HelloTask", "HELLO_TASK")),
        )
        for (text in texts) {
            assertNotNull(text)
            assertFalse("leaked the node class: $text", text!!.contains("com.xmitya"))
            assertFalse("leaked an identity hash: $text", text.contains("@"))
        }
    }

    /** Either half of the row finds the task: people remember one or the other, rarely both. */
    fun testTaskIsFoundByItsDefinitionOrByItsClass() {
        val text = searchTextOf(firstTaskAfterAdding("HelloTask", "HELLO_TASK"))
        assertEquals("HELLO_TASK HelloTask", text)
    }

    /** With no TaskDef the class name is the label, and repeating it would double every match. */
    fun testUnnamedTaskIsSearchedByItsClassOnce() {
        myFixture.addFileToProject(
            "OpaqueTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class OpaqueTask implements Task<String> {
                private final TaskDef<String> def;

                public OpaqueTask(TaskDef<String> def) { this.def = def; }

                @Override
                public TaskDef<String> getDef() { return def; }
            }
            """.trimIndent(),
        )
        assertEquals("OpaqueTask", searchTextOf(snapshot().modules.single().tasks.single()))
    }

    fun testProjectRowIsSearchedByItsName() {
        assertEquals("proj", searchTextOf(DtfTaskSnapshot("proj", emptyList(), stamp = 1L)))
    }

    fun testUnknownNodeIsNotSearchable() {
        assertNull(searchTextOf("something else"))
    }

    /**
     * That the panel actually installs the above, which is the half that broke. `getElementText` is
     * protected, so it is reached by reflection - the alternative is asserting the function in
     * isolation and shipping a tree still wired to the default.
     */
    fun testPanelSpeedSearchUsesTheRowTextAndSearchesCollapsedModules() {
        addJavaTask("HelloTask", "HELLO_TASK")
        addJavaTask("OtherTask", "OTHER_TASK")
        val tasks = snapshot().modules.single().tasks
        val panel = DtfTaskTreePanel(project)
        // Two modules, so they stay collapsed and their tasks are off screen.
        panel.show(
            DtfTaskSnapshot(
                "proj",
                listOf(
                    DtfTaskModuleGroup("callback-processing", listOf(tasks.first())),
                    DtfTaskModuleGroup("coupon-service", listOf(tasks.last())),
                ),
                stamp = 1L,
            ),
        )
        val tree = panel.preferredFocusComponent as Tree

        // The two-arg form: the one-arg one hands back the supply only while its popup is up.
        val supply = SpeedSearchSupply.getSupply(tree, true) as SpeedSearchBase<*>
        val modulePath = TreePath(arrayOf(tree.model.root, tree.model.getChild(tree.model.root, 0)))
        assertEquals("callback-processing", elementTextOf(supply, modulePath))

        // Collapsed modules have to be searchable, or a task is unfindable until you open its module.
        assertEquals(3, tree.rowCount)
        assertEquals(5, allPaths(supply).size)
    }

    private fun elementTextOf(supply: SpeedSearchBase<*>, path: TreePath): String? =
        SpeedSearchBase::class.java.getDeclaredMethod("getElementText", Any::class.java)
            .apply { isAccessible = true }
            .invoke(supply, path) as String?

    private fun allPaths(supply: SpeedSearchBase<*>): List<*> =
        com.intellij.ui.TreeSpeedSearch::class.java.getDeclaredMethod("allPaths")
            .apply { isAccessible = true }
            .invoke(supply)
            .let { (it as com.intellij.util.containers.JBIterable<*>).toList() }

    private fun searchTextOf(userObject: Any): String? =
        DtfTaskTreeSearchText.of(TreePath(DefaultMutableTreeNode(userObject)))

    private fun firstTaskAfterAdding(className: String, taskName: String): Any {
        addJavaTask(className, taskName)
        return snapshot().modules.single().tasks.single()
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

    private fun snapshot(): DtfTaskSnapshot =
        ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
            DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
        }
}
