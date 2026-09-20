package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.getUCallExpression
import org.jetbrains.uast.nonStructuralChildren
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/**
 * Finds the task a given call schedules - the inverse of [ScheduleCallSearcher].
 *
 * Expects to be called inside a read action, off the EDT.
 *
 * More than one task can be the answer: a `TaskDef` held in a shared holder may be returned by
 * several tasks, a conditional local carries one definition per branch, and a wrapper's parameter
 * is whatever each of its callers passes.
 */
class ScheduledTaskSearcher(private val project: Project) {

    /** How many local variables a definition may travel through, within one method, before this gives up. */
    private val maxDepth = 2

    /**
     * How many wrapper parameters a definition may be chased back through.
     *
     * Each one is a project-wide search for the wrapper's callers, and a chain of them multiplies
     * that per link, so one is what a click can afford.
     */
    private val maxParameterHops = 1

    fun findTasks(call: UCallExpression): List<PsiClass> {
        val found = LinkedHashMap<String, PsiClass>()
        val scope = GlobalSearchScope.projectScope(project)

        CallArgumentMatcher.receiverTask(call)?.let { collect(it, found) }
        for (index in launchParametersOf(call)) {
            call.getArgumentForParameter(index)?.let { collectFrom(it, found, depth = 0, hops = 0, scope = scope) }
        }

        return found.values.sortedBy { it.qualifiedName }
    }

    /**
     * The argument positions worth resolving.
     *
     * The framework always puts the definition first, but a project wrapper puts it wherever it
     * likes - `createTask(entity, taskDef)` is as common as the other way round - and may take
     * several, as a saga step scheduling two branches and their join does.
     */
    private fun launchParametersOf(call: UCallExpression): Set<Int> {
        val method = call.resolve() ?: return emptySet()
        return CallArgumentMatcher.launchParameters(method)
    }

    /**
     * The tasks named by a `TaskDef` expression.
     *
     * [nonStructuralChildren] comes first, not inside the local-variable branch: the definition is
     * just as often chosen inline, as `schedule(flag ? A.DEF : B.DEF, ctx)`, with no variable to
     * hang the branches on. Unwrapping here means a conditional is reported for both tasks
     * wherever it appears - which is the mirror of the forward direction reporting one call site
     * under two tasks.
     */
    private fun collectFrom(argument: UExpression, found: MutableMap<String, PsiClass>, depth: Int, hops: Int, scope: GlobalSearchScope) {
        if (depth > maxDepth) return
        for (child in nonStructuralChildren(argument)) {
            collectFromSingle(child, found, depth, hops, scope)
        }
    }

    /**
     * One branch of [collectFrom], in the order the shapes are cheapest to decide: the task's own
     * `getDef()`, a local variable standing in for a definition, and the definition itself.
     */
    private fun collectFromSingle(
        argument: UExpression,
        found: MutableMap<String, PsiClass>,
        depth: Int,
        hops: Int,
        scope: GlobalSearchScope,
    ) {
        ProgressManager.checkCanceled()

        if (CallArgumentMatcher.resolvesToGetDef(argument)) {
            collectSelfScheduled(argument, found, scope)
            return
        }

        val resolved = (argument as? UReferenceExpression)?.resolve() ?: return
        when {
            resolved is PsiParameter -> collectFromParameter(resolved, found, hops, scope)
            resolved is PsiLocalVariable -> collectFromLocal(resolved, found, depth, hops, scope)
            else -> collectOwnersOf(resolved, found, scope)
        }
    }

    /**
     * A wrapper's own parameter: the definition belongs to whoever called it, so the callers are
     * where to look.
     *
     * Only a parameter its method actually forwards to a scheduling call qualifies, which is what
     * keeps this off the framework's internal `schedule(TaskEntity)` and off the methods that
     * merely read a definition rather than launch it.
     *
     * The local-variable budget starts over on the caller's side: it is a different method, and its
     * own conditionals deserve the same allowance the first one had.
     */
    private fun collectFromParameter(parameter: PsiParameter, found: MutableMap<String, PsiClass>, hops: Int, scope: GlobalSearchScope) {
        if (hops >= maxParameterHops) return
        val method = parameter.declarationScope as? PsiMethod ?: return
        val index = method.parameterList.getParameterIndex(parameter)
        if (index < 0 || CallArgumentMatcher.classify(method, index) != ScheduleTier.WRAPPER) return

        for (reference in MethodReferencesSearch.search(method, scope, true).findAll()) {
            ProgressManager.checkCanceled()
            val uRef = CallArgumentMatcher.asExpression(reference.element) ?: continue
            val call = uRef.getUCallExpression() ?: continue
            val argument = call.getArgumentForParameter(index) ?: continue
            collectFrom(argument, found, depth = 0, hops = hops + 1, scope = scope)
        }
    }

    /**
     * `schedule(getDef(), ctx)`: the task is the class the call is written in.
     *
     * When that class is an abstract base the self-reschedule is inherited, so every concrete task
     * below it is a genuine answer.
     */
    private fun collectSelfScheduled(argument: UExpression, found: MutableMap<String, PsiClass>, scope: GlobalSearchScope) {
        val owner = enclosingClassOf(argument) ?: return
        collectClassOrInheritors(owner, found, scope)
    }

    /**
     * A local variable: read its initializer and carry on.
     *
     * The initializer goes back through [collectFrom], which unwraps a conditional the same way it
     * does one written straight into the argument.
     */
    private fun collectFromLocal(
        variable: PsiLocalVariable,
        found: MutableMap<String, PsiClass>,
        depth: Int,
        hops: Int,
        scope: GlobalSearchScope,
    ) {
        val uVariable = variable.toUElementOfType<UVariable>()
            ?: variable.navigationElement?.takeIf { it.isValid }?.toUElementOfType<UVariable>()
            ?: return
        val initializer = uVariable.uastInitializer ?: return
        collectFrom(initializer, found, depth + 1, hops, scope)
    }

    /**
     * The task classes whose `getDef()` hands back [declaration].
     *
     * The fast path is the common one: the constant is declared in the very task that returns it,
     * and no search is needed. It has to check the `getDef()` too, because a definition can also be
     * declared on a `Task` *interface* that several implementations share - that type is a `Task`
     * subtype without being any one task, and must fall through.
     *
     * Otherwise the definition lives in a holder, and the tasks are found by searching for the
     * references to it that sit inside a `getDef()`. Searching the references of one constant beats
     * the alternative of walking every `Task` in the project and resolving its definition.
     */
    private fun collectOwnersOf(declaration: PsiElement, found: MutableMap<String, PsiClass>, scope: GlobalSearchScope) {
        val anchors = DtfTaskDefResolver.anchorsFor(declaration)

        val owner = (declaration as? PsiMember)?.containingClass
        // The backing field of a Kotlin companion val belongs to the outer class, not the companion.
        for (candidate in listOfNotNull(owner, owner?.containingClass)) {
            if (DtfTaskModel.isMarkableTask(candidate) && definedBy(candidate, anchors)) {
                collect(candidate, found)
                return
            }
        }

        for (anchor in anchors) {
            ProgressManager.checkCanceled()
            for (reference in DtfTaskDefResolver.referencesTo(anchor, scope)) {
                ProgressManager.checkCanceled()
                val taskClass = getDefOwnerOf(reference) ?: continue
                collectClassOrInheritors(taskClass, found, scope)
            }
        }
    }

    /** Whether [taskClass] identifies itself by one of [anchors]. */
    private fun definedBy(taskClass: PsiClass, anchors: List<PsiElement>): Boolean {
        val declared = DtfTaskDefResolver.resolve(taskClass).anchors
        return declared.any { own -> anchors.any { isSameDeclaration(own, it) } }
    }

    /**
     * Whether two handles denote the same declaration.
     *
     * A Kotlin `val` is reachable as a light field, as its generated accessor and as the `KtProperty`
     * behind both, and which one you get depends on who resolved it.
     */
    private fun isSameDeclaration(one: PsiElement, other: PsiElement): Boolean = one === other ||
        one.navigationElement === other.navigationElement ||
        one.isEquivalentTo(other)

    /** The class of the `getDef()` that [reference] sits in, if it sits in one. */
    private fun getDefOwnerOf(reference: PsiElement): PsiClass? {
        val start = reference.toUElement() ?: CallArgumentMatcher.asExpression(reference) ?: return null
        val enclosing = generateSequence<UElement>(start) { it.uastParent }
        val method = enclosing.filterIsInstance<UMethod>().firstOrNull() ?: return null
        if (method.name != DtfFqns.GET_DEF || method.uastParameters.isNotEmpty()) return null
        return enclosing.filterIsInstance<UClass>().firstOrNull()?.javaPsi
    }

    /**
     * [psiClass] when it is a task in its own right, and every concrete task below it when it is
     * the abstract base that holds the `getDef()` or the self-reschedule.
     */
    private fun collectClassOrInheritors(psiClass: PsiClass, found: MutableMap<String, PsiClass>, scope: GlobalSearchScope) {
        if (DtfTaskModel.isMarkableTask(psiClass)) {
            collect(psiClass, found)
            return
        }
        // Guard against fanning out from something that is not a task at all.
        if (!DtfTaskModel.isDtfTask(psiClass)) return
        for (inheritor in ClassInheritorsSearch.search(psiClass, scope, true).findAll()) {
            ProgressManager.checkCanceled()
            if (DtfTaskModel.isMarkableTask(inheritor)) collect(inheritor, found)
        }
    }

    private fun enclosingClassOf(element: UElement): PsiClass? = generateSequence<UElement>(element) { it.uastParent }
        .filterIsInstance<UClass>()
        .firstOrNull()
        ?.javaPsi

    private fun collect(taskClass: PsiClass, found: MutableMap<String, PsiClass>) {
        val key = taskClass.qualifiedName ?: return
        found.putIfAbsent(key, taskClass)
    }
}
