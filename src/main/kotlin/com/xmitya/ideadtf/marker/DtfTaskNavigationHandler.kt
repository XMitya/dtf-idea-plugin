package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.search.DtfPopupTarget
import com.xmitya.ideadtf.search.NavigableScheduleSite
import com.xmitya.ideadtf.search.NavigableTaskConfigSite
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSitePresenter
import com.xmitya.ideadtf.search.TaskConfigSitePresenter
import java.awt.event.MouseEvent

/**
 * Opens how a task gets launched: the places it is scheduled, and the places it is configured.
 *
 * One handler for both icons rather than one each. A task is launched by a `schedule(...)` call, by
 * the framework on a cron, or by both, and it can carry a `task-properties` block whichever of
 * those is true - an ordinary task is configured there for its timeout and its retries just as a
 * cron task is for its schedule. Asking the two questions separately meant each icon could answer
 * only one of them, and a cron task that was also scheduled explicitly lost the list of its call
 * sites to the clock.
 *
 * Both searches are deliberately not done during highlighting - one walks the whole project - so
 * they happen here, under a single cancellable modal progress.
 */
class DtfTaskNavigationHandler : GutterIconNavigationHandler<PsiElement> {

    override fun navigate(event: MouseEvent, element: PsiElement) {
        val project = element.project
        // Before anything else: recognising the task resolves the supertype, which reads an index.
        // The provider is not DumbAware, so no icon is *drawn* while indexing - but one drawn just
        // before indexing started is still on screen and still clickable, and this is what keeps
        // that click from throwing IndexNotReadyException.
        if (DumbService.isDumb(project)) {
            DtfTargetPopup.message(event, DtfBundle.message("dtf.popup.dumb"))
            return
        }

        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return

        val found = try {
            // Opens a read action of its own, so nothing here needs to wrap one.
            ActionUtil.underModalProgress(project, DtfBundle.message("dtf.progress.searching")) {
                search(project, taskClass)
            }
        } catch (_: ProcessCanceledException) {
            return
        }

        DtfTargetPopup.show(event, found.rows, titleFor(found)) { emptyMessageFor(found) }
    }

    /**
     * Everything the popup needs, resolved inside the progress.
     *
     * The name and the cron mark are carried out rather than asked for again when the popup is
     * built: `cronMarkOf` reads the index, and the popup is built on the EDT outside any read
     * action.
     */
    private fun search(project: Project, taskClass: PsiClass): Found {
        val calls = ScheduleSitePresenter(project).present(ScheduleCallSearcher(project).findScheduleSites(taskClass))
        ProgressManager.checkCanceled()

        val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName
        val scope = GlobalSearchScope.projectScope(project)
        val configs = taskName?.let { name ->
            TaskConfigSitePresenter(project)
                .present(DtfTaskConfigSource.EP.extensionList.flatMap { it.findSites(project, name, scope) })
        }.orEmpty()

        return Found(calls, configs, taskName ?: taskClass.name.orEmpty(), DtfCronModel.cronMarkOf(taskClass))
    }

    private fun titleFor(found: Found): String {
        val key = when {
            found.configs.isEmpty() -> "dtf.popup.title.named"
            found.calls.isEmpty() -> "dtf.popup.config.title.named"
            else -> "dtf.popup.mixed.title.named"
        }
        return DtfBundle.message(key, found.taskName, found.rows.size)
    }

    /**
     * Nothing found is three different things, and saying which saves the next search.
     *
     * A cron mark here can only have come from the annotation: configuration counts as a schedule
     * only when the index holds a non-blank cron for the task, and that implies a configuration
     * site, which would have made the list non-empty.
     */
    private fun emptyMessageFor(found: Found): String {
        val expression = (found.cronMark as? CronMark.Cron)?.expression
        return when {
            found.cronMark !is CronMark.Cron -> DtfBundle.message("dtf.popup.empty")
            !expression.isNullOrEmpty() -> DtfBundle.message("dtf.popup.cron.annotation", expression)
            else -> DtfBundle.message("dtf.popup.cron.empty")
        }
    }

    /** Call sites first: they are what the question usually is, and the popup preselects row one. */
    private class Found(
        val calls: List<NavigableScheduleSite>,
        val configs: List<NavigableTaskConfigSite>,
        val taskName: String,
        val cronMark: CronMark,
    ) {
        val rows: List<DtfPopupTarget> get() = calls + configs
    }
}
