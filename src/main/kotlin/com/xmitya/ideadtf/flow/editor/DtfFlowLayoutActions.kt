package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation

/**
 * Rearranging what is already drawn, and choosing what is.
 *
 * A layered arrangement can only do so much once a flow fans out: some crossings are inherent to the
 * graph, not to the algorithm. So the ways out are giving the reader a different direction to read
 * it in, letting them pull a box out of the tangle by hand, and taking off what they are not looking
 * at - calls from tests, most of the time - which is what these offer.
 */
object DtfFlowLayoutActions {

    fun group(canvas: DtfFlowCanvas): DefaultActionGroup {
        val layout = DefaultActionGroup(DtfBundle.message("dtf.flow.layout"), true)
        layout.templatePresentation.icon = AllIcons.Graph.Layout
        DtfFlowOrientation.entries.forEach { layout.add(OrientationAction(canvas, it)) }
        layout.addSeparator()
        DtfFlowEdgeStyle.entries.forEach { layout.add(EdgeStyleAction(canvas, it)) }
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

    private fun messageKeyOf(style: DtfFlowEdgeStyle): String = when (style) {
        DtfFlowEdgeStyle.ORTHOGONAL -> "dtf.flow.edges.orthogonal"
        DtfFlowEdgeStyle.CURVED -> "dtf.flow.edges.curved"
    }

    private class EdgeStyleAction(private val canvas: DtfFlowCanvas, private val style: DtfFlowEdgeStyle) :
        DumbAwareToggleAction({ DtfBundle.message(messageKeyOf(style)) }) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = canvas.edgeStyle == style

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) canvas.edgeStyle = style
        }
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

    /**
     * Hides the calls made from tests, and brings them back.
     *
     * Pressed while they are shown, as the test runner's own Show Passed is. Greyed out on a diagram
     * that has none, rather than a button that visibly does nothing.
     */
    fun showTests(canvas: DtfFlowCanvas): AnAction = ShowTestsAction(canvas)

    private class ShowTestsAction(private val canvas: DtfFlowCanvas) :
        DumbAwareToggleAction(
            { DtfBundle.message("dtf.flow.tests.show") },
            { DtfBundle.message("dtf.flow.tests.show.description") },
            AllIcons.Nodes.TestSourceFolder,
        ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = canvas.hasTestCalls
        }

        override fun isSelected(e: AnActionEvent): Boolean = canvas.showTests

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            canvas.showTests = state
        }
    }

    /**
     * What can be done with the box right-clicked, then the same layout actions the toolbar has.
     *
     * @param ownScope the diagram the canvas shows; see [DtfFlowNodeActions.group].
     */
    fun popupGroup(canvas: DtfFlowCanvas, ownScope: DtfFlowScope? = null): DefaultActionGroup = DefaultActionGroup().apply {
        addAll(DtfFlowNodeActions.group(canvas, ownScope))
        add(Separator.getInstance())
        add(group(canvas))
    }
}
