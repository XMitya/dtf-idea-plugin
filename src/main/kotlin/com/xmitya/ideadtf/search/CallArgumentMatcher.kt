package com.xmitya.ideadtf.search

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.toUElementOfType

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

    /** A call, together with the method it resolves to, for which [uRef] is argument 0. */
    data class Arg0Call(val call: UCallExpression, val method: PsiMethod)

    fun asExpression(psi: PsiElement): UExpression? =
        psi.toUElementOfType<UExpression>() ?: getUParentForIdentifier(psi) as? UExpression

    /**
     * The call that receives [uRef] as its first argument, if any.
     *
     * Climbing rather than matching a fixed set of wrapper types is what makes ternaries, `if` and
     * `when` expressions, casts, parentheses and qualified references all work for free.
     *
     * Containment is checked on the underlying PSI, not on the UAST nodes: UAST wrappers are
     * rebuilt on access, so two handles on the same expression are not the same object. Comparing
     * PSI also covers the case where the reference sits *inside* argument 0 rather than being it.
     *
     * The argument is looked up with [UCallExpression.getArgumentForParameter] rather than by
     * indexing `valueArguments`, which is source order and would break on Kotlin named arguments.
     */
    fun arg0CallFor(uRef: UElement): Arg0Call? {
        val referencePsi = uRef.sourcePsi ?: return null
        var current: UElement = uRef
        repeat(MAX_CLIMB) {
            val parent = current.uastParent ?: return null
            if (parent is UCallExpression) {
                val method = parent.resolve() ?: return null
                val arg0 = parent.getArgumentForParameter(0)
                    ?: parent.valueArguments.firstOrNull()
                    ?: return null
                val arg0Psi = arg0.sourcePsi ?: return null
                return if (PsiTreeUtil.isAncestor(arg0Psi, referencePsi, false)) {
                    Arg0Call(parent, method)
                } else {
                    null
                }
            }
            current = parent
        }
        return null
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
            current = parent
        }
        return null
    }

    /**
     * Whether [method] launches a task, and how.
     *
     * A call on the framework service counts only when it is one of the scheduling methods - the
     * service has plenty of other methods taking a `TaskDef`, such as `cancelAllTaskByTaskDef`,
     * which must not be reported as a launch.
     *
     * Anything else taking a `TaskDef` first is treated as a project-local wrapper. Keying on the
     * parameter type rather than the method name catches the hand-rolled scheduler facades that
     * every other service seems to grow.
     */
    fun classify(method: PsiMethod): ScheduleTier? {
        val owner = method.containingClass ?: return null
        val qualifiedName = owner.qualifiedName
        val isFrameworkService = qualifiedName == DtfFqns.TASK_COMMAND_SERVICE ||
            qualifiedName == DtfFqns.DISTRIBUTED_TASK_SERVICE ||
            InheritanceUtil.isInheritor(owner, DtfFqns.TASK_COMMAND_SERVICE)
        if (isFrameworkService) {
            return if (method.name in DtfFqns.SCHEDULE_METHODS) ScheduleTier.SERVICE else null
        }
        val firstParameter = method.parameterList.parameters.firstOrNull() ?: return null
        return if (InheritanceUtil.isInheritor(firstParameter.type, DtfFqns.TASK_DEF)) {
            ScheduleTier.WRAPPER
        } else {
            null
        }
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
     * The task a call's receiver is declared as, if it is one.
     *
     * Some bases expose their own `schedule(message)` and fill in the `TaskDef` internally, so the
     * call mentions no definition at all and the receiver is the only thing naming the task. A
     * receiver typed as the shared base is not one task and yields nothing.
     */
    fun receiverTask(call: UCallExpression): PsiClass? {
        val receiverType = call.receiverType ?: return null
        val psiClass = PsiUtil.resolveClassInClassTypeOnly(receiverType) ?: return null
        return psiClass.takeIf { DtfTaskModel.isMarkableTask(it) }
    }
}
