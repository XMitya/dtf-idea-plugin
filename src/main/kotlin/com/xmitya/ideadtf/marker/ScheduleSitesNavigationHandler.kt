package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.search.NavigableScheduleSite
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSitePresenter
import java.awt.event.MouseEvent

/**
 * Runs the search when the gutter icon is clicked and shows the result.
 *
 * The search is deliberately not done during highlighting - it walks the whole project - so it
 * happens here, under a cancellable modal progress.
 */
class ScheduleSitesNavigationHandler : GutterIconNavigationHandler<PsiElement> {

    override fun navigate(event: MouseEvent, element: PsiElement) {
        val project = element.project
        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return

        if (DumbService.isDumb(project)) {
            showMessage(event, DtfBundle.message("dtf.popup.dumb"))
            return
        }

        val sites = try {
            // Opens a read action of its own, so nothing here needs to wrap one.
            ActionUtil.underModalProgress(project, DtfBundle.message("dtf.progress.searching")) {
                val found = ScheduleCallSearcher(project).findScheduleSites(taskClass)
                ScheduleSitePresenter(project).present(found)
            }
        } catch (_: ProcessCanceledException) {
            return
        }

        when (sites.size) {
            0 -> showMessage(event, emptyMessageFor(taskClass))
            1 -> sites.single().navigate()
            else -> showPopup(event, taskClass, sites)
        }
    }

    private fun showPopup(event: MouseEvent, taskClass: PsiClass, sites: List<NavigableScheduleSite>) {
        val taskName = DtfTaskDefResolver.resolve(taskClass).taskName
        val title = if (taskName != null) {
            DtfBundle.message("dtf.popup.title.named", taskName, sites.size)
        } else {
            DtfBundle.message("dtf.popup.title", sites.size)
        }
        createTargetPopup(title, sites, { it.presentation }, { it.navigate() })
            .show(RelativePoint(event))
    }

    /** A cron task has no explicit call site by design; say so instead of "nothing found". */
    private fun emptyMessageFor(taskClass: PsiClass): String =
        if (taskClass.hasAnnotation(DtfFqns.TASK_SCHEDULE_ANNOTATION)) {
            DtfBundle.message("dtf.popup.empty.cron")
        } else {
            DtfBundle.message("dtf.popup.empty")
        }

    private fun showMessage(event: MouseEvent, message: String) {
        JBPopupFactory.getInstance()
            .createMessage(message)
            .show(RelativePoint(event))
    }
}
