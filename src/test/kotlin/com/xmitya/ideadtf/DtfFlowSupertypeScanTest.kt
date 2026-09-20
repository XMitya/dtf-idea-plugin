package com.xmitya.ideadtf

/**
 * Where the scheduling call actually lives.
 *
 * In real projects it is regularly not in `execute()`: a base class makes that final and dispatches
 * to `doExecute(...)`, error edges sit in `onFailure(...)`, and an interface `default` method
 * schedules on behalf of every implementor. Reading only the concrete class would draw all of those
 * flows as a row of disconnected boxes.
 */
class DtfFlowSupertypeScanTest : DtfFlowFixtureTestCase() {

    /** The template-method base: `execute` is final, the body the project writes is `doExecute`. */
    fun testEdgeScheduledFromATemplateBaseBody() {
        addJavaTask("NextTask", "NEXT")
        myFixture.addFileToProject(
            "CommonTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public abstract class CommonTask<T> implements Task<T> {
                protected DistributedTaskService distributedTaskService;

                @Override
                public final void execute(ExecutionContext<T> context) throws Exception {
                    doExecute(context);
                }

                public abstract void doExecute(ExecutionContext<T> context) throws Exception;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "StartTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public class StartTask extends CommonTask<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("START", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void doExecute(ExecutionContext<String> context) throws Exception {
                    distributedTaskService.schedule(NextTask.DEF, context);
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("START -> NEXT"), buildFlowOf("StartTask").arrows())
    }

    /** The error path is a flow of its own, and it is written in `onFailure`, not in `execute`. */
    fun testEdgeScheduledFromOnFailure() {
        addJavaTask("ReportFailureTask", "REPORT_FAILURE")
        myFixture.addFileToProject(
            "SendTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.FailedExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public class SendTask implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("SEND", String.class);
                DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> context) {}

                public void onFailure(FailedExecutionContext<String> context) throws Exception {
                    distributedTaskService.scheduleImmediately(ReportFailureTask.DEF, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("SEND -> REPORT_FAILURE"), buildFlowOf("SendTask").arrows())
    }

    /** An interface `default` method schedules for every implementor, so every implementor gets the edge. */
    fun testEdgeInheritedFromAnInterfaceDefaultMethod() {
        addJavaTask("CompleteTask", "COMPLETE")
        myFixture.addFileToProject(
            "ProcTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public interface ProcTask<T> extends Task<T> {
                default void sendFailed(DistributedTaskService service, ExecutionContext<?> context) throws Exception {
                    service.schedule(CompleteTask.DEF, ExecutionContext.simple("failed"));
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "ValidateTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public class ValidateTask implements ProcTask<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("VALIDATE", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> context) {}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("VALIDATE -> COMPLETE"), buildFlowOf("ValidateTask").arrows())
    }

    /**
     * A self-reschedule written in an abstract base is inherited by every task below it - but it is
     * a loop on each of them, not a wire between siblings.
     */
    fun testSelfRescheduleInABaseDoesNotWireUpSiblings() {
        myFixture.addFileToProject(
            "RetryingTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public abstract class RetryingTask<T> implements Task<T> {
                protected DistributedTaskService distributedTaskService;

                protected void retry(ExecutionContext<T> context) throws Exception {
                    distributedTaskService.schedule(getDef(), context);
                }
            }
            """.trimIndent(),
        )
        for (name in listOf("AlphaTask" to "ALPHA", "BetaTask" to "BETA")) {
            myFixture.addFileToProject(
                "${name.first}.java",
                """
                import com.distributed_task_framework.model.ExecutionContext;
                import com.distributed_task_framework.model.TaskDef;

                public class ${name.first} extends RetryingTask<String> {
                    public static final TaskDef<String> DEF = TaskDef.privateTaskDef("${name.second}", String.class);

                    @Override
                    public TaskDef<String> getDef() { return DEF; }

                    @Override
                    public void execute(ExecutionContext<String> context) throws Exception { retry(context); }
                }
                """.trimIndent(),
            )
        }

        assertEquals(listOf("ALPHA -> ALPHA"), buildFlowOf("AlphaTask").arrows())
    }
}
