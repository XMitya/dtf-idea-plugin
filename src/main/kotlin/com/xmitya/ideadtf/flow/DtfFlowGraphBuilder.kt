package com.xmitya.ideadtf.flow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.cron.DtfCronConfigSource
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskHierarchy
import com.xmitya.ideadtf.model.DtfTaskModel
import com.xmitya.ideadtf.search.CallArgumentMatcher
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.search.PointerNavigatable
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSiteLabel
import com.xmitya.ideadtf.search.ScheduledTaskSearcher
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Turns a [DtfFlowScope] into the graph the diagram draws.
 *
 * Expects to be called inside a read action, off the EDT: it runs one project-wide reference search
 * per task, which is the cost of one gutter click multiplied by the size of the flow.
 *
 * The walk goes both ways from its seeds, because neither direction alone is a flow. Upstream is
 * [ScheduleCallSearcher] - who launches this task - and downstream is a UAST walk of the task's own
 * code resolved through [ScheduledTaskSearcher]. Everything the two produce is reduced to strings and
 * smart pointers before it leaves this class.
 */
class DtfFlowGraphBuilder(private val project: Project) {

    private companion object {
        /** Runaway guards rather than routine truncation: real flows are three to six nodes. */
        const val MAX_NODES = 300
        const val MAX_DEPTH = 12

        /** Each probe is a project-wide search, so the whole build gets an allowance rather than each site. */
        const val MAX_FACADE_PROBES = 50
        const val MAX_FACADE_REFERENCES = 16

        const val SCHEDULE_JOIN = "scheduleJoin"
        const val SCHEDULE_FORK = "scheduleFork"
        const val SCHEDULE_IMMEDIATELY = "scheduleImmediately"
    }

    private val searchScope = GlobalSearchScope.projectScope(project)
    private val pointers = SmartPointerManager.getInstance(project)
    private val scheduleCalls = ScheduleCallSearcher(project)
    private val scheduledTasks = ScheduledTaskSearcher(project)
    private val joinBranches = JoinBranchResolver(project)

    private val taskRecords = LinkedHashMap<String, TaskRecord>()
    private val otherNodes = LinkedHashMap<String, DtfFlowNode>()
    private val edges = LinkedHashMap<String, DtfFlowEdge>()
    private val queue = ArrayDeque<Pending>()
    private val expanded = HashSet<String>()
    private var truncated = false
    private var facadeProbes = 0

    private class Pending(val task: PsiClass, val depth: Int)

    /** A task node under construction: `isJoinTarget` is only learned when some `scheduleJoin` names it. */
    private class TaskRecord(val psi: PsiClass, val node: DtfFlowTaskNode) {
        var isJoinTarget = false
    }

    fun build(scope: DtfFlowScope): DtfFlowGraph {
        seedsOf(scope).forEach { expand(it, depth = 0) }

        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val pending = queue.removeFirst()
            val id = taskIdOf(pending.task) ?: continue
            collectUpstream(pending.task, id, pending.depth)
            collectDownstream(pending.task, id, pending.depth)
            collectCron(pending.task, id)
        }

        val nodes = taskRecords.values.map { record ->
            if (record.isJoinTarget) record.node.copy(isJoinTarget = true) else record.node
        } + otherNodes.values
        return DtfFlowGraph(scope.title, nodes, edges.values.toList(), truncated)
    }

    // --- seeds ----------------------------------------------------------------------------------

    private fun seedsOf(scope: DtfFlowScope): List<PsiClass> = when (scope) {
        is DtfFlowScope.Task -> listOfNotNull(findTaskClass(scope.qualifiedName))
        is DtfFlowScope.Module -> tasksOfModule(scope.moduleName)
        is DtfFlowScope.Call -> callSeedsOf(scope)
    }

    private fun findTaskClass(qualifiedName: String): PsiClass? = JavaPsiFacade.getInstance(project).findClass(qualifiedName, searchScope)

    private fun tasksOfModule(moduleName: String): List<PsiClass> {
        val index = ProjectFileIndex.getInstance(project)
        return DtfTaskSearcher(project).findAllTasks().filter { task ->
            ProgressManager.checkCanceled()
            val file = sourceAnchorOf(task).containingFile?.virtualFile ?: return@filter false
            index.getModuleForFile(file)?.name == moduleName
        }
    }

    /** The tasks one `schedule(...)` call launches, which is where a flow opened from a call site starts. */
    private fun callSeedsOf(scope: DtfFlowScope.Call): List<PsiClass> {
        val call = callAt(scope.fileUrl, scope.offset) ?: return emptyList()
        return scheduledTasks.findTasks(call)
    }

    private fun callAt(fileUrl: String, offset: Int): UCallExpression? {
        // By URL rather than by path: a fixture's files live in a temp file system, and so do the
        // ones inside a jar - neither is reachable through LocalFileSystem.
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        // The offset is the start of the whole expression, so the leaf is usually the receiver -
        // `distributedTaskService` in `distributedTaskService.schedule(...)`. Climbing from there
        // reaches the qualified expression, of which the call is the *selector*, never a parent;
        // hence unwrapping it rather than only filtering the ancestors.
        val start = leaf.toUElement() ?: CallArgumentMatcher.asExpression(leaf) ?: return null
        return generateSequence(start) { it.uastParent }
            .firstNotNullOfOrNull { element ->
                when (element) {
                    is UCallExpression -> element
                    is UQualifiedReferenceExpression -> element.selector as? UCallExpression
                    else -> null
                }
            }
    }

    // --- traversal ------------------------------------------------------------------------------

    private fun expand(task: PsiClass, depth: Int) {
        val qualifiedName = task.qualifiedName ?: return
        if (!expanded.add(qualifiedName)) return
        if (depth > MAX_DEPTH || taskRecords.size + otherNodes.size >= MAX_NODES) {
            truncated = true
            return
        }
        queue += Pending(task, depth)
    }

    private fun collectUpstream(task: PsiClass, taskId: String, depth: Int) {
        for (site in scheduleCalls.findScheduleSites(task)) {
            ProgressManager.checkCanceled()
            // The body of the task's own `schedule(message)` helper is not a place it is scheduled
            // from; it is how other code schedules it, and those callers are separate sites.
            if (isSchedulingHelperBody(site.element, task)) continue

            val call = site.element.toUElementOfType<UCallExpression>()
            if (call != null && call.resolve()?.name == SCHEDULE_JOIN) {
                // The join path owns this arrow: it goes through a gateway, not straight in.
                linkJoin(call, site.element, depth)
                continue
            }

            val owner = ScheduleSiteLabel.ownerOf(site.element)
            when {
                owner != null && DtfTaskModel.isMarkableTask(owner) ->
                    linkTask(owner, taskId, site.element, depth)

                // An abstract base or a shared interface is not a task in its own right and has no
                // box. What it schedules belongs to the concrete tasks below it - which is this task
                // itself when the code is inherited, and every implementor when it is not.
                owner != null && DtfTaskModel.isDtfTask(owner) ->
                    linkInheritorsOf(owner, task, taskId, site.element, depth)

                owner != null && linkThroughScheduler(owner, site.element, taskId, depth) -> Unit

                else -> addCaller(site.element, taskId, kindOf(call))
            }
        }
    }

    private fun linkInheritorsOf(owner: PsiClass, task: PsiClass, taskId: String, element: PsiElement, depth: Int) {
        val ownerName = owner.qualifiedName
        if (ownerName != null && InheritanceUtil.isInheritor(task, ownerName)) {
            addEdge(taskId, taskId, kindOf(element.toUElementOfType<UCallExpression>()), element)
            return
        }
        for (inheritor in ClassInheritorsSearch.search(owner, searchScope, true).findAll()) {
            ProgressManager.checkCanceled()
            if (DtfTaskModel.isMarkableTask(inheritor)) linkTask(inheritor, taskId, element, depth)
        }
    }

    /**
     * What the task's own code launches.
     *
     * The whole supertype closure, not just the class: a project base routinely makes `execute()` a
     * final template and puts the real body in `doExecute(...)`, error edges live in `onFailure(...)`,
     * and an interface `default` method can schedule on behalf of every implementor.
     */
    private fun collectDownstream(task: PsiClass, taskId: String, depth: Int) {
        for (owner in DtfTaskHierarchy.supertypeClosure(task)) {
            val uClass = DtfTaskHierarchy.asSourceUClass(owner) ?: continue
            uClass.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    ProgressManager.checkCanceled()
                    collectOutgoing(node, task, taskId, depth)
                    return false
                }
            })
        }
    }

    private fun collectOutgoing(call: UCallExpression, task: PsiClass, taskId: String, depth: Int) {
        val method = call.resolve() ?: return
        if (method.name == SCHEDULE_JOIN) {
            linkJoin(call, call.sourcePsi ?: return, depth)
            return
        }
        if (!DtfScheduleMarkers.launchesATask(call)) return

        // `schedule(getDef(), ctx)` read from an abstract base resolves to every task below it; here
        // the answer is the one task whose code is being walked, or the loop would wire up siblings.
        val selfScheduled = call.getArgumentForParameter(0)?.let { CallArgumentMatcher.resolvesToGetDef(it) } == true
        // ...unless it is the body of the task's own `schedule(message)` helper, which is not a
        // reschedule at all but how other code launches it. Those callers are drawn from the other
        // direction, and drawing a loop here as well would put one on every task sharing such a base.
        if (selfScheduled && isSchedulingHelperBody(call.sourcePsi, task)) return

        val targets = if (selfScheduled) listOf(task) else scheduledTasks.findTasks(call)

        for (target in targets) {
            ProgressManager.checkCanceled()
            linkTask(target, taskId, call.sourcePsi, depth, fromId = taskId, reversed = false)
        }
    }

    private fun isSchedulingHelperBody(element: PsiElement?, task: PsiClass): Boolean {
        val enclosing = element?.let { enclosingMethodOf(it) } ?: return false
        return DtfTaskHierarchy.schedulingHelpersOf(task).any { it.isEquivalentTo(enclosing.javaPsi) }
    }

    private fun collectCron(task: PsiClass, taskId: String) {
        val cron = DtfCronModel.cronMarkOf(task) as? CronMark.Cron ?: return
        val id = "timer:${task.qualifiedName}"
        otherNodes.getOrPut(id) { DtfFlowTimerNode(id, cron.expression, cronPointerOf(task)) }
        addEdge(id, taskId, DtfFlowEdgeKind.TIMER, null)
    }

    /** Where the schedule is written, so that a double-click behaves like the clock gutter icon. */
    private fun cronPointerOf(task: PsiClass) = run {
        val taskName = DtfTaskDefResolver.resolveCached(task).taskName
        val site = taskName?.let {
            DtfCronConfigSource.EP.extensionList.firstNotNullOfOrNull { source ->
                source.findSites(project, it, searchScope).firstOrNull()
            }
        }
        when {
            site != null -> PointerNavigatable(pointers.createSmartPsiFileRangePointer(site.file, TextRange(site.offset, site.offset)))
            else -> task.getAnnotation(DtfFqns.TASK_SCHEDULE_ANNOTATION)?.let { navigatableFor(it) }
        }
    }

    // --- the one hop through a named scheduler --------------------------------------------------

    /**
     * A project scheduler such as `FileUploadWorkflowScheduler.scheduleDeleteFromDirtyBucket(payload)`
     * holds the `TaskDef` in its own body, so the call site's class is the façade rather than a task
     * and the chain would break in two here.
     *
     * One hop back through its callers repairs that: a caller that is itself a task becomes the real
     * arrow, and the façade is not drawn. A façade nobody schedules from a task - a controller, a
     * listener, a service - has no task callers, so nothing matches and it stays an entry point.
     */
    private fun linkThroughScheduler(owner: PsiClass, element: PsiElement, targetId: String, depth: Int): Boolean {
        if (facadeProbes >= MAX_FACADE_PROBES) return false
        val method = enclosingMethodOf(element)?.javaPsi ?: return false
        if (owner.qualifiedName == null) return false
        facadeProbes++

        val references = MethodReferencesSearch.search(method, searchScope, true).findAll().take(MAX_FACADE_REFERENCES)
        val callers = references.mapNotNull { reference ->
            ProgressManager.checkCanceled()
            ScheduleSiteLabel.ownerOf(reference.element)?.let { reference.element to it }
        }
        if (callers.none { DtfTaskModel.isMarkableTask(it.second) }) return false

        for ((reference, callerClass) in callers) {
            if (DtfTaskModel.isMarkableTask(callerClass)) {
                linkTask(callerClass, targetId, reference, depth)
            } else {
                addCaller(reference, targetId, DtfFlowEdgeKind.SCHEDULE)
            }
        }
        return true
    }

    private fun enclosingMethodOf(element: PsiElement): UMethod? = generateSequence(element.toUElement()) { it.uastParent }
        .filterIsInstance<UMethod>()
        .firstOrNull()

    // --- joins ----------------------------------------------------------------------------------

    private fun linkJoin(call: UCallExpression, anchor: PsiElement, depth: Int) {
        val resolved = joinBranches.resolve(call) ?: return
        val gatewayId = "join:${keyOf(anchor)}"
        otherNodes.getOrPut(gatewayId) {
            DtfFlowGatewayNode(
                gatewayId,
                GatewayKind.JOIN,
                resolved.approximate,
                navigatableFor(anchor),
            )
        }

        for (joinTask in resolved.joinTasks) {
            val id = taskIdOf(joinTask) ?: continue
            taskRecords[id]?.isJoinTarget = true
            addEdge(gatewayId, id, DtfFlowEdgeKind.GATEWAY_OUT, anchor)
            expand(joinTask, depth + 1)
        }
        for (branch in resolved.branches) {
            val id = taskIdOf(branch) ?: continue
            addEdge(id, gatewayId, DtfFlowEdgeKind.JOIN_BRANCH, anchor)
            expand(branch, depth + 1)
        }
    }

    // --- nodes and edges ------------------------------------------------------------------------

    /**
     * Links [from] to [to], where [from] is a task. Both directions of the walk arrive here, so the
     * edge key is what keeps one call from being drawn twice.
     */
    private fun linkTask(
        other: PsiClass,
        anchorId: String,
        element: PsiElement?,
        depth: Int,
        fromId: String? = null,
        reversed: Boolean = true,
    ) {
        val otherId = taskIdOf(other) ?: return
        if (reversed) {
            addEdge(otherId, anchorId, kindOf(element?.toUElementOfType<UCallExpression>()), element)
        } else {
            addEdge(fromId ?: anchorId, otherId, kindOf(element?.toUElementOfType<UCallExpression>()), element)
        }
        expand(other, depth + 1)
    }

    private fun addCaller(element: PsiElement, targetId: String, kind: DtfFlowEdgeKind) {
        val id = "caller:${keyOf(element)}"
        otherNodes.getOrPut(id) {
            val label = ScheduleSiteLabel.of(project, element)
            DtfFlowCallerNode(
                id,
                label.methodName,
                label.className,
                label.location,
                navigatableFor(element),
            )
        }
        addEdge(id, targetId, kind, element)
    }

    private fun taskIdOf(task: PsiClass): String? {
        val qualifiedName = task.qualifiedName ?: return null
        taskRecords[qualifiedName]?.let { return it.node.id }
        if (taskRecords.size + otherNodes.size >= MAX_NODES) {
            truncated = true
            return null
        }
        val anchor = sourceAnchorOf(task)
        val node = DtfFlowTaskNode(
            id = qualifiedName,
            taskName = DtfTaskDefResolver.resolveCached(task).taskName,
            className = task.name.orEmpty(),
            qualifiedName = qualifiedName,
            isJoinTarget = false,
            isCron = DtfCronModel.cronMarkOf(task) is CronMark.Cron,
            target = navigatableFor(anchor),
        )
        taskRecords[qualifiedName] = TaskRecord(task, node)
        return qualifiedName
    }

    private fun addEdge(fromId: String, toId: String, kind: DtfFlowEdgeKind, element: PsiElement?) {
        val edge = DtfFlowEdge(
            fromId = fromId,
            toId = toId,
            kind = kind,
            target = element?.let { navigatableFor(it) },
        )
        edges.putIfAbsent(edge.key, edge)
    }

    private fun kindOf(call: UCallExpression?): DtfFlowEdgeKind = when (call?.resolve()?.name) {
        SCHEDULE_FORK -> DtfFlowEdgeKind.FORK
        SCHEDULE_IMMEDIATELY -> DtfFlowEdgeKind.IMMEDIATE
        else -> DtfFlowEdgeKind.SCHEDULE
    }

    /**
     * The declaration to open, which for a Kotlin task is the `KtClass` rather than the light class
     * standing in for it - navigating to the latter lands nowhere.
     */
    private fun sourceAnchorOf(task: PsiClass): PsiElement = task.navigationElement?.takeIf { it.isValid } ?: task

    private fun navigatableFor(element: PsiElement) = PointerNavigatable(pointers.createSmartPsiElementPointer(element))

    private fun keyOf(element: PsiElement): String {
        val path = element.containingFile?.virtualFile?.path ?: element.containingFile?.name.orEmpty()
        return "$path:${element.textRange.startOffset}"
    }
}
