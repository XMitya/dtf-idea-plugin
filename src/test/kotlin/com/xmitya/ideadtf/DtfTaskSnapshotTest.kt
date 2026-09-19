package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder

/** What each row of the tool window ends up saying, and where it points. */
class DtfTaskSnapshotTest : DtfFixtureTestCase() {

    /** The row leads with the TaskDef's name, which is rarely the class name. */
    fun testRowIsNamedByTheTaskDefNotTheClass() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val entry = single()
        assertEquals("HELLO_TASK", entry.taskName)
        assertEquals("HelloTask", entry.className)
        assertEquals("HELLO_TASK", entry.displayName)
    }

    /** A task whose definition cannot be read still belongs in the list, under its class name. */
    fun testTaskWithUnreadableDefinitionFallsBackToTheClassName() {
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
        val entry = single()
        assertNull(entry.taskName)
        assertEquals("OpaqueTask", entry.displayName)
    }

    fun testAnnotatedTaskCarriesTheClockAndItsExpression() {
        myFixture.addFileToProject(
            "HelloTask.java",
            """
            import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            @TaskSchedule(cron = "0 0/10 * ? * *")
            public class HelloTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("HELLO_TASK", String.class); }
            }
            """.trimIndent(),
        )
        val entry = single()
        assertTrue(entry.isCron)
        assertEquals("0 0/10 * ? * *", entry.cronExpression)
    }

    fun testYamlConfiguredTaskCarriesTheClockAndItsExpression() {
        addApplicationYaml("HELLO_TASK", "0 0 1 * * *")
        addJavaTask("HelloTask", "HELLO_TASK")
        val entry = single()
        assertTrue(entry.isCron)
        assertEquals("0 0 1 * * *", entry.cronExpression)
    }

    /** Same order of precedence as the gutter: the file the framework reads last wins. */
    fun testConfigurationOutranksTheAnnotation() {
        addApplicationYaml("HELLO_TASK", "0 0 1 * * *")
        myFixture.addFileToProject(
            "HelloTask.java",
            """
            import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            @TaskSchedule(cron = "0 0/10 * ? * *")
            public class HelloTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("HELLO_TASK", String.class); }
            }
            """.trimIndent(),
        )
        assertEquals("0 0 1 * * *", single().cronExpression)
    }

    /** A blank cron disables the schedule, so it must not put a clock on the row either. */
    fun testBlankCronLeavesThePlainIcon() {
        addApplicationYaml("HELLO_TASK", "")
        addJavaTask("HelloTask", "HELLO_TASK")
        val entry = single()
        assertFalse(entry.isCron)
        assertNull(entry.cronExpression)
    }

    fun testPlainTaskIsNotCron() {
        addJavaTask("HelloTask", "HELLO_TASK")
        assertFalse(single().isCron)
    }

    /** Sorted by what the row shows, not by class name - otherwise the order looks arbitrary. */
    fun testRowsAreSortedByTaskName() {
        addJavaTask("ZebraTask", "ALPHA_TASK")
        addJavaTask("AlphaTask", "ZEBRA_TASK")
        assertEquals(listOf("ALPHA_TASK", "ZEBRA_TASK"), entries().map { it.displayName })
    }

    fun testTasksAreGroupedUnderTheirModule() {
        addJavaTask("HelloTask", "HELLO_TASK")
        val snapshot = snapshot()
        assertEquals(1, snapshot.modules.size)
        assertNotNull(snapshot.modules.single().moduleName)
        assertEquals(1, snapshot.taskCount)
    }

    /**
     * The Kotlin case is the one that catches a light-class slip: navigating to the light class
     * lands nowhere, so the anchor has to be the `KtClass`.
     */
    fun testKotlinTaskNavigatesIntoItsOwnSource() {
        myFixture.addFileToProject(
            "ScanFileTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class ScanFileTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("SCAN_FILE", String::class.java)
            }
            """.trimIndent(),
        )
        val entry = single()
        assertTrue(entry.canNavigate())
        entry.navigate(false)
        assertEquals(
            "ScanFileTask.kt",
            FileEditorManager.getInstance(project).selectedFiles.single().name,
        )
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

    private fun snapshot(): DtfTaskSnapshot =
        ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
            DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
        }

    private fun entries(): List<DtfTaskEntry> = snapshot().modules.flatMap { it.tasks }

    private fun single(): DtfTaskEntry = entries().single()
}
