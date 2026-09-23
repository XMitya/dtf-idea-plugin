package com.xmitya.ideadtf

/**
 * The shared `commons-dtf` bases that give a task its own `schedule(message)` method.
 *
 * Their body is `distributedTaskService.schedule(getDef(), ...)`, which looks exactly like a
 * self-reschedule and is nothing of the sort: it is how *other* code launches the task. Reading it as
 * a loop would put one on every task in the monorepo that extends such a base.
 */
class DtfFlowSchedulableBaseTest : DtfFlowFixtureTestCase() {

    fun testASelfSchedulingBaseDoesNotPutALoopOnItsSubclass() {
        addSchedulableBase()
        addSchedulableTask("ReportTask", "REPORT")

        assertEquals(emptyList<String>(), buildFlowOf("ReportTask").arrows())
    }

    /** And the call sites that do use it are still arrows, attributed to whoever wrote them. */
    fun testACallerOfThatHelperIsStillAnArrow() {
        addSchedulableBase()
        addSchedulableTask("ReportTask", "REPORT")
        myFixture.addFileToProject(
            "SenderTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class SenderTask implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("SENDER", String.class);
                ReportTask reportTask;

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> executionContext) {
                    reportTask.schedule("payload");
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("SENDER -> REPORT"), buildFlowOf("ReportTask").arrows())
    }

    /**
     * The reverse trap: a task's own public `schedule*` helper that launches a *different* task.
     * Called on `this`, it looks like the task launching itself, but the arrow belongs to the task
     * named inside the helper.
     */
    fun testATaskHelperLaunchingAnotherTaskIsNotALoop() {
        addJavaTask("FileScanTask", "FILE_SCAN")
        myFixture.addFileToProject(
            "PathScanTask.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.service.DistributedTaskService
            import com.distributed_task_framework.task.Task

            class PathScanTask(private val distributedTaskService: DistributedTaskService) : Task<String> {
                override fun getDef(): TaskDef<String> = TASK_DEF

                override fun execute(executionContext: ExecutionContext<String>) {
                    listOf("a", "b").forEach { file -> scheduleFileScan(file) }
                }

                fun scheduleFileScan(file: String) {
                    distributedTaskService.schedule(FileScanTask.DEF, ExecutionContext.simple(file))
                }

                companion object {
                    val TASK_DEF: TaskDef<String> = TaskDef.privateTaskDef("PATH_SCAN", String::class.java)
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("PATH_SCAN -> FILE_SCAN"), buildFlowOf("PathScanTask").arrows())
    }

    private fun addSchedulableBase() {
        myFixture.addFileToProject(
            "SimpleSchedulableTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskId;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public abstract class SimpleSchedulableTask<T> implements Task<T> {
                DistributedTaskService distributedTaskService;

                public TaskId schedule(T message) {
                    try {
                        return distributedTaskService.schedule(getDef(), ExecutionContext.simple(message));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun addSchedulableTask(className: String, taskName: String) {
        myFixture.addFileToProject(
            "$className.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public class $className extends SimpleSchedulableTask<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("$taskName", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> executionContext) {}
            }
            """.trimIndent(),
        )
    }
}
