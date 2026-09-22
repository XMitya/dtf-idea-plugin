package com.xmitya.ideadtf.marker

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.search.CallArgumentMatcher
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.getUParentForIdentifier
import org.jetbrains.uast.nonStructuralChildren
import org.jetbrains.uast.toUElement

/**
 * Recognises a call that schedules a task, from the leaf the gutter marker anchors on.
 *
 * Shared between the schedule-call line marker provider and its navigation handler, mirroring
 * [DtfTaskMarkers] on the other side of the feature.
 *
 * Everything here runs inside the highlighting pass, so it must stay local: it resolves, but it
 * never searches the project. Working out *which* task a recognised call launches is deferred to
 * the click.
 */
object DtfScheduleMarkers {

    private const val SCHEDULE_PREFIX = "schedule"

    /** How far above the identifier the call can sit: reference, qualifier, call. */
    private const val MAX_CLIMB = 4

    /**
     * The scheduling call whose method name [element] is, if it is one.
     *
     * A call is only reported when the task it launches could plausibly be named at this site -
     * see [identifiesATask]. An icon that can never lead anywhere is worse than no icon.
     */
    fun scheduleCallAt(element: PsiElement): UCallExpression? {
        if (element.firstChild != null) return null
        // Childless is not the same as a leaf of a source file: an editor popup hands over whatever
        // the caret resolves to, and that can be a fake element standing in for a reference target -
        // a config key in a YAML file is one - whose text is null however non-null getText() looks.
        val text = element.text ?: return null
        // A pure string test, so unrelated calls cost nothing. It also excludes the framework's
        // other TaskDef-taking methods - rescheduleByTaskDef, cancelAllTaskByTaskDef - for free.
        if (!text.startsWith(SCHEDULE_PREFIX, ignoreCase = true)) return null

        val call = callAt(element) ?: return null
        // A Kotlin constructor is a UCallExpression too, and `ScheduleRequest(DEF, ctx)` otherwise
        // looks exactly like a wrapper: named for scheduling, TaskDef first.
        if (call.kind != UastCallKind.METHOD_CALL) return null
        if (!isMethodNameOf(call, element)) return null
        return call.takeIf { launchesATask(it) }
    }

    /**
     * The call [element] belongs to.
     *
     * Climbing a few parents rather than converting directly: a method name maps to a reference
     * expression in Kotlin and to the call itself in Java, and a qualified call adds another level.
     * Climbing too far is harmless because [isMethodNameOf] rejects anything that is not the name.
     */
    private fun callAt(element: PsiElement): UCallExpression? {
        val start = element.toUElement() ?: getUParentForIdentifier(element) ?: return null
        return generateSequence<UElement>(start) { it.uastParent }
            .take(MAX_CLIMB)
            .filterIsInstance<UCallExpression>()
            .firstOrNull()
    }

    /**
     * Whether [element] is the name of [call] rather than its receiver or one of its arguments.
     *
     * Without this every identifier in `service.schedule(DEF, ctx)` climbs to the same call and the
     * line collects three identical icons - the same trap the task marker avoids by comparing
     * against the class's UAST anchor.
     */
    private fun isMethodNameOf(call: UCallExpression, element: PsiElement): Boolean {
        val identifier = call.methodIdentifier?.sourcePsi ?: return false
        return PsiTreeUtil.isAncestor(identifier, element, false)
    }

    /**
     * Whether the task launched here can be named from this call site alone.
     *
     * Public because the flow diagram asks the same question of every call in a task's body: one
     * predicate, so the gutter and the diagram can never disagree about what a schedule call is.
     *
     * Two ways in. Either the receiver is itself a task, which is how the bases that expose their
     * own `schedule(message)` work and where no `TaskDef` appears at the call at all; or the call
     * launches a task through one of its arguments and that argument is something we can follow.
     *
     * Which argument is asked of [CallArgumentMatcher.launchParameters] rather than assumed to be
     * the first: the framework pins the definition to position 0, a project wrapper does not.
     */
    fun launchesATask(call: UCallExpression): Boolean {
        // A receiver that is a task names the task by itself - but only when the call is a scheduling
        // one. Without that guard any helper called on `this` inside a task, `ids()` or `getDef()`,
        // reads as `someTask.schedule(message)` and the task appears to schedule itself.
        if (CallArgumentMatcher.receiverTask(call) != null && isNamedLikeSchedule(call)) return true
        val method = call.resolve() ?: return false
        return CallArgumentMatcher.launchParameters(method).any { index ->
            val argument = call.getArgumentForParameter(index)
            // The definition can be chosen inline - `schedule(flag ? A.DEF : B.DEF, ctx)` - so the
            // conditional has to be unwrapped before anything can be resolved.
            argument != null && nonStructuralChildren(argument).anyMatch { isTaskDefDeclaration(it) }
        }
    }

    private fun isNamedLikeSchedule(call: UCallExpression): Boolean =
        (call.methodName ?: call.resolve()?.name)?.startsWith(SCHEDULE_PREFIX, ignoreCase = true) == true

    private fun isTaskDefDeclaration(argument: UExpression): Boolean {
        if (CallArgumentMatcher.resolvesToGetDef(argument)) return true
        return when (val resolved = (argument as? UReferenceExpression)?.resolve()) {
            // A definition arriving as a parameter is whatever the caller passed, so the answer is
            // not here - but a wrapper that forwards it has callers to ask, and the click does. A
            // parameter its own method does not forward leads nowhere and gets no icon, which is
            // also what keeps the framework-internal `schedule(TaskEntity)` out.
            is PsiParameter -> CallArgumentMatcher.isForwardedTaskDefParameter(resolved)

            // Covers a Java constant and a Kotlin local alike; Kotlin locals resolve to a synthetic
            // variable, which is still a PsiVariable with the declared type.
            is PsiVariable -> InheritanceUtil.isInheritor(resolved.type, DtfFqns.TASK_DEF)

            // Reading a Kotlin `val` resolves to its generated accessor rather than to a field.
            is PsiMethod ->
                resolved.parameterList.isEmpty &&
                    resolved.returnType?.let { InheritanceUtil.isInheritor(it, DtfFqns.TASK_DEF) } == true

            else -> false
        }
    }
}
