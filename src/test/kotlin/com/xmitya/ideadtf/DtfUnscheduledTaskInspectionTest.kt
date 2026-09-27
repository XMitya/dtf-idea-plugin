package com.xmitya.ideadtf

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.xmitya.ideadtf.inspection.DtfUnscheduledTaskInspection
import org.jetbrains.jps.model.java.JavaSourceRootType

/**
 * The warning on a task nothing starts: which launches count, which do not, and what it says.
 *
 * Each case opens the task's own file and reads back only this inspection's highlights, so neither
 * a Kotlin file's other diagnostics nor the order files were added in can get in the way.
 */
class DtfUnscheduledTaskInspectionTest : DtfFixtureTestCase() {

    /** A test source root, registered by URL: the first file written into it creates it. */
    private val withTestRoot = object : ProjectDescriptor(LanguageLevel.JDK_21_PREVIEW) {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            contentEntry.addSourceFolder("${contentEntry.url}/tst", JavaSourceRootType.TEST_SOURCE)
        }
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = withTestRoot

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(DtfUnscheduledTaskInspection())
    }

    fun testTaskNobodySchedulesIsReportedOnItsName() {
        addTask("HelloTask.java", "HelloTask", "HELLO")
        assertEquals(listOf("""HelloTask: DTF task "HELLO" is never scheduled"""), warningsIn("HelloTask.java"))
    }

    fun testTaskScheduledFromProductionCodeIsNotReported() {
        addTask("HelloTask.java", "HelloTask", "HELLO")
        addCaller("Caller.java", "Caller", "schedule(HelloTask.DEF, ExecutionContext.simple(\"x\"))")
        assertEmpty(warningsIn("HelloTask.java"))
    }

    fun testTaskScheduledOnlyFromTestsIsReportedAsSuch() {
        addTask("HelloTask.java", "HelloTask", "HELLO")
        addCaller("tst/CallerTest.java", "CallerTest", "schedule(HelloTask.DEF, ExecutionContext.simple(\"x\"))")
        assertEquals(listOf("""HelloTask: DTF task "HELLO" is scheduled only from tests"""), warningsIn("HelloTask.java"))
    }

    /** A fixture task is meant to be scheduled from tests and from nowhere else. */
    fun testTaskInTestSourcesScheduledFromTestsIsNotReported() {
        addTask("tst/FixtureTask.java", "FixtureTask", "FIXTURE")
        addCaller("tst/CallerTest.java", "CallerTest", "schedule(FixtureTask.DEF, ExecutionContext.simple(\"x\"))")
        assertEmpty(warningsIn("tst/FixtureTask.java"))
    }

    /** Rescheduling needs the task running already, so on its own it starts nothing. */
    fun testTaskThatOnlyReschedulesItselfIsReported() {
        myFixture.addFileToProject(
            "SelfTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public class SelfTask implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("SELF", String.class);

                private DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> ctx) throws Exception {
                    distributedTaskService.schedule(getDef(), ctx);
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("""SelfTask: DTF task "SELF" is never scheduled"""), warningsIn("SelfTask.java"))
    }

    /** The base's `schedule(message)` body is how others launch the task, not a launch in itself. */
    fun testSchedulingHelperNobodyCallsIsNotALaunch() {
        addSchedulableTask()
        assertEquals(listOf("""SendTask: DTF task "SEND" is never scheduled"""), warningsIn("SendTask.java"))
    }

    fun testSchedulingHelperCalledFromOutsideIsALaunch() {
        addSchedulableTask()
        myFixture.addFileToProject(
            "Consumer.java",
            """
            public class Consumer {
                private SendTask sendTask;

                public void consume() throws Exception {
                    sendTask.schedule("payload");
                }
            }
            """.trimIndent(),
        )
        assertEmpty(warningsIn("SendTask.java"))
    }

    /** Only abstract bases are the task's own code: a concrete parent is a task of its own. */
    fun testConcreteParentSchedulingItsSubclassIsALaunch() {
        myFixture.addFileToProject(
            "ParentTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public class ParentTask implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("PARENT", String.class);

                protected DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> ctx) throws Exception {
                    distributedTaskService.schedule(ChildTask.DEF, ctx);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "ChildTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class ChildTask extends ParentTask {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("CHILD", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }
            }
            """.trimIndent(),
        )
        assertEmpty(warningsIn("ChildTask.java"))
    }

    fun testCronTaskIsNotReported() {
        myFixture.addFileToProject(
            "CleanupTask.java",
            """
            import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            @TaskSchedule(cron = "0 0 * * * *")
            public class CleanupTask implements Task<Void> {
                public static final TaskDef<Void> DEF = TaskDef.privateTaskDef("CLEANUP");

                @Override
                public TaskDef<Void> getDef() { return DEF; }
            }
            """.trimIndent(),
        )
        assertEmpty(warningsIn("CleanupTask.java"))
    }

    fun testJoinTaskScheduledByScheduleJoinIsNotReported() {
        addTask("JoinTask.java", "JoinTask", "JOIN")
        addCaller("Caller.java", "Caller", "scheduleJoin(JoinTask.DEF, ExecutionContext.simple(\"x\"), java.util.List.of())")
        assertEmpty(warningsIn("JoinTask.java"))
    }

    fun testAbstractTaskIsNotReported() {
        myFixture.addFileToProject(
            "AbstractTask.java",
            """
            import com.distributed_task_framework.task.Task;

            public abstract class AbstractTask<T> implements Task<T> {}
            """.trimIndent(),
        )
        assertEmpty(warningsIn("AbstractTask.java"))
    }

    /** Without a readable `TaskDef` the class is the only name there is. */
    fun testTaskWithoutReadableNameIsReportedByItsClass() {
        myFixture.addFileToProject(
            "NamelessTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class NamelessTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return null; }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("""NamelessTask: DTF task "NamelessTask" is never scheduled"""), warningsIn("NamelessTask.java"))
    }

    /** The answer is cached, and a caller written in another file still has to clear it. */
    fun testWarningGoesAwayOnceSomethingSchedulesTheTask() {
        addTask("HelloTask.java", "HelloTask", "HELLO")
        assertSize(1, warningsIn("HelloTask.java"))

        addCaller("Caller.java", "Caller", "schedule(HelloTask.DEF, ExecutionContext.simple(\"x\"))")
        assertEmpty(warningsIn("HelloTask.java"))
    }

    fun testKotlinTaskNobodySchedulesIsReported() {
        addKotlinTask()
        assertEquals(listOf("""ScanFileTask: DTF task "SCAN_FILE" is never scheduled"""), warningsIn("ScanFileTask.kt"))
    }

    fun testKotlinTaskScheduledFromKotlinIsNotReported() {
        addKotlinTask()
        myFixture.addFileToProject(
            "Scheduler.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.service.DistributedTaskService

            class Scheduler(private val distributedTaskService: DistributedTaskService) {
                fun run() {
                    distributedTaskService.schedule(ScanFileTask.SCAN_FILE, ExecutionContext.simple("x"))
                }
            }
            """.trimIndent(),
        )
        assertEmpty(warningsIn("ScanFileTask.kt"))
    }

    /** This inspection's warnings in [path], as `highlighted text: description`. */
    private fun warningsIn(path: String): List<String> {
        myFixture.configureFromTempProjectFile(path)
        return myFixture.doHighlighting(HighlightSeverity.WARNING)
            .filter { it.inspectionToolId == INSPECTION_ID }
            .map { "${it.text}: ${it.description}" }
    }

    private fun addTask(path: String, className: String, taskName: String) {
        myFixture.addFileToProject(
            path,
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class $className implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("$taskName", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }
            }
            """.trimIndent(),
        )
    }

    private fun addCaller(path: String, className: String, call: String) {
        myFixture.addFileToProject(
            path,
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class $className {
                private DistributedTaskService distributedTaskService;

                public void createTask() throws Exception {
                    distributedTaskService.$call;
                }
            }
            """.trimIndent(),
        )
    }

    /** A task launched through its base's own `schedule(message)`, with no `TaskDef` at the call. */
    private fun addSchedulableTask() {
        myFixture.addFileToProject(
            "SchedulableTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskId;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public abstract class SchedulableTask<T> implements Task<T> {
                private DistributedTaskService distributedTaskService;

                public TaskId schedule(T message) throws Exception {
                    return distributedTaskService.schedule(getDef(), ExecutionContext.simple(message));
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "SendTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class SendTask extends SchedulableTask<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("SEND", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }
            }
            """.trimIndent(),
        )
    }

    private fun addKotlinTask() {
        myFixture.addFileToProject(
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
    }

    private companion object {
        const val INSPECTION_ID = "DtfUnscheduledTask"
    }
}
