package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskModel

/**
 * Every DTF task in the project, for the tool window.
 *
 * The other two searchers answer questions about one task or one call; this one enumerates, which is
 * why it is the only place that fans out from [DtfFqns.TASK] itself.
 *
 * Expects to be called inside a read action, off the EDT: a real monorepo has hundreds of tasks
 * spread over dozens of modules, so this is seconds of work, not milliseconds.
 */
class DtfTaskSearcher(private val project: Project) {

    fun findAllTasks(): List<PsiClass> {
        // allScope to *find* the interface - it arrives as a library class - but projectScope to
        // search below it, so the framework's own tasks stay out of a listing of "my tasks".
        val taskInterface = JavaPsiFacade.getInstance(project)
            .findClass(DtfFqns.TASK, GlobalSearchScope.allScope(project))
            ?: return emptyList()

        val found = LinkedHashMap<String, PsiClass>()
        // findAll() rather than iterating the Query: `for (x in query)` goes through
        // Query.iterator(), which is deprecated and scheduled for removal.
        val inheritors = ClassInheritorsSearch.search(taskInterface, GlobalSearchScope.projectScope(project), true)
        for (inheritor in inheritors.findAll()) {
            ProgressManager.checkCanceled()
            if (!DtfTaskModel.isMarkableTask(inheritor)) continue
            // Keyed on the qualified name for the same reason as ScheduledTaskSearcher: a class is
            // reachable through several supertype paths and would otherwise be listed twice.
            val qualifiedName = inheritor.qualifiedName ?: continue
            found.putIfAbsent(qualifiedName, inheritor)
        }
        return found.values.toList()
    }
}
