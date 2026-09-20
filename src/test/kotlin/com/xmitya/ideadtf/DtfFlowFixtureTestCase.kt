package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowGraphBuilder
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode

/** Shared scaffolding for the flow tests: building a graph, and describing one compactly. */
abstract class DtfFlowFixtureTestCase : DtfFixtureTestCase() {

    protected fun buildFlowOf(qualifiedName: String): DtfFlowGraph = build(DtfFlowScope.Task(qualifiedName, qualifiedName))

    protected fun build(scope: DtfFlowScope): DtfFlowGraph = ReadAction.compute<DtfFlowGraph, RuntimeException> {
        DtfFlowGraphBuilder(project).build(scope)
    }

    /** Node ids, with the noisy parts of a caller or gateway id replaced by what it stands for. */
    protected fun DtfFlowGraph.nodeNames(): List<String> = nodes.map {
        when (it) {
            is DtfFlowTaskNode -> it.displayName
            is DtfFlowCallerNode -> "caller:${it.displayName}"
            is DtfFlowTimerNode -> "timer:${it.expression}"
            is DtfFlowGatewayNode -> if (it.approximate) "gateway:approximate" else "gateway"
        }
    }.sorted()

    /** Arrows as `from -> to`, using the same readable names. */
    protected fun DtfFlowGraph.arrows(): List<String> {
        val names = nodes.associate { node ->
            node.id to when (node) {
                is DtfFlowTaskNode -> node.displayName
                is DtfFlowCallerNode -> "caller:${node.displayName}"
                is DtfFlowTimerNode -> "timer"
                is DtfFlowGatewayNode -> "gateway"
            }
        }
        return edges.map { "${names[it.fromId]} -> ${names[it.toId]}" }.sorted()
    }

    protected fun DtfFlowGraph.edgeBetween(from: String, to: String): DtfFlowEdge? {
        val names = nodes.associate { node -> node.id to (node as? DtfFlowTaskNode)?.displayName }
        return edges.firstOrNull { names[it.fromId] == from && names[it.toId] == to }
    }

    protected fun addJavaTask(className: String, taskName: String, body: String = "") {
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
            }
            """.trimIndent(),
        )
    }
}
