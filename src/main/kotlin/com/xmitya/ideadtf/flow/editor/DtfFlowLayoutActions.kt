package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation

/**
 * Rearranging what is already drawn.
 *
 * A layered arrangement can only do so much once a flow fans out: some crossings are inherent to the
 * graph, not to the algorithm. So the two ways out are giving the reader a different direction to
 * read it in, and letting them pull a box out of the tangle by hand - which is what these offer.
 */
object DtfFlowLayoutActions {

    fun group(canvas: DtfFlowCanvas): DefaultActionGroup {
        val layout = DefaultActionGroup(DtfBundle.message("dtf.flow.layout"), true)
        layout.templatePresentation.icon = AllIcons.Graph.Layout
        DtfFlowOrientation.entries.forEach { layout.add(OrientationAction(canvas, it)) }
        layout.addSeparator()
        layout.add(ResetAction(canvas))
        return layout
    }

    private fun messageKeyOf(orientation: DtfFlowOrientation): String = when (orientation) {
        DtfFlowOrientation.LEFT_TO_RIGHT -> "dtf.flow.layout.leftToRight"
        DtfFlowOrientation.RIGHT_TO_LEFT -> "dtf.flow.layout.rightToLeft"
        DtfFlowOrientation.TOP_TO_BOTTOM -> "dtf.flow.layout.topToBottom"
        DtfFlowOrientation.BOTTOM_TO_TOP -> "dtf.flow.layout.bottomToTop"
    }

    private class OrientationAction(private val canvas: DtfFlowCanvas, private val orientation: DtfFlowOrientation) :
        DumbAwareToggleAction({ DtfBundle.message(messageKeyOf(orientation)) }) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = canvas.orientation == orientation

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) canvas.orientation = orientation
        }
    }

    /** Only worth offering once something has actually been moved. */
    private class ResetAction(private val canvas: DtfFlowCanvas) :
        DumbAwareAction({ DtfBundle.message("dtf.flow.layout.reset") }, AllIcons.Actions.Rollback) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = canvas.hasMovedBoxes
        }

        override fun actionPerformed(e: AnActionEvent) = canvas.resetPositions()
    }

    /** The same actions the toolbar has, for a right-click on the diagram itself. */
    fun popupGroup(canvas: DtfFlowCanvas): DefaultActionGroup = DefaultActionGroup().apply {
        add(group(canvas))
        add(Separator.getInstance())
    }
}
