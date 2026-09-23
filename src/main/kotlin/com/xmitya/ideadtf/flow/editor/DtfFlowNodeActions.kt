package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.DtfFlowTaskNode

/**
 * What can be done with the box that was right-clicked.
 *
 * In a module of fifty flows the arrows of one are hard to tell from their neighbours' however they
 * are routed, so the reader is given two ways to narrow it down: light up the chain a box takes part
 * in while everything else fades - Esc puts it back - or open that task's own diagram in a tab of
 * its own.
 */
object DtfFlowNodeActions {

    /**
     * The box actions, for the canvas's right-click menu.
     *
     * Clear Highlight is also bound to Esc on the canvas - the same instance, so the menu shows the
     * key. It is only enabled while there is something to clear, and a disabled action lets Esc go
     * on to whatever else the IDE binds it to.
     *
     * @param ownScope the diagram the canvas is showing, so that a task is not offered the tab it is
     *   already in.
     */
    fun group(canvas: DtfFlowCanvas, ownScope: DtfFlowScope? = null): DefaultActionGroup {
        val clear = ClearAction(canvas)
        clear.registerCustomShortcutSet(CommonShortcuts.ESCAPE, canvas)
        return DefaultActionGroup(ShowFlowAction(canvas, ownScope), HighlightAction(canvas), clear)
    }

    /**
     * Opens the flow of the task in the box - everything it takes part in, rather than the slice of
     * it this diagram happens to show - the way Show BPMN Flow does from the tool window.
     *
     * Tasks only: a caller box is a call site, and the flow it starts is already the one on screen.
     */
    private class ShowFlowAction(private val canvas: DtfFlowCanvas, private val ownScope: DtfFlowScope?) :
        DumbAwareAction({ DtfBundle.message("action.Dtf.ShowFlow.text") }) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val scope = scopeOfSelection()
            e.presentation.isEnabledAndVisible = e.project != null && scope != null && scope.key != ownScope?.key
            // Mnemonic parsing off: task names are SCREAMING_SNAKE_CASE, as in ShowDtfFlowAction.
            scope?.let { e.presentation.setText(DtfBundle.message("dtf.flow.action.text.named", it.title), false) }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val scope = scopeOfSelection() ?: return
            DtfFlowTabService.getInstance(project).show(scope)
        }

        /** Titled as the tool window titles a task, so that both open the same tab rather than two. */
        private fun scopeOfSelection(): DtfFlowScope.Task? {
            val task = canvas.selectedNodeId?.let { canvas.currentGraph().node(it) } as? DtfFlowTaskNode ?: return null
            return DtfFlowScope.Task(task.qualifiedName, task.displayName)
        }
    }

    /** Offered on a box only: the press that opened the menu has already selected what is under it. */
    private class HighlightAction(private val canvas: DtfFlowCanvas) : DumbAwareAction({ DtfBundle.message("dtf.flow.highlight") }) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = canvas.selectedNodeId != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            canvas.selectedNodeId?.let(canvas::highlightFlowOf)
        }
    }

    private class ClearAction(private val canvas: DtfFlowCanvas) : DumbAwareAction({ DtfBundle.message("dtf.flow.highlight.clear") }) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = canvas.hasHighlight
        }

        override fun actionPerformed(e: AnActionEvent) = canvas.clearHighlight()
    }
}
