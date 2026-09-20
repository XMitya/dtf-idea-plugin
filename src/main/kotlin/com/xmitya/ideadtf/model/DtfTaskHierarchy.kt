package com.xmitya.ideadtf.model

import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.xmitya.ideadtf.DtfFqns
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElementOfType

/**
 * Walking a task's supertypes.
 *
 * Scheduling code is frequently *not* written in the concrete task: project bases make `execute()` a
 * final template and dispatch to `doExecute(...)`, error paths live in `onFailure(...)`, and an
 * interface `default` method can schedule on behalf of every implementor. Anything that asks "what
 * does this task schedule" therefore has to read the whole closure, not just the class itself.
 *
 * Shared by [com.xmitya.ideadtf.search.ScheduleCallSearcher] and the flow builder so that the two
 * agree on what "this task's code" means.
 */
object DtfTaskHierarchy {

    private const val SCHEDULE_PREFIX = "schedule"

    /** The class plus everything it inherits from, minus the framework interface and Object. */
    fun supertypeClosure(taskClass: PsiClass): List<PsiClass> {
        val seen = LinkedHashSet<PsiClass>()
        fun walk(psiClass: PsiClass) {
            if (psiClass.qualifiedName == DtfFqns.TASK) return
            if (psiClass.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) return
            if (!seen.add(psiClass)) return
            psiClass.supers.forEach(::walk)
        }
        walk(taskClass)
        return seen.toList()
    }

    /**
     * The methods a task exposes for *other* code to launch it with.
     *
     * Some project bases give every task its own `schedule(message)`, whose body is
     * `distributedTaskService.schedule(getDef(), ...)`. That reads exactly like a self-reschedule and
     * is the opposite of one: it is how the task is started from outside. Both directions of the flow
     * walk ask this same question - one to find the callers, the other to know not to draw a loop -
     * so they cannot disagree.
     *
     * Public only: a helper nobody outside the class can call is not how anything launches this task,
     * so `schedule(getDef(), ...)` inside one really is a reschedule.
     */
    fun schedulingHelpersOf(taskClass: PsiClass): List<PsiMethod> = supertypeClosure(taskClass)
        .flatMap { it.methods.asIterable() }
        .filter {
            it.name.startsWith(SCHEDULE_PREFIX) &&
                it.hasModifierProperty(PsiModifier.PUBLIC) &&
                !it.hasModifierProperty(PsiModifier.STATIC) &&
                !it.hasModifierProperty(PsiModifier.ABSTRACT)
        }

    /**
     * The class as UAST, preferring the source declaration.
     *
     * A Kotlin light class converts to a stub with no bodies; only the `KtClass` behind it carries
     * the expressions a visitor is after.
     */
    fun asSourceUClass(psiClass: PsiClass): UClass? {
        val source = psiClass.navigationElement?.takeIf { it.isValid } ?: psiClass
        return source.toUElementOfType<UClass>() ?: psiClass.toUElementOfType<UClass>()
    }
}
