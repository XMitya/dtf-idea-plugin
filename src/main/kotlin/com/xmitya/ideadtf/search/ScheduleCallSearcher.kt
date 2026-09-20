package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskHierarchy
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.getUCallExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Finds every place a given task is scheduled.
 *
 * Expects to be called inside a read action, off the EDT.
 */
class ScheduleCallSearcher(private val project: Project) {

    /** One hop through a local variable, and one hop through a wrapper's parameter. */
    private val maxDepth = 2

    fun findScheduleSites(taskClass: PsiClass): List<ScheduleCallSite> {
        val found = LinkedHashMap<Pair<String, Int>, ScheduleCallSite>()
        val scope = GlobalSearchScope.projectScope(project)

        val taskDef = DtfTaskDefResolver.resolve(taskClass)
        for (anchor in taskDef.anchors) {
            collectFrom(DtfTaskDefResolver.referencesTo(anchor, scope), found, depth = 0, scope = scope)
        }
        collectSelfSchedules(taskClass, found)
        collectSchedulableBeanCalls(taskClass, found, scope)

        return found.values.sortedWith(compareBy({ it.tier.ordinal }, { it.key.first }, { it.key.second }))
    }

    /**
     * Turns raw references into call sites, following at most [maxDepth] hops of trivial dataflow.
     *
     * Two hops are needed by real code: a reference assigned to a local variable that is then
     * scheduled (frequently via a conditional, which is why one call site can belong to two tasks),
     * and a reference handed to a project-local helper that forwards it to the framework.
     *
     * The definition may sit at any argument position of such a helper; only the framework's own
     * methods are pinned to the first one.
     */
    private fun collectFrom(
        references: List<PsiElement>,
        found: MutableMap<Pair<String, Int>, ScheduleCallSite>,
        depth: Int,
        scope: GlobalSearchScope,
    ) {
        if (depth > maxDepth) return
        for (reference in references) {
            ProgressManager.checkCanceled()
            val uRef = CallArgumentMatcher.asExpression(reference) ?: continue

            val argCall = CallArgumentMatcher.argCallFor(uRef)
            if (argCall != null) {
                val tier = CallArgumentMatcher.classify(argCall.method, argCall.parameterIndex)
                if (tier != null) {
                    addSite(argCall.call, tier, found)
                    continue
                }
            }

            // A call that does not launch anything is not the end of the road: the reference may
            // still be on its way into a local that a scheduling call reads further down.
            val variable = CallArgumentMatcher.initializedVariableFor(uRef) ?: continue
            collectFrom(readsOf(variable), found, depth + 1, scope)
        }
    }

    /** Every read of [variable] within the method that declares it, in Java and Kotlin alike. */
    private fun readsOf(variable: UVariable): List<PsiElement> {
        val declaration = variable.sourcePsi ?: return emptyList()
        val enclosingMethod = generateSequence<UElement>(variable) { it.uastParent }
            .filterIsInstance<UMethod>()
            .firstOrNull()
            ?: return emptyList()

        val reads = mutableListOf<PsiElement>()
        enclosingMethod.accept(object : AbstractUastVisitor() {
            override fun visitElement(node: UElement): Boolean {
                ProgressManager.checkCanceled()
                if (node is UReferenceExpression && UastDeclarations.denotes(node.resolve(), declaration)) {
                    node.sourcePsi?.let { reads += it }
                }
                return false
            }
        })
        return reads
    }

    /**
     * Self-rescheduling: `schedule(getDef(), ...)`, or `schedule(def, ...)` in Kotlin.
     *
     * Such calls live inside the task class or one of its base classes by definition, so the
     * supertype closure is scanned directly instead of searching the project for `getDef`
     * references. That is both cheaper and more precise: a global search would have to be filtered
     * anyway, because `task.def` on some *other* task must not be attributed here.
     */
    private fun collectSelfSchedules(taskClass: PsiClass, found: MutableMap<Pair<String, Int>, ScheduleCallSite>) {
        for (owner in DtfTaskHierarchy.supertypeClosure(taskClass)) {
            val uClass = DtfTaskHierarchy.asSourceUClass(owner) ?: continue
            uClass.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    ProgressManager.checkCanceled()
                    val method = node.resolve() ?: return false
                    val tier = CallArgumentMatcher.classify(method, 0) ?: return false
                    val arg0 = node.getArgumentForParameter(0) ?: return false
                    if (CallArgumentMatcher.resolvesToGetDef(arg0)) {
                        addSite(node, tier, found)
                    }
                    return false
                }
            })
        }
    }

    /**
     * Tasks that schedule themselves: `sendVkNotificationTask.schedule(dto, affinityKey)`.
     *
     * Some base classes expose their own `schedule` that fills in the `TaskDef` internally, so the
     * call site mentions no `TaskDef` at all and is invisible to the reference search. Such a call
     * is attributed to this task when the receiver's declared type is this task or a subclass of
     * it; a receiver typed as the shared base cannot be attributed to any one task and is skipped.
     */
    private fun collectSchedulableBeanCalls(
        taskClass: PsiClass,
        found: MutableMap<Pair<String, Int>, ScheduleCallSite>,
        scope: GlobalSearchScope,
    ) {
        val taskFqn = taskClass.qualifiedName ?: return
        for (method in DtfTaskHierarchy.schedulingHelpersOf(taskClass)) {
            ProgressManager.checkCanceled()
            for (reference in MethodReferencesSearch.search(method, scope, true).findAll()) {
                ProgressManager.checkCanceled()
                val uRef = CallArgumentMatcher.asExpression(reference.element) ?: continue
                val call = uRef.getUCallExpression() ?: continue
                val receiverType = call.receiverType ?: continue
                if (InheritanceUtil.isInheritor(receiverType, taskFqn)) {
                    addSite(call, ScheduleTier.WRAPPER, found)
                }
            }
        }
    }

    private fun addSite(call: UCallExpression, tier: ScheduleTier, found: MutableMap<Pair<String, Int>, ScheduleCallSite>) {
        val psi = call.sourcePsi ?: return
        val site = ScheduleCallSite(psi, tier)
        found.putIfAbsent(site.key, site)
    }
}
