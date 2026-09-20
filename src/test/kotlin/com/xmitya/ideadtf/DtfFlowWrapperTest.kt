package com.xmitya.ideadtf

/**
 * Projects rarely call the framework directly.
 *
 * Two shapes dominate. A pass-through helper takes the `TaskDef` and forwards it, which the existing
 * searchers already follow. A named scheduler holds the definition in its own body and takes only the
 * payload - there the call site mentions no task at all, and without one hop back through its callers
 * the chain would simply stop.
 */
class DtfFlowWrapperTest : DtfFlowFixtureTestCase() {

    /** `sneakyScheduler.schedule(TARGET.DEF, ctx)` - the definition is still at the call site. */
    fun testPassThroughWrapperKeepsTheChainTogether() {
        addSneakyScheduler()
        addJavaTask("TargetTask", "TARGET")
        addTaskUsing(
            "SourceTask",
            "SOURCE",
            field = "SneakyScheduler sneakyScheduler;",
            body = "        sneakyScheduler.schedule(TargetTask.DEF, executionContext);",
        )

        assertEquals(listOf("SOURCE -> TARGET"), buildFlowOf("SourceTask").arrows())
    }

    /** A named scheduler hides the definition, so the arrow is recovered from its callers instead. */
    fun testNamedSchedulerIsSteppedThroughRatherThanDrawn() {
        addJavaTask("CleanupTask", "CLEANUP")
        addWorkflowScheduler()
        addTaskUsing(
            "ValidateTask",
            "VALIDATE",
            field = "WorkflowScheduler workflowScheduler;",
            body = "        workflowScheduler.scheduleCleanup(\"x\");",
        )

        val graph = buildFlowOf("CleanupTask")

        assertEquals(listOf("CLEANUP", "VALIDATE"), graph.nodeNames())
        assertEquals(listOf("VALIDATE -> CLEANUP"), graph.arrows())
    }

    /**
     * Called from ordinary code rather than from a task, the scheduler's own method is the entry
     * point - and stays one box.
     *
     * Stepping through to every caller would be truer to where the flow is triggered, but a
     * scheduler called from twenty controllers would then open as twenty start events for one chain.
     * The hop exists to keep task-to-task chains connected, not to enumerate trigger sites; the
     * gutter icon on the task still lists all of those.
     */
    fun testASchedulerCalledFromPlainCodeStaysOneEntryPoint() {
        addJavaTask("CleanupTask", "CLEANUP")
        addWorkflowScheduler()
        myFixture.addFileToProject(
            "CleanupController.java",
            """
            public class CleanupController {
                private WorkflowScheduler workflowScheduler;

                public void trigger() throws Exception {
                    workflowScheduler.scheduleCleanup("x");
                }
            }
            """.trimIndent(),
        )

        val graph = buildFlowOf("CleanupTask")

        assertEquals(listOf("CLEANUP", "caller:scheduleCleanup()"), graph.nodeNames())
        assertEquals(listOf("caller:scheduleCleanup() -> CLEANUP"), graph.arrows())
    }

    /** With nobody calling it from a task, the scheduler's own method is the entry point. */
    fun testAnUncalledSchedulerIsItselfTheEntryPoint() {
        addJavaTask("CleanupTask", "CLEANUP")
        addWorkflowScheduler()

        assertEquals(listOf("CLEANUP", "caller:scheduleCleanup()"), buildFlowOf("CleanupTask").nodeNames())
    }

    private fun addSneakyScheduler() {
        myFixture.addFileToProject(
            "SneakyScheduler.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class SneakyScheduler {
                private DistributedTaskService distributedTaskService;

                public <T> void schedule(TaskDef<T> taskDef, ExecutionContext<T> executionContext) throws Exception {
                    distributedTaskService.schedule(taskDef, executionContext);
                }
            }
            """.trimIndent(),
        )
    }

    private fun addWorkflowScheduler() {
        myFixture.addFileToProject(
            "WorkflowScheduler.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class WorkflowScheduler {
                private DistributedTaskService distributedTaskService;

                public void scheduleCleanup(String payload) throws Exception {
                    distributedTaskService.schedule(CleanupTask.DEF, ExecutionContext.simple(payload));
                }
            }
            """.trimIndent(),
        )
    }

    private fun addTaskUsing(className: String, taskName: String, field: String, body: String) {
        myFixture.addFileToProject(
            "$className.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class $className implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("$taskName", String.class);
                $field

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> executionContext) throws Exception {
            $body
                }
            }
            """.trimIndent(),
        )
    }
}
