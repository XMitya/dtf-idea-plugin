package com.xmitya.ideadtf.toolwindow.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.marker.DtfTargetPopup
import com.xmitya.ideadtf.search.DtfPopupTarget
import com.xmitya.ideadtf.search.DtfTaskTargets
import com.xmitya.ideadtf.toolwindow.DtfTaskDataKeys
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry

/**
 * Asking about one row of the DTF Tasks tree and showing the answer where the gutter icon shows its
 * own.
 *
 * The tree lists tasks but could say nothing about any of them; these carry the two questions the
 * gutter already answers - where is this scheduled, where is it configured - to the panel, where
 * the task is in front of you but its source is not.
 *
 * Deliberately not `DumbAware`: every search reads an index, and the platform greying the entry out
 * while it builds them is a better answer than a message saying "try again later". The gutter needs
 * that message only because an icon drawn before indexing began is still on screen and clickable.
 */
abstract class DtfTaskTargetAction : AnAction() {

    /** Only the data key is read in [update]; the searching happens under a progress. */
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        val entry = selectedTask(e)
        // Invisible rather than greyed out: nothing else in this menu applies to a module row
        // either, and a permanently dead entry there reads as a bug.
        e.presentation.isEnabledAndVisible = e.project != null && entry != null && appliesTo(entry)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = selectedTask(e) ?: return
        // Guessed before the progress runs: where a popup belongs is a question about the component
        // hierarchy, which is the EDT's, and a data context read after a modal progress has been up
        // is a context that may no longer describe anything.
        val where = JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext)

        val targets = try {
            // Opens a read action of its own, so nothing in the search needs to wrap one.
            ActionUtil.underModalProgress(project, progressText()) { search(project, entry) }
        } catch (_: ProcessCanceledException) {
            return
        }

        DtfTargetPopup.show(where, targets, titleFor(entry, targets.size)) { emptyMessageFor(entry) }
    }

    /** Whether this question can be asked of [entry] at all, beyond it being a task. */
    protected open fun appliesTo(entry: DtfTaskEntry): Boolean = true

    /** Already resolved rather than a bundle key: a key held in a variable loses its inspection. */
    protected abstract fun progressText(): String

    /** Runs inside a read action, off the EDT. */
    protected abstract fun search(project: Project, entry: DtfTaskEntry): List<DtfPopupTarget>

    protected abstract fun titleFor(entry: DtfTaskEntry, found: Int): String

    protected abstract fun emptyMessageFor(entry: DtfTaskEntry): String

    /**
     * One row only: two selected tasks are two popups, and guessing which was meant is worse than
     * offering neither - the same rule the flow diagram follows for the same reason.
     */
    private fun selectedTask(e: AnActionEvent): DtfTaskEntry? = DtfTaskDataKeys.TASK_ENTRIES.getData(e.dataContext)?.singleOrNull()
}

/** Opens the task's own `task-properties` block, wherever it is configured. */
class GoToTaskConfigurationAction : DtfTaskTargetAction() {

    /** Configuration is keyed by the task's name, so an unreadable `TaskDef` leaves nothing to ask. */
    override fun appliesTo(entry: DtfTaskEntry): Boolean = entry.taskName != null

    override fun progressText(): String = DtfBundle.message("dtf.progress.searching.config")

    override fun search(project: Project, entry: DtfTaskEntry): List<DtfPopupTarget> = DtfTaskTargets.configSites(project, entry.taskName)

    override fun titleFor(entry: DtfTaskEntry, found: Int): String =
        DtfBundle.message("dtf.popup.config.title.named", entry.displayName, found)

    override fun emptyMessageFor(entry: DtfTaskEntry): String = DtfBundle.message("dtf.popup.config.empty", entry.displayName)
}

/** Opens the places the task is handed to `schedule(...)`. */
class GoToScheduleCallsAction : DtfTaskTargetAction() {

    override fun progressText(): String = DtfBundle.message("dtf.progress.searching.calls")

    /** The row holds no live PSI, so the class is looked up again - and may be gone by now. */
    override fun search(project: Project, entry: DtfTaskEntry): List<DtfPopupTarget> {
        val scope = GlobalSearchScope.projectScope(project)
        val taskClass = JavaPsiFacade.getInstance(project).findClass(entry.qualifiedName, scope) ?: return emptyList()
        ProgressManager.checkCanceled()
        return DtfTaskTargets.scheduleSites(project, taskClass)
    }

    override fun titleFor(entry: DtfTaskEntry, found: Int): String = DtfBundle.message("dtf.popup.title.named", entry.displayName, found)

    override fun emptyMessageFor(entry: DtfTaskEntry): String = DtfBundle.message("dtf.popup.calls.empty", entry.displayName)
}
