package com.xmitya.ideadtf.search

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.config.DtfTaskConfigSource

/**
 * The two questions asked about a task: where it is scheduled, and where it is configured.
 *
 * The gutter icon asks both at once and the tool window's menu asks them one at a time, so the two
 * searches are written here rather than in each caller. Both walk the project and resolve PSI, so
 * both must be called inside a read action, off the EDT.
 */
internal object DtfTaskTargets {

    fun scheduleSites(project: Project, taskClass: PsiClass): List<NavigableScheduleSite> =
        ScheduleSitePresenter(project).present(ScheduleCallSearcher(project).findScheduleSites(taskClass))

    /** Configuration is keyed by the task's name, so a task whose `TaskDef` is unreadable has none. */
    fun configSites(project: Project, taskName: String?): List<NavigableTaskConfigSite> {
        if (taskName == null) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        return TaskConfigSitePresenter(project)
            .present(DtfTaskConfigSource.EP.extensionList.flatMap { it.findSites(project, taskName, scope) })
    }
}
