package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.xmitya.ideadtf.DtfBundle

/**
 * Following one chain through a crowded diagram.
 *
 * In a module of fifty flows the arrows of one are hard to tell from their neighbours' however they
 * are routed, so the reader is given a way to ask for exactly one: right-click a box, and its chain
 * lights up while everything else fades. Esc puts it back.
 */
object DtfFlowHighlightActions {

    /**
     * Highlight Flow and Clear Highlight, for the canvas's right-click menu.
     *
     * Clear Highlight is also bound to Esc on the canvas - the same instance, so the menu shows the
     * key. It is only enabled while there is something to clear, and a disabled action lets Esc go
     * on to whatever else the IDE binds it to.
     */
    fun group(canvas: DtfFlowCanvas): DefaultActionGroup {
        val clear = ClearAction(canvas)
        clear.registerCustomShortcutSet(CommonShortcuts.ESCAPE, canvas)
        return DefaultActionGroup(HighlightAction(canvas), clear)
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
