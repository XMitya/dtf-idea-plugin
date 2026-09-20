package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.cron.DtfCronConfigSource
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.search.CronSitePresenter
import com.xmitya.ideadtf.search.NavigableCronSite
import java.awt.event.MouseEvent

/**
 * Opens the place a cron task's schedule is configured.
 *
 * The counterpart of [ScheduleSitesNavigationHandler] for tasks that have no call site at all, and
 * built the same way: the work happens on click, under a cancellable modal progress, never during
 * highlighting.
 */
class CronConfigNavigationHandler : GutterIconNavigationHandler<PsiElement> {

    override fun navigate(event: MouseEvent, element: PsiElement) {
        val project = element.project
        // Before anything else: recognising the task resolves the supertype, which reads an index.
        // The provider is not DumbAware, so no icon is *drawn* while indexing - but one drawn just
        // before indexing started is still on screen and still clickable, and this is what keeps
        // that click from throwing IndexNotReadyException.
        if (DumbService.isDumb(project)) {
            showMessage(event, DtfBundle.message("dtf.popup.dumb"))
            return
        }

        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return

        val sites = try {
            // Opens a read action of its own, so nothing here needs to wrap one.
            ActionUtil.underModalProgress(project, DtfBundle.message("dtf.progress.locating")) {
                val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName
                if (taskName == null) {
                    emptyList()
                } else {
                    val scope = GlobalSearchScope.projectScope(project)
                    val found = DtfCronConfigSource.EP.extensionList
                        .flatMap { it.findSites(project, taskName, scope) }
                    CronSitePresenter(project).present(found)
                }
            }
        } catch (_: ProcessCanceledException) {
            return
        }

        when (sites.size) {
            0 -> showMessage(event, annotationMessageFor(taskClass))
            1 -> sites.single().navigate()
            else -> showPopup(event, taskClass, sites)
        }
    }

    private fun showPopup(event: MouseEvent, taskClass: PsiClass, sites: List<NavigableCronSite>) {
        val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName
        val title = if (taskName != null) {
            DtfBundle.message("dtf.popup.cron.title.named", taskName, sites.size)
        } else {
            DtfBundle.message("dtf.popup.cron.title", sites.size)
        }
        // The overload taking presentations as a parallel list, rather than the one taking a
        // function: that one is marked internal API.
        createTargetPopup(title, sites, sites.map { it.presentation }) { it.navigate() }
            .show(RelativePoint(event))
    }

    /**
     * A schedule set by the annotation alone has nowhere to navigate to, so name it instead of
     * leaving the click looking broken.
     */
    private fun annotationMessageFor(taskClass: PsiClass): String {
        val expression = (DtfCronModel.cronMarkOf(taskClass) as? CronMark.Cron)?.expression
        return if (!expression.isNullOrEmpty()) {
            DtfBundle.message("dtf.popup.cron.annotation", expression)
        } else {
            DtfBundle.message("dtf.popup.cron.empty")
        }
    }

    private fun showMessage(event: MouseEvent, message: String) {
        JBPopupFactory.getInstance()
            .createMessage(message)
            .show(RelativePoint(event))
    }
}
