package com.xmitya.ideadtf.flow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.search.ScheduledTaskSearcher
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Works out which tasks a `scheduleJoin(...)` waits for.
 *
 * A join is the only place DTF's flow branches in a way the code states outright, and it states it
 * only here: nothing marks the join task itself, and the branches are whatever produced the `TaskId`s
 * in the `joinList`. So this reads the list.
 *
 * Three tiers, in descending confidence:
 *
 * 1. the argument itself - `List.of(a, b)` inline, or a local whose initializer and `add(...)` calls
 *    lead back to scheduling calls;
 * 2. every scheduling call in the same method, which is where the framework requires the branches to
 *    be scheduled anyway, and which catches the collection idioms tier 1 cannot follow - a
 *    `stream(...).toList()`, or `schedule(...).apply(joinList::add)`;
 * 3. every scheduling call in the enclosing class, for the case where the list is assembled several
 *    levels deep inside a helper. That one is a guess, and says so through [Resolved.approximate].
 *
 * Expects to be called inside a read action.
 */
class JoinBranchResolver(private val project: Project) {

    private companion object {
        /** How many locals a `TaskId` may travel through before this gives up. */
        const val MAX_LOCAL_DEPTH = 3

        val LIST_APPENDERS = setOf("add", "addAll")

        const val LIST_FQN = "java.util.List"
    }

    private val searcher = ScheduledTaskSearcher(project)

    /**
     * @param joinTasks what the join schedules - normally one, several when the definition is shared.
     * @param branches the tasks it waits for.
     * @param approximate whether [branches] was inferred from the enclosing class rather than read
     *   off the `joinList`.
     */
    class Resolved(val joinTasks: List<PsiClass>, val branches: List<PsiClass>, val approximate: Boolean)

    fun resolve(call: UCallExpression): Resolved? {
        val method = call.resolve() ?: return null
        if (method.name != DtfFqns.SCHEDULE_JOIN) return null

        val joinTasks = searcher.findTasks(call)
        val found = LinkedHashMap<String, UCallExpression>()
        var approximate = false

        joinListArgumentOf(call, method)?.let { collectFromArgument(it, enclosingMethod(call), found, HashSet(), depth = 0) }
        if (found.isEmpty()) {
            collectFrom(enclosingMethod(call), call, found)
        }
        if (found.isEmpty()) {
            approximate = true
            collectFrom(enclosingClass(call), call, found)
        }

        val branches = found.values
            .flatMap { searcher.findTasks(it) }
            .distinctBy { it.qualifiedName }
            .filter { branch -> joinTasks.none { it.qualifiedName == branch.qualifiedName } }
        return Resolved(joinTasks, branches, approximate)
    }

    /** The `List<TaskId>` argument, found by type rather than by position. */
    private fun joinListArgumentOf(call: UCallExpression, method: PsiMethod): UExpression? {
        val parameters = method.parameterList.parameters
        val index = parameters.indexOfFirst { InheritanceUtil.isInheritor(it.type, LIST_FQN) }
            .takeIf { it >= 0 }
            ?: parameters.lastIndex.takeIf { it >= 0 }
            ?: return null
        return call.getArgumentForParameter(index)
    }

    // --- tier 1: read the list ------------------------------------------------------------------

    private fun collectFromArgument(
        argument: UExpression,
        enclosing: UMethod?,
        found: MutableMap<String, UCallExpression>,
        seen: MutableSet<PsiElement>,
        depth: Int,
    ) {
        if (depth > MAX_LOCAL_DEPTH) return
        argument.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                if (DtfScheduleMarkers.launchesATask(node)) remember(node, found)
                return false
            }

            override fun visitElement(node: UElement): Boolean {
                if (node is UReferenceExpression && node !is UCallExpression) {
                    followLocal(node, enclosing, found, seen, depth)
                }
                return false
            }
        })
    }

    /**
     * A `TaskId` held in a local: its initializer is the scheduling call, and in the loop idiom the
     * list is filled by `list.add(taskId)`, which is another local to follow.
     */
    private fun followLocal(
        reference: UReferenceExpression,
        enclosing: UMethod?,
        found: MutableMap<String, UCallExpression>,
        seen: MutableSet<PsiElement>,
        depth: Int,
    ) {
        val resolved = reference.resolve() as? PsiVariable ?: return
        val declaration = resolved.navigationElement?.takeIf { it.isValid } ?: resolved
        if (!seen.add(declaration)) return

        val variable = resolved.toUElementOfType<UVariable>() ?: declaration.toUElementOfType<UVariable>()
        variable?.uastInitializer?.let { collectFromArgument(it, enclosing, found, seen, depth + 1) }
        appendedValues(declaration, enclosing).forEach { collectFromArgument(it, enclosing, found, seen, depth + 1) }
    }

    /** Everything handed to `<declaration>.add(...)` within the method that declares it. */
    private fun appendedValues(declaration: PsiElement, enclosing: UMethod?): List<UExpression> {
        val method = enclosing ?: return emptyList()
        val values = mutableListOf<UExpression>()
        method.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                if (node.methodName in LIST_APPENDERS && receiverDenotes(node, declaration)) {
                    values += node.valueArguments
                }
                return false
            }
        })
        return values
    }

    private fun receiverDenotes(call: UCallExpression, declaration: PsiElement): Boolean {
        val receiver = call.receiver as? UReferenceExpression ?: return false
        val resolved = receiver.resolve() ?: return false
        return resolved === declaration ||
            resolved.navigationElement === declaration ||
            resolved.toUElement()?.sourcePsi === declaration
    }

    // --- tiers 2 and 3: infer from the surrounding code -----------------------------------------

    private fun collectFrom(owner: UElement?, joinCall: UCallExpression, found: MutableMap<String, UCallExpression>) {
        val root = owner ?: return
        val joinPsi = joinCall.sourcePsi
        root.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                if (node.sourcePsi === joinPsi) return false
                // A different join in the same body is not evidence of a branch of this one.
                if (node.resolve()?.name == DtfFqns.SCHEDULE_JOIN) return false
                if (DtfScheduleMarkers.launchesATask(node)) remember(node, found)
                return false
            }
        })
    }

    // --- shared -------------------------------------------------------------------------------

    private fun remember(call: UCallExpression, found: MutableMap<String, UCallExpression>) {
        val psi = call.sourcePsi ?: return
        val path = psi.containingFile?.virtualFile?.path ?: psi.containingFile?.name.orEmpty()
        found.putIfAbsent("$path:${psi.textRange.startOffset}", call)
    }

    private fun enclosingMethod(call: UCallExpression): UMethod? =
        generateSequence<UElement>(call) { it.uastParent }.filterIsInstance<UMethod>().firstOrNull()

    private fun enclosingClass(call: UCallExpression): UClass? =
        generateSequence<UElement>(call) { it.uastParent }.filterIsInstance<UClass>().firstOrNull()
}
