package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.nonStructuralChildren
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Decides whether a reference to a `TaskDef` sits in a position that actually launches a task.
 *
 * Everything here is pure UAST, so it behaves the same for Java and Kotlin.
 */
object CallArgumentMatcher {

    /**
     * How far to climb out of a reference before giving up. Enough for the wrappers that occur in
     * practice - casts, parentheses, qualified references, conditionals - without walking a whole
     * method body.
     */
    private const val MAX_CLIMB = 8

    /**
     * How many wrappers a `TaskDef` parameter may be forwarded through before [forwardingParameters]
     * gives up. Two covers a project facade layered over the framework service, which is as deep as
     * these chains get.
     */
    private const val MAX_FORWARD_DEPTH = 2

    /**
     * How many methods called on `this` a task's scheduling helper may go through before it reaches
     * the call that hands over `getDef()`. The shared bases chain their overloads -
     * `schedule(message)` to `schedule(message, Duration.ZERO)` to the real one - and a task adds
     * its own on top.
     */
    private const val MAX_SELF_LAUNCH_DEPTH = 4

    /** A call, the method it resolves to, and the parameter the reference fills. */
    data class ScheduleArgCall(val call: UCallExpression, val method: PsiMethod, val parameterIndex: Int)

    fun asExpression(psi: PsiElement): UExpression? = psi.toUElementOfType<UExpression>() ?: getUParentForIdentifier(psi) as? UExpression

    /**
     * The call that receives [uRef] as one of its arguments, together with which one.
     *
     * Climbing rather than matching a fixed set of wrapper types is what makes ternaries, `if` and
     * `when` expressions, casts, parentheses and qualified references all work for free.
     *
     * The climb stops at a value boundary - see [isValueBoundary]. Without that, a definition chosen
     * inside a lambda body reaches the call the *lambda* is an argument of, which is some unrelated
     * `forEach`, and the reference is charged to it instead of being followed further.
     *
     * Containment is checked on the underlying PSI, not on the UAST nodes: UAST wrappers are
     * rebuilt on access, so two handles on the same expression are not the same object. Comparing
     * PSI also covers the case where the reference sits *inside* an argument rather than being it.
     */
    fun argCallFor(uRef: UElement): ScheduleArgCall? {
        val referencePsi = uRef.sourcePsi ?: return null
        var current: UElement = uRef
        repeat(MAX_CLIMB) {
            val parent = current.uastParent ?: return null
            if (parent is UCallExpression) {
                val method = parent.resolve() ?: return null
                val index = argumentIndexOf(parent, method.parameterList.parametersCount, referencePsi) ?: return null
                return ScheduleArgCall(parent, method, index)
            }
            if (isValueBoundary(parent)) return null
            current = parent
        }
        return null
    }

    /**
     * Where a climb has to stop, because past it the reference no longer flows into the enclosing
     * call as an argument.
     *
     * A lambda body is a value in its own right, not a path into whatever receives the lambda, and a
     * variable initializer belongs to the variable hop rather than to any surrounding call.
     */
    private fun isValueBoundary(element: UElement): Boolean =
        element is UVariable || element is ULambdaExpression || element is UMethod || element is UClass

    /**
     * Which argument of [call] contains [referencePsi].
     *
     * Arguments are looked up with [UCallExpression.getArgumentForParameter] rather than by indexing
     * `valueArguments`, which is source order and would break on Kotlin named arguments. Source
     * order is the fallback for the calls the parameter list does not describe, such as a vararg
     * spread.
     */
    private fun argumentIndexOf(call: UCallExpression, parameterCount: Int, referencePsi: PsiElement): Int? {
        for (index in 0 until parameterCount) {
            val argumentPsi = call.getArgumentForParameter(index)?.sourcePsi ?: continue
            if (PsiTreeUtil.isAncestor(argumentPsi, referencePsi, false)) return index
        }
        val fallback = call.valueArguments.indexOfFirst {
            it.sourcePsi?.let { psi -> PsiTreeUtil.isAncestor(psi, referencePsi, false) } == true
        }
        return fallback.takeIf { it in 0 until parameterCount }
    }

    /** The local variable whose initializer contains [uRef], if any. */
    fun initializedVariableFor(uRef: UElement): UVariable? {
        val referencePsi = uRef.sourcePsi ?: return null
        var current: UElement = uRef
        repeat(MAX_CLIMB) {
            val parent = current.uastParent ?: return null
            if (parent is UVariable) {
                val initializerPsi = parent.uastInitializer?.sourcePsi ?: return null
                return if (PsiTreeUtil.isAncestor(initializerPsi, referencePsi, false)) parent else null
            }
            // A reference inside a lambda belongs to the lambda, not to the variable the lambda
            // itself is assigned to.
            if (isValueBoundary(parent)) return null
            current = parent
        }
        return null
    }

    /**
     * Whether [method] launches a task through its parameter at [parameterIndex], and how.
     *
     * A call on the framework service counts only when it is one of the scheduling methods - the
     * service has plenty of other methods taking a `TaskDef`, such as `cancelAllTaskByTaskDef`,
     * which must not be reported as a launch - and only through parameter 0, where the framework
     * always puts the definition.
     *
     * Anything else is a project-local wrapper, recognised by what it does with the definition
     * rather than by its name or by the position it takes it in.
     */
    fun classify(method: PsiMethod, parameterIndex: Int): ScheduleTier? {
        val owner = method.containingClass ?: return null
        if (isFrameworkService(owner)) {
            return if (parameterIndex == 0 && method.name in DtfFqns.SCHEDULE_METHODS) ScheduleTier.SERVICE else null
        }
        return if (parameterIndex in forwardingParameters(method)) ScheduleTier.WRAPPER else null
    }

    /** Every parameter of [method] that names the task the call launches. */
    fun launchParameters(method: PsiMethod): Set<Int> {
        val owner = method.containingClass ?: return emptySet()
        if (isFrameworkService(owner)) {
            return if (method.name in DtfFqns.SCHEDULE_METHODS) setOf(0) else emptySet()
        }
        return forwardingParameters(method)
    }

    /** Whether [parameter] is a `TaskDef` its own method forwards to a scheduling call. */
    fun isForwardedTaskDefParameter(parameter: PsiParameter): Boolean {
        val method = parameter.declarationScope as? PsiMethod ?: return false
        val index = method.parameterList.getParameterIndex(parameter)
        return index >= 0 && index in forwardingParameters(method)
    }

    private fun isFrameworkService(owner: PsiClass): Boolean {
        val qualifiedName = owner.qualifiedName
        return qualifiedName == DtfFqns.TASK_COMMAND_SERVICE ||
            qualifiedName == DtfFqns.DISTRIBUTED_TASK_SERVICE ||
            InheritanceUtil.isInheritor(owner, DtfFqns.TASK_COMMAND_SERVICE)
    }

    /**
     * The parameters of [method] that take a `TaskDef` and actually forward it to a scheduling call
     * in the method's own body.
     *
     * The declared type alone is not enough. Plenty of project methods accept a `TaskDef` to log its
     * name, to build a metric tag or to look something up, and reporting those as launch sites is
     * noise. Requiring the parameter to be forwarded is also what makes accepting a definition in
     * *any* position safe, rather than only in the first one.
     *
     * Cached per method: this runs during highlighting, where it must not be recomputed for every
     * call in the file.
     */
    private fun forwardingParameters(method: PsiMethod): Set<Int> =
        CachedValuesManager.getProjectPsiDependentCache(method) { computeForwardingParameters(it, MAX_FORWARD_DEPTH, HashSet()) }

    private fun computeForwardingParameters(method: PsiMethod, depth: Int, seen: MutableSet<PsiMethod>): Set<Int> {
        if (depth <= 0 || !seen.add(method)) return emptySet()

        val taskDefParameters = method.parameterList.parameters
            .withIndex()
            .filter { (_, parameter) -> InheritanceUtil.isInheritor(parameter.type, DtfFqns.TASK_DEF) }
        if (taskDefParameters.isEmpty()) return emptySet()

        val body = asSourceUMethod(method) ?: return emptySet()
        val forwarding = LinkedHashSet<Int>()
        body.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                val callee = node.resolve() ?: return false
                for ((index, parameter) in taskDefParameters) {
                    if (index in forwarding) continue
                    val calleeIndex = argumentIndexFor(node, callee, parameter) ?: continue
                    if (launches(callee, calleeIndex, depth - 1, seen)) forwarding += index
                }
                return false
            }
        })
        return forwarding
    }

    /** Whether [callee] launches a task through the argument it receives at [parameterIndex]. */
    private fun launches(callee: PsiMethod, parameterIndex: Int, depth: Int, seen: MutableSet<PsiMethod>): Boolean {
        val owner = callee.containingClass ?: return false
        if (isFrameworkService(owner)) {
            return parameterIndex == 0 && callee.name in DtfFqns.SCHEDULE_METHODS
        }
        return parameterIndex in computeForwardingParameters(callee, depth, seen)
    }

    /** Which argument of [call] hands [parameter] on, if any. */
    private fun argumentIndexFor(call: UCallExpression, callee: PsiMethod, parameter: PsiParameter): Int? {
        for (index in 0 until callee.parameterList.parametersCount) {
            val argument = call.getArgumentForParameter(index) ?: continue
            val matches = nonStructuralChildren(argument).anyMatch { child ->
                (child as? UReferenceExpression)?.resolve()?.let { isSameParameter(it, parameter) } == true
            }
            if (matches) return index
        }
        return null
    }

    /**
     * Whether [resolved] denotes [parameter].
     *
     * A Kotlin parameter is reachable as the light method's parameter, as a synthetic UAST one and
     * as the `KtParameter` behind both, and which one you get depends on who resolved it.
     */
    private fun isSameParameter(resolved: PsiElement, parameter: PsiParameter): Boolean {
        if (resolved === parameter) return true
        val parameterSource = parameter.navigationElement ?: parameter
        if ((resolved.navigationElement ?: resolved) === parameterSource) return true
        if (resolved.toUElement()?.sourcePsi === parameterSource) return true
        return resolved.isEquivalentTo(parameter)
    }

    private fun asSourceUMethod(method: PsiMethod): UMethod? {
        val source = method.navigationElement?.takeIf { it.isValid } ?: method
        return source.toUElementOfType<UMethod>() ?: method.toUElementOfType<UMethod>()
    }

    /**
     * Whether [argument] is the task's own `getDef()`.
     *
     * Written as `getDef()` in Java and read as the `def` property in Kotlin, which resolves to the
     * same accessor - hence matching the resolved method rather than the source text.
     */
    fun resolvesToGetDef(argument: UElement): Boolean {
        val resolved = (argument as? UReferenceExpression)?.resolve()
            ?: (argument as? UCallExpression)?.resolve()
        return resolved is PsiMethod && resolved.name == DtfFqns.GET_DEF && resolved.parameterList.isEmpty
    }

    /**
     * The task a call's receiver is declared as, if it is one and the call launches it.
     *
     * Some bases expose their own `schedule(message)` and fill in the `TaskDef` internally, so the
     * call mentions no definition at all and the receiver is the only thing naming the task. A
     * receiver typed as the shared base is not one task and yields nothing.
     *
     * The method has to be one that launches its receiver - see [launchesOwnTask]. A task's own
     * `scheduleFileProcessing(file)` called on `this` has a task receiver just the same, and it
     * launches the *next* task, not this one.
     */
    fun receiverTask(call: UCallExpression): PsiClass? {
        val receiverType = call.receiverType ?: return null
        val psiClass = PsiUtil.resolveClassInClassTypeOnly(receiverType) ?: return null
        if (!DtfTaskModel.isMarkableTask(psiClass)) return null
        val method = call.resolve() ?: return null
        return psiClass.takeIf { launchesOwnTask(method) }
    }

    /**
     * Whether [method] launches the task it is called on: its body hands the task's own `getDef()`
     * to a scheduling call, directly or through another method called on `this`.
     *
     * Neither the name nor the owner tells this apart from a task launching something else - a
     * public `scheduleFileProcessing(file)` on one task is as much a `schedule*` method of a task as
     * the bases' `schedule(message)` is. Only the body does.
     *
     * Cached per method: this runs during highlighting, for every scheduling-named call on a task.
     */
    fun launchesOwnTask(method: PsiMethod): Boolean = CachedValuesManager.getProjectPsiDependentCache(method) {
        computeLaunchesOwnTask(it, MAX_SELF_LAUNCH_DEPTH, HashSet())
    }

    private fun computeLaunchesOwnTask(method: PsiMethod, depth: Int, seen: MutableSet<PsiMethod>): Boolean {
        if (depth <= 0 || !seen.add(method)) return false
        val body = asSourceUMethod(method) ?: return false
        var launches = false
        body.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                if (launches) return true
                val callee = node.resolve() ?: return false
                launches = passesOwnDef(node, callee) || (isOnThis(node) && computeLaunchesOwnTask(callee, depth - 1, seen))
                return false
            }
        })
        return launches
    }

    /** Whether [call] hands `this` task's `getDef()` to a parameter through which [callee] launches a task. */
    private fun passesOwnDef(call: UCallExpression, callee: PsiMethod): Boolean = launchParameters(callee).any { index ->
        val argument = call.getArgumentForParameter(index)
        argument != null && nonStructuralChildren(argument).anyMatch { resolvesToGetDef(it) && isOnThis(it) }
    }

    /**
     * Whether [expression] is addressed to the object whose code it is written in: unqualified, or
     * qualified by `this`. `otherTask.def` names another task, and `otherTask.schedule(...)`
     * launches it.
     */
    private fun isOnThis(expression: UExpression): Boolean {
        val receiver = when (expression) {
            is UQualifiedReferenceExpression -> expression.receiver
            is UCallExpression -> expression.receiver
            else -> null
        }
        return receiver == null || receiver is UThisExpression
    }
}
