package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
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
            collectFrom(referencesTo(anchor, scope), found, depth = 0, scope = scope)
        }
        collectSelfSchedules(taskClass, found)

        return found.values.sortedWith(compareBy({ it.tier.ordinal }, { it.key.first }, { it.key.second }))
    }

    private fun referencesTo(anchor: PsiElement, scope: GlobalSearchScope): List<PsiElement> =
        when (anchor) {
            // Kotlin's synthetic-property access (`task.def`) is only reachable through the
            // method-references search, not the plain reference search.
            is PsiMethod -> MethodReferencesSearch.search(anchor, scope, true).findAll()
            else -> ReferencesSearch.search(anchor, scope).findAll()
        }.map { it.element }

    /**
     * Turns raw references into call sites, following at most [maxDepth] hops of trivial dataflow.
     *
     * Two hops are needed by real code: a reference assigned to a local variable that is then
     * scheduled (frequently via a conditional, which is why one call site can belong to two tasks),
     * and a reference handed to a project-local helper that forwards it to the framework.
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

            val arg0Call = CallArgumentMatcher.arg0CallFor(uRef)
            if (arg0Call != null) {
                val tier = CallArgumentMatcher.classify(arg0Call.method)
                if (tier != null) {
                    addSite(arg0Call.call, tier, found)
                }
                continue
            }

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
                if (node is UReferenceExpression && isSameDeclaration(node.resolve(), declaration)) {
                    node.sourcePsi?.let { reads += it }
                }
                return false
            }
        })
        return reads
    }

    /**
     * Whether [resolved] denotes [declaration].
     *
     * Kotlin resolves a read of a local to a synthetic `UastKotlinPsiVariable` rather than to the
     * `KtProperty` that declares it, and its navigation element does not lead back either. Going
     * through UAST and comparing source PSI is what bridges the two.
     */
    private fun isSameDeclaration(resolved: PsiElement?, declaration: PsiElement): Boolean {
        if (resolved == null) return false
        if (resolved === declaration || resolved.navigationElement === declaration) return true
        return resolved.toUElement()?.sourcePsi === declaration
    }

    /**
     * Self-rescheduling: `schedule(getDef(), ...)`, or `schedule(def, ...)` in Kotlin.
     *
     * Such calls live inside the task class or one of its base classes by definition, so the
     * supertype closure is scanned directly instead of searching the project for `getDef`
     * references. That is both cheaper and more precise: a global search would have to be filtered
     * anyway, because `task.def` on some *other* task must not be attributed here.
     */
    private fun collectSelfSchedules(
        taskClass: PsiClass,
        found: MutableMap<Pair<String, Int>, ScheduleCallSite>,
    ) {
        for (owner in supertypeClosure(taskClass)) {
            val uClass = asSourceUClass(owner) ?: continue
            uClass.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    ProgressManager.checkCanceled()
                    val method = node.resolve() ?: return false
                    val tier = CallArgumentMatcher.classify(method) ?: return false
                    val arg0 = node.getArgumentForParameter(0) ?: return false
                    if (resolvesToGetDef(arg0)) {
                        addSite(node, tier, found)
                    }
                    return false
                }
            })
        }
    }

    private fun resolvesToGetDef(argument: UElement): Boolean {
        val resolved = (argument as? UReferenceExpression)?.resolve()
            ?: ((argument as? UCallExpression)?.resolve())
        return resolved is PsiMethod && resolved.name == DtfFqns.GET_DEF && resolved.parameterList.isEmpty
    }

    /** The class plus everything it inherits from, minus the framework interface itself. */
    private fun supertypeClosure(taskClass: PsiClass): List<PsiClass> {
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

    private fun asSourceUClass(psiClass: PsiClass): UClass? {
        val source = psiClass.navigationElement?.takeIf { it.isValid } ?: psiClass
        return source.toUElementOfType<UClass>() ?: psiClass.toUElementOfType<UClass>()
    }

    private fun addSite(
        call: UCallExpression,
        tier: ScheduleTier,
        found: MutableMap<Pair<String, Int>, ScheduleCallSite>,
    ) {
        val psi = call.sourcePsi ?: return
        val site = ScheduleCallSite(psi, tier)
        found.putIfAbsent(site.key, site)
    }
}
