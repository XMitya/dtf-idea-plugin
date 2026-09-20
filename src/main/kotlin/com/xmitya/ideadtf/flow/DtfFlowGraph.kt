package com.xmitya.ideadtf.flow

import com.intellij.pom.Navigatable

/**
 * One box of a flow diagram.
 *
 * Carries no live PSI, only strings and a smart pointer - the same rule
 * [com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot] follows, and for the same reason: the diagram
 * outlives the read action that built it, and painting it must never resolve anything.
 */
sealed interface DtfFlowNode {

    /** Identity, and the key edges refer to. Two nodes with the same id are the same thing. */
    val id: String

    /**
     * Where a double-click lands; null for a node with nothing to open.
     *
     * A [Navigatable] rather than the pointer behind it, so that the canvas can hand it straight to
     * `CommonDataKeys.NAVIGATABLE_ARRAY` and get F4 and Enter for free.
     */
    val target: Navigatable?
}

/**
 * A DTF task.
 *
 * [taskName] leads and [className] follows in grey - the tool window row, boxed, so that the same
 * task reads the same way in both places.
 */
data class DtfFlowTaskNode(
    override val id: String,
    val taskName: String?,
    val className: String,
    val qualifiedName: String,
    /** Whether some `scheduleJoin` names this task, which is the only thing that makes it a join. */
    val isJoinTarget: Boolean,
    val isCron: Boolean,
    override val target: Navigatable?,
) : DtfFlowNode {

    /** What the box leads with, as in the tool window: the definition when there is one. */
    val displayName: String get() = taskName ?: className
}

/**
 * The calling code a flow starts from - `upload()`, in grey `AppUploadService`.
 *
 * One node per call site rather than per method: a method that schedules two different tasks would
 * otherwise be one box whose double-click could only pick one of them.
 */
class DtfFlowCallerNode(
    override val id: String,
    val methodName: String?,
    val className: String?,
    /** `AppUploadService.java:91`, the thing you actually scan for. */
    val location: String?,
    override val target: Navigatable?,
) : DtfFlowNode {

    val displayName: String get() = methodName?.let { "$it()" } ?: className ?: location.orEmpty()
}

/**
 * BPMN timer start event: a cron task has no caller, the framework launches it.
 *
 * Its pointer leads where the schedule is configured, so double-click behaves like the clock gutter
 * icon rather than dead-ending.
 */
class DtfFlowTimerNode(override val id: String, val expression: String?, override val target: Navigatable?) : DtfFlowNode

/** Which BPMN gateway a [DtfFlowGatewayNode] is. Only [JOIN] is produced today. */
enum class GatewayKind { JOIN, SPLIT }

/**
 * BPMN parallel gateway.
 *
 * Produced for a `scheduleJoin(...)` call, which is the only static evidence that a join exists at
 * all - nothing marks the join task itself.
 */
class DtfFlowGatewayNode(
    override val id: String,
    val kind: GatewayKind,
    /**
     * True when the branches could not be read off the `joinList` and were inferred from the
     * enclosing class instead. Drawn dashed, so the diagram never passes a guess off as a reading.
     */
    val approximate: Boolean,
    override val target: Navigatable?,
) : DtfFlowNode

/** How one node leads to the next. */
enum class DtfFlowEdgeKind {
    /** A plain `schedule` / `scheduleUnsafe`. */
    SCHEDULE,

    /** `scheduleFork`: deliberately outside the enclosing join, hence never a [JOIN_BRANCH]. */
    FORK,

    /** `scheduleImmediately`. */
    IMMEDIATE,

    /** A branch feeding a join gateway. */
    JOIN_BRANCH,

    /** A join gateway feeding its join task. */
    GATEWAY_OUT,

    /** A cron schedule feeding the task it launches. */
    TIMER,
}

/**
 * One arrow.
 *
 * [messageType], [condition] and [inWorkflow] are the next two features' worth of information. They
 * are declared now and left null, because the layout already reserves a label slot for them - filling
 * them in later is a builder change and a `drawString`, not a reshaping of the model.
 */
class DtfFlowEdge(
    val fromId: String,
    val toId: String,
    val kind: DtfFlowEdgeKind,
    /** The type of the message handed to the target. */
    val messageType: String? = null,
    /** The `if` / `when` the call sits in. */
    val condition: String? = null,
    /**
     * `ctx.withNewMessage(...)` keeps the target in the caller's workflow;
     * `ExecutionContext.simple(...)` detaches it. Null when it could not be read.
     */
    val inWorkflow: Boolean? = null,
    /** The scheduling call itself, so that an arrow can be navigable later. */
    val target: Navigatable? = null,
) {
    /** Identity for de-duplication: the same call is reachable from both directions of the walk. */
    val key: String get() = "$fromId->$toId:$kind"

    val isSelfLoop: Boolean get() = fromId == toId

    /** False throughout v1, because nothing fills the two reserved fields yet. */
    val hasLabel: Boolean get() = messageType != null || condition != null

    /** `SomeDto [flag]` - what the reserved slot on the arrow will say. */
    val label: String?
        get() = listOfNotNull(messageType, condition?.let { "[$it]" })
            .joinToString(" ")
            .ifEmpty { null }
}

/**
 * A whole diagram, as of one build.
 *
 * @param truncated whether a cap was hit. Said out loud on the canvas rather than quietly showing
 *   part of a flow as if it were all of it.
 */
class DtfFlowGraph(val title: String, val nodes: List<DtfFlowNode>, val edges: List<DtfFlowEdge>, val truncated: Boolean = false) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    fun node(id: String): DtfFlowNode? = nodes.firstOrNull { it.id == id }

    companion object {
        fun empty(title: String): DtfFlowGraph = DtfFlowGraph(title, emptyList(), emptyList())
    }
}
