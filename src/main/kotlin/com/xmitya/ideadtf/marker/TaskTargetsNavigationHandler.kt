package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.search.NavigableTaskTarget
import com.xmitya.ideadtf.search.ScheduledTaskSearcher
import com.xmitya.ideadtf.search.TaskTargetPresenter
import java.awt.event.MouseEvent

/**
 * Resolves the task when the schedule-call gutter icon is clicked and shows the result.
 *
 * The mirror of [ScheduleSitesNavigationHandler]: the work that may walk the project happens here,
 * under a cancellable modal progress, and never during highlighting. Recognising the call is
 * repeated inside the progress rather than carried over from the pass, so no PSI is held between
 * the two.
 */
class TaskTargetsNavigationHandler : GutterIconNavigationHandler<PsiElement> {

    override fun navigate(event: MouseEvent, element: PsiElement) {
        val project = element.project

        if (DumbService.isDumb(project)) {
            showMessage(event, DtfBundle.message("dtf.popup.dumb"))
            return
        }

        val targets = try {
            // Opens a read action of its own, so nothing here needs to wrap one.
            ActionUtil.underModalProgress(project, DtfBundle.message("dtf.progress.resolving")) {
                val call = DtfScheduleMarkers.scheduleCallAt(element)
                if (call == null) {
                    emptyList()
                } else {
                    TaskTargetPresenter(project).present(ScheduledTaskSearcher(project).findTasks(call))
                }
            }
        } catch (_: ProcessCanceledException) {
            return
        }

        when (targets.size) {
            0 -> showMessage(event, DtfBundle.message("dtf.popup.task.empty"))
            1 -> targets.single().navigate(true)
            else -> showPopup(event, targets)
        }
    }

    private fun showPopup(event: MouseEvent, targets: List<NavigableTaskTarget>) {
        val title = DtfBundle.message("dtf.popup.task.title", targets.size)
        // The overload taking presentations as a parallel list, rather than the one taking a
        // function: that one is marked internal API.
        createTargetPopup(title, targets, targets.map { it.presentation }) { it.navigate(true) }
            .show(RelativePoint(event))
    }

    private fun showMessage(event: MouseEvent, message: String) {
        JBPopupFactory.getInstance()
            .createMessage(message)
            .show(RelativePoint(event))
    }
}
