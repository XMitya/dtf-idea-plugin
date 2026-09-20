package com.xmitya.ideadtf

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.xmitya.ideadtf.marker.CronConfigNavigationHandler

/** Which classes get the clock instead of the T. */
class DtfCronGutterTest : DtfFixtureTestCase() {

    fun testAnnotatedTaskGetsTheCronIcon() {
        myFixture.configureByText("HelloTask.java", annotatedTask("0 0/10 * ? * *"))
        assertEquals(1, cronGutters().size)
    }

    fun testTaskConfiguredInYamlGetsTheCronIcon() {
        addApplicationYaml("0 0 1 * * *")
        myFixture.configureByText("HelloTask.java", plainTask())
        assertEquals(1, cronGutters().size)
    }

    /** The clock replaces the T rather than joining it. */
    fun testCronIconReplacesTheTaskIcon() {
        addApplicationYaml("0 0 1 * * *")
        myFixture.configureByText("HelloTask.java", plainTask())
        assertEmpty(taskGutters())
    }

    fun testPlainTaskKeepsTheTaskIcon() {
        myFixture.configureByText("HelloTask.java", plainTask())
        assertEmpty(cronGutters())
        assertEquals(1, taskGutters().size)
    }

    /** A blank cron disables the schedule, so it must not put a clock on anything. */
    fun testBlankCronAloneIsNotACronTask() {
        addApplicationYaml("")
        myFixture.configureByText("HelloTask.java", plainTask())
        assertEmpty(cronGutters())
        assertEquals(1, taskGutters().size)
    }

    /** The task name is the string in the TaskDef, never the class name. */
    fun testConfigurationUnderADifferentNameIsNotMatched() {
        addApplicationYaml("0 0 1 * * *", taskName = "SOMETHING_ELSE")
        myFixture.configureByText("HelloTask.java", plainTask())
        assertEmpty(cronGutters())
    }

    fun testKotlinCronTaskIsMarkedOnce() {
        addApplicationYaml("0 0 1 * * *", taskName = "SCAN_FILE")
        myFixture.configureByText(
            "ScanFileTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class ScanFileTask : Task<String> {
                override fun getDef(): TaskDef<String> = SCAN_FILE

                companion object {
                    val SCAN_FILE: TaskDef<String> = TaskDef.privateTaskDef("SCAN_FILE", String::class.java)
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, cronGutters().size)
    }

    fun testTwoCronTasksInOneKotlinFileAreBothMarked() {
        addApplicationYaml("0 0 1 * * *", taskName = "SPECIALIST")
        addApplicationYaml("0 0 2 * * *", taskName = "JUDGE", path = "app/src/main/resources/application-local.yaml")
        myFixture.configureByText(
            "LlmModerationWorkerTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class SpecialistTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("SPECIALIST", String::class.java)
            }

            class JudgeTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("JUDGE", String::class.java)
            }
            """.trimIndent(),
        )
        assertEquals(2, cronGutters().size)
    }

    fun testMarkerIsWiredToTheCronHandler() {
        myFixture.configureByText("HelloTask.java", annotatedTask("0 0/10 * ? * *"))
        val marker = cronMarker()
        assertTrue(marker.navigationHandler is CronConfigNavigationHandler)
    }

    fun testTooltipNamesTheCronExpression() {
        myFixture.configureByText("HelloTask.java", annotatedTask("0 0/10 * ? * *"))
        assertEquals(
            DtfBundle.message("dtf.gutter.cron.tooltip.expression", "HELLO_TASK", "0 0/10 * ? * *"),
            cronMarker().lineMarkerTooltip,
        )
    }

    /** Configuration wins over the annotation, so that is the schedule the tooltip should show. */
    fun testConfigurationOutranksTheAnnotationInTheTooltip() {
        addApplicationYaml("0 0 3 * * *")
        myFixture.configureByText("HelloTask.java", annotatedTask("0 0/10 * ? * *"))
        assertEquals(
            DtfBundle.message("dtf.gutter.cron.tooltip.expression", "HELLO_TASK", "0 0 3 * * *"),
            cronMarker().lineMarkerTooltip,
        )
    }

    /** No TaskDef name to look up, but the annotation alone is enough to know it is scheduled. */
    fun testAnnotatedTaskWithUnreadableNameIsStillMarked() {
        myFixture.configureByText(
            "MysteryTask.java",
            """
            import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            @TaskSchedule(cron = "0 0 1 * * *")
            public class MysteryTask implements Task<String> {
                private final TaskDef<String> def;

                public MysteryTask(TaskDef<String> def) { this.def = def; }

                @Override
                public TaskDef<String> getDef() { return def; }
            }
            """.trimIndent(),
        )
        assertEquals(1, cronGutters().size)
    }

    private fun plainTask() =
        """
        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        public class HelloTask implements Task<String> {
            public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("HELLO_TASK", String.class);

            @Override
            public TaskDef<String> getDef() { return HELLO; }
        }
        """.trimIndent()

    private fun annotatedTask(cron: String) =
        """
        import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;
        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        @TaskSchedule(cron = "$cron")
        public class HelloTask implements Task<String> {
            public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("HELLO_TASK", String.class);

            @Override
            public TaskDef<String> getDef() { return HELLO; }
        }
        """.trimIndent()

    private fun addApplicationYaml(
        cron: String,
        taskName: String = "HELLO_TASK",
        path: String = "app/src/main/resources/application.yaml",
    ) {
        myFixture.addFileToProject(
            path,
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  $taskName:
                    cron: $cron
            """.trimIndent(),
        )
    }

    private fun cronMarker(): LineMarkerInfo<*> = myFixture.findAllGutters()
        .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
        .single { it.icon === DtfIcons.CronGutter }
        .lineMarkerInfo

    private fun cronGutters(): List<GutterMark> = myFixture.findAllGutters().filter { it.icon === DtfIcons.CronGutter }

    private fun taskGutters(): List<GutterMark> = myFixture.findAllGutters().filter { it.icon === DtfIcons.TaskGutter }
}
