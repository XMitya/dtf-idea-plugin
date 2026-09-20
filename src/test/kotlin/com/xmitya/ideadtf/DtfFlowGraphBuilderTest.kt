package com.xmitya.ideadtf

import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.DtfFlowTaskNode

/**
 * The shape the diagram is built from: who starts a flow, what each task hands on, and where the
 * walk has to stop.
 */
class DtfFlowGraphBuilderTest : DtfFlowFixtureTestCase() {

    /** The plain case: something calls `schedule`, one task runs, and it hands on to the next. */
    fun testChainFromCallingCodeThroughTwoTasks() {
        addJavaTask("SecondTask", "SECOND")
        addJavaTask(
            "FirstTask",
            "FIRST",
            "        distributedTaskService.schedule(SecondTask.DEF, executionContext.withNewMessage(\"x\"));",
        )
        addCaller("startFlow", "FirstTask.DEF")

        val graph = buildFlowOf("FirstTask")

        assertEquals(listOf("FIRST", "SECOND", "caller:startFlow()"), graph.nodeNames())
        assertEquals(listOf("FIRST -> SECOND", "caller:startFlow() -> FIRST"), graph.arrows())
    }

    /** Opening the diagram on the far end of a chain has to find its way back to the beginning. */
    fun testWalkingUpstreamFromTheLastTaskReachesTheCaller() {
        addJavaTask("SecondTask", "SECOND")
        addJavaTask(
            "FirstTask",
            "FIRST",
            "        distributedTaskService.schedule(SecondTask.DEF, executionContext.withNewMessage(\"x\"));",
        )
        addCaller("startFlow", "FirstTask.DEF")

        assertEquals(listOf("FIRST -> SECOND", "caller:startFlow() -> FIRST"), buildFlowOf("SecondTask").arrows())
    }

    /** A task that reschedules itself is a loop, not a second box. */
    fun testSelfRescheduleIsASelfEdge() {
        addJavaTask(
            "RetryTask",
            "RETRY",
            "        distributedTaskService.schedule(getDef(), executionContext);",
        )

        val graph = buildFlowOf("RetryTask")

        assertEquals(listOf("RETRY"), graph.nodeNames())
        assertEquals(listOf("RETRY -> RETRY"), graph.arrows())
    }

    /** Two tasks launched from one body are two branches, and both are followed. */
    fun testFanOutFromOneBody() {
        addJavaTask("LeftTask", "LEFT")
        addJavaTask("RightTask", "RIGHT")
        addJavaTask(
            "SplitTask",
            "SPLIT",
            """
                    distributedTaskService.schedule(LeftTask.DEF, executionContext);
                    distributedTaskService.schedule(RightTask.DEF, executionContext);
            """.trimIndent(),
        )

        assertEquals(listOf("SPLIT -> LEFT", "SPLIT -> RIGHT"), buildFlowOf("SplitTask").arrows())
    }

    /** `scheduleFork` is not a fan-out; it is a branch that leaves the parent's join hierarchy. */
    fun testForkKeepsItsOwnEdgeKind() {
        addJavaTask("DetachedTask", "DETACHED")
        addJavaTask(
            "ForkingTask",
            "FORKING",
            "        distributedTaskService.scheduleFork(DetachedTask.DEF, executionContext);",
        )

        val edge = buildFlowOf("ForkingTask").edgeBetween("FORKING", "DETACHED")

        assertEquals(DtfFlowEdgeKind.FORK, edge?.kind)
    }

    /** A cron task has nobody to call it, so the framework's clock is the start of the flow. */
    fun testCronTaskGetsATimerStartEvent() {
        myFixture.addFileToProject(
            "app/src/main/resources/application.yaml",
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  NIGHTLY:
                    cron: 0 0 1 * * *
            """.trimIndent(),
        )
        addJavaTask("NightlyTask", "NIGHTLY")

        val graph = buildFlowOf("NightlyTask")

        assertEquals(listOf("NIGHTLY", "timer:0 0 1 * * *"), graph.nodeNames())
        assertEquals(listOf("timer -> NIGHTLY"), graph.arrows())
        assertTrue((graph.nodes.first { it is DtfFlowTaskNode } as DtfFlowTaskNode).isCron)
    }

    /** Everything a module holds, whether or not the flows are connected to each other. */
    fun testModuleScopeDrawsEveryFlowInTheModule() {
        addJavaTask("AloneTask", "ALONE")
        addJavaTask("SecondTask", "SECOND")
        addJavaTask(
            "FirstTask",
            "FIRST",
            "        distributedTaskService.schedule(SecondTask.DEF, executionContext);",
        )

        val graph = build(DtfFlowScope.Module(module.name, module.name))

        assertEquals(listOf("ALONE", "FIRST", "SECOND"), graph.nodeNames())
        assertEquals(listOf("FIRST -> SECOND"), graph.arrows())
    }

    /** A flow the plugin cannot find anything for says so rather than drawing an empty box. */
    fun testUnknownTaskYieldsAnEmptyGraph() {
        assertTrue(buildFlowOf("NoSuchTask").isEmpty)
    }

    /** A task name is what the box leads with; the class stays beside it, as in the tool window. */
    fun testTaskNodeCarriesBothNames() {
        addJavaTask("HelloTask", "HELLO")

        val node = buildFlowOf("HelloTask").nodes.filterIsInstance<DtfFlowTaskNode>().single()

        assertEquals("HELLO", node.taskName)
        assertEquals("HelloTask", node.className)
        assertEquals("HELLO", node.displayName)
    }

    /** Opening the diagram from a `schedule(...)` call starts the flow at that call. */
    fun testCallScopeStartsAtTheCall() {
        addJavaTask("SecondTask", "SECOND")
        addJavaTask(
            "FirstTask",
            "FIRST",
            "        distributedTaskService.schedule(SecondTask.DEF, executionContext.withNewMessage(\"x\"));",
        )
        val caller = addCaller("startFlow", "FirstTask.DEF")
        val offset = caller.text.indexOf("distributedTaskService.schedule")

        val graph = build(DtfFlowScope.Call(caller.virtualFile.url, offset, "schedule"))

        assertEquals(listOf("FIRST -> SECOND", "caller:startFlow() -> FIRST"), graph.arrows())
    }

    /** A call scope pointing at nothing draws nothing rather than throwing. */
    fun testCallScopeAtAnUnknownFileIsEmpty() {
        assertTrue(build(DtfFlowScope.Call("temp:///nowhere/Nothing.java", 0, "schedule")).isEmpty)
    }

    private fun addCaller(methodName: String, definition: String) = myFixture.addFileToProject(
        "Caller.java",
        """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Caller {
                private DistributedTaskService distributedTaskService;

                public void $methodName() throws Exception {
                    distributedTaskService.schedule($definition, ExecutionContext.simple("x"));
                }
            }
        """.trimIndent(),
    )
}
