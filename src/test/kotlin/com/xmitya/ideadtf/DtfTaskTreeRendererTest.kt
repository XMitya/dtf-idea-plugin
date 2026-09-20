package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.DtfTaskTreeRenderer
import javax.swing.tree.DefaultMutableTreeNode

/** The text and icon of a row, which is the whole of what the panel communicates. */
class DtfTaskTreeRendererTest : DtfFixtureTestCase() {

    private val renderer = DtfTaskTreeRenderer()
    private val tree = Tree()

    /** Task name first, class name after it in grey - the class is context, not the subject. */
    fun testTaskRowLeadsWithTheTaskNameThenTheClass() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val row = render(firstTask())
        assertEquals("HELLO_TASK  HelloTask", row)
        assertSame(DtfIcons.TaskGutter, renderer.icon)
    }

    fun testCronRowCarriesTheClockAndTheExpression() {
        addApplicationYaml("HELLO_TASK", "0 0 1 * * *")
        addJavaTask("HelloTask", "HELLO_TASK")
        val row = render(firstTask())
        assertEquals("HELLO_TASK  HelloTask  0 0 1 * * *", row)
        assertSame(DtfIcons.CronGutter, renderer.icon)
    }

    /** With no TaskDef to read, the class name is already the label and must not be repeated. */
    fun testUnnamedTaskShowsItsClassOnlyOnce() {
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
        assertEquals("OpaqueTask", render(firstTask()))
    }

    fun testModuleRowCountsItsTasks() {
        addJavaTask("HelloTask", "HELLO_TASK")
        addJavaTask("OtherTask", "OTHER_TASK")
        val module = snapshot().modules.single()
        assertEquals("${module.moduleName}  2 tasks", render(module))
    }

    /** Singular rather than "1 tasks": the count is read at a glance, and a wrong plural snags. */
    fun testASingleTaskIsCountedInTheSingular() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val snapshot = snapshot()
        assertEquals("${snapshot.projectName}  1 task", render(snapshot))
    }

    private fun render(userObject: Any): String {
        renderer.getTreeCellRendererComponent(
            tree,
            DefaultMutableTreeNode(userObject),
            false,
            false,
            true,
            0,
            false,
        )
        return renderer.toString()
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

    private fun addApplicationYaml(taskName: String, cron: String) {
        myFixture.addFileToProject(
            "app/src/main/resources/application.yaml",
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  $taskName:
                    cron: $cron
            """.trimIndent(),
        )
    }

    private fun snapshot(): DtfTaskSnapshot = ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
        DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
    }

    private fun firstTask() = snapshot().modules.single().tasks.first()
}
