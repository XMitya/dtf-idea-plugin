package com.xmitya.ideadtf.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.xmitya.ideadtf.DtfFqns

/**
 * Read-only questions about DTF tasks. No searching happens here, so everything is cheap enough to
 * call from the highlighting pass.
 */
object DtfTaskModel {

    private val DTF_PRESENT: Key<CachedValue<Boolean>> = Key.create("com.xmitya.ideadtf.dtfPresent")

    /**
     * Whether the DTF API is on the project's classpath at all. Lets the line marker provider cost
     * nothing in projects that do not use the framework.
     *
     * Depends on [PsiModificationTracker.MODIFICATION_COUNT] as well as the roots, so that adding a
     * source-level `Task` interface also invalidates the answer.
     */
    fun isDtfPresent(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(
        project,
        DTF_PRESENT,
        {
            val scope = GlobalSearchScope.allScope(project)
            val found = JavaPsiFacade.getInstance(project).findClass(DtfFqns.TASK, scope) != null
            CachedValueProvider.Result.create(
                found,
                ProjectRootModificationTracker.getInstance(project),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        },
        false,
    )

    /**
     * Whether [psiClass] is a class the plugin should mark as a task: a concrete class somewhere
     * below [DtfFqns.TASK].
     *
     * The inheritance is frequently indirect - real code reaches `Task` through project-local base
     * classes and interfaces, up to three levels deep - so the whole supertype closure is walked.
     */
    fun isMarkableTask(psiClass: PsiClass): Boolean {
        if (psiClass.isInterface || psiClass.isEnum || psiClass.isAnnotationType) return false
        if (psiClass.qualifiedName == null) return false // anonymous or local
        if (psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)) return false
        return isDtfTask(psiClass)
    }

    /** Supertype-closure test, memoized per class. Kotlin light classes make this non-trivial. */
    fun isDtfTask(psiClass: PsiClass): Boolean = CachedValuesManager.getProjectPsiDependentCache(psiClass) {
        InheritanceUtil.isInheritor(it, DtfFqns.TASK)
    }
}
