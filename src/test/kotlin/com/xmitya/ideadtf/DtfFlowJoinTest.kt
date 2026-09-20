package com.xmitya.ideadtf

import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode

/**
 * Joins, which are the only branching DTF states outright.
 *
 * `scheduleJoin(def, ctx, joinList)` is the whole of the evidence - nothing marks the join task
 * itself - so what is asserted here is how far the `joinList` can be read, and that the diagram says
 * so when it could only be guessed at.
 */
class DtfFlowJoinTest : DtfFlowFixtureTestCase() {

    /** `List.of(idA, idB)`, each id a local holding a scheduling call: the branches are written down. */
    fun testInlineListNamesBothBranches() {
        addJavaTask("UniversalTask", "UNIVERSAL")
        addJavaTask("SplitTask", "SPLIT")
        addJavaTask("JoinTask", "JOIN")
        addJavaTask(
            "ValidateTask",
            "VALIDATE",
            """
                    TaskId universal = distributedTaskService.schedule(UniversalTask.DEF, executionContext);
                    TaskId split = distributedTaskService.schedule(SplitTask.DEF, executionContext);
                    distributedTaskService.scheduleJoin(JoinTask.DEF, executionContext, List.of(universal, split));
            """.trimIndent(),
        )

        val graph = buildFlowOf("ValidateTask")

        assertEquals(listOf("JOIN", "SPLIT", "UNIVERSAL", "VALIDATE", "gateway"), graph.nodeNames())
        assertEquals(
            listOf(
                "SPLIT -> gateway",
                "UNIVERSAL -> gateway",
                "VALIDATE -> SPLIT",
                "VALIDATE -> UNIVERSAL",
                "gateway -> JOIN",
            ),
            graph.arrows(),
        )
    }

    /** The loop idiom: an `ArrayList` filled with `add(taskId)` inside a `for`. */
    fun testListBuiltInALoopNamesTheBranch() {
        addJavaTask("MapTask", "MAP")
        addJavaTask("ReduceTask", "REDUCE")
        addJavaTask(
            "MapReduceTask",
            "MAP_REDUCE",
            """
                    List<TaskId> joinList = new ArrayList<TaskId>();
                    for (int i = 0; i < 10; i++) {
                        TaskId taskId = distributedTaskService.schedule(MapTask.DEF, executionContext);
                        joinList.add(taskId);
                    }
                    distributedTaskService.scheduleJoin(ReduceTask.DEF, executionContext, joinList);
            """.trimIndent(),
        )

        val graph = buildFlowOf("MapReduceTask")

        assertEquals(listOf("MAP -> gateway", "MAP_REDUCE -> MAP", "gateway -> REDUCE"), graph.arrows())
        assertFalse(gatewayOf(graph).approximate)
    }

    /**
     * A list the reader cannot follow - handed straight back by another call - still has its branches
     * in the same method, which is where the framework requires them to be scheduled.
     */
    fun testBranchesAreFoundInTheSameMethodWhenTheListCannotBeRead() {
        addJavaTask("BranchTask", "BRANCH")
        addJavaTask("JoinTask", "JOIN")
        addJavaTask(
            "OpaqueTask",
            "OPAQUE",
            """
                    distributedTaskService.schedule(BranchTask.DEF, executionContext);
                    distributedTaskService.scheduleJoin(JoinTask.DEF, executionContext, ids());
            """.trimIndent(),
            extraMembers = "    List<TaskId> ids() { return new ArrayList<TaskId>(); }",
        )

        val graph = buildFlowOf("OpaqueTask")

        assertEquals(listOf("BRANCH -> gateway", "OPAQUE -> BRANCH", "gateway -> JOIN"), graph.arrows())
        assertFalse(gatewayOf(graph).approximate)
    }

    /**
     * When the list is assembled inside a helper, the branches are only scheduled there too - so the
     * gateway is inferred from the class, and is drawn as the guess it is.
     */
    fun testBranchesAssembledInAHelperGiveAnApproximateGateway() {
        addJavaTask("MoveTask", "MOVE")
        addJavaTask("JoinTask", "JOIN")
        addJavaTask(
            "UploadCompleteTask",
            "UPLOAD_COMPLETE",
            "        distributedTaskService.scheduleJoin(JoinTask.DEF, executionContext, moves(executionContext));",
            extraMembers = """
                    List<TaskId> moves(ExecutionContext<String> ctx) throws Exception {
                        List<TaskId> ids = new ArrayList<TaskId>();
                        for (int i = 0; i < 3; i++) {
                            ids.add(distributedTaskService.schedule(MoveTask.DEF, ctx));
                        }
                        return ids;
                    }
            """.trimIndent(),
        )

        val graph = buildFlowOf("UploadCompleteTask")

        assertEquals(listOf("MOVE -> gateway", "UPLOAD_COMPLETE -> MOVE", "gateway -> JOIN"), graph.arrows())
        assertTrue(gatewayOf(graph).approximate)
    }

    /** `scheduleFork` detaches a branch from the join hierarchy, so it must not feed the gateway. */
    fun testForkedBranchStaysOutsideTheGateway() {
        addJavaTask("BranchTask", "BRANCH")
        addJavaTask("DetachedTask", "DETACHED")
        addJavaTask("JoinTask", "JOIN")
        addJavaTask(
            "ParentTask",
            "PARENT",
            """
                    TaskId branch = distributedTaskService.schedule(BranchTask.DEF, executionContext);
                    distributedTaskService.scheduleFork(DetachedTask.DEF, executionContext);
                    distributedTaskService.scheduleJoin(JoinTask.DEF, executionContext, List.of(branch));
            """.trimIndent(),
        )

        val graph = buildFlowOf("ParentTask")

        assertTrue("the forked branch must not enter the gateway", graph.arrows().none { it == "DETACHED -> gateway" })
        assertEquals(listOf("PARENT -> DETACHED"), graph.arrows().filter { it.endsWith("-> DETACHED") })
        assertEquals(DtfFlowEdgeKind.FORK, graph.edgeBetween("PARENT", "DETACHED")?.kind)
    }

    /** A join whose own id feeds another join: gateways nest, and the inner join is a branch. */
    fun testNestedJoinsChain() {
        addJavaTask("LeafTask", "LEAF")
        addJavaTask("InnerJoinTask", "INNER")
        addJavaTask("OuterJoinTask", "OUTER")
        addJavaTask(
            "NestingTask",
            "NESTING",
            """
                    TaskId leaf = distributedTaskService.schedule(LeafTask.DEF, executionContext);
                    TaskId inner = distributedTaskService.scheduleJoin(InnerJoinTask.DEF, executionContext, List.of(leaf));
                    distributedTaskService.scheduleJoin(OuterJoinTask.DEF, executionContext, List.of(inner));
            """.trimIndent(),
        )

        val arrows = buildFlowOf("NestingTask").arrows()

        assertTrue("the leaf feeds the inner gateway: $arrows", arrows.contains("LEAF -> gateway"))
        assertTrue("the inner join feeds the outer gateway: $arrows", arrows.contains("INNER -> gateway"))
        assertEquals(2, arrows.count { it.startsWith("gateway -> ") })
    }

    private fun gatewayOf(graph: com.xmitya.ideadtf.flow.DtfFlowGraph): DtfFlowGatewayNode =
        graph.nodes.filterIsInstance<DtfFlowGatewayNode>().single()

    private fun addJavaTask(className: String, taskName: String, body: String, extraMembers: String) {
        myFixture.addFileToProject(
            "$className.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.model.TaskId;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;
            import java.util.ArrayList;
            import java.util.List;

            public class $className implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("$taskName", String.class);
                DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return DEF; }

                @Override
                public void execute(ExecutionContext<String> executionContext) throws Exception {
            $body
                }

            $extraMembers
            }
            """.trimIndent(),
        )
    }
}
