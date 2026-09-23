package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfFileColors
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.search.PointerNavigatable
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The contents of a flow tab: a toolbar, the canvas, and the two things the diagram has to be able
 * to say about itself - "still building" and "this is not all of it".
 */
class DtfFlowPanel(private val project: Project, private val scope: DtfFlowScope) :
    SimpleToolWindowPanel(true, true),
    Disposable {

    private val canvas = DtfFlowCanvas()
    private val banner = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }
    private val status = JBLabel(DtfBundle.message("dtf.flow.progress")).apply {
        horizontalAlignment = JBLabel.CENTER
    }
    private val content = JPanel(BorderLayout())
    private var running: Job? = null

    init {
        content.add(banner, BorderLayout.NORTH)
        content.add(status, BorderLayout.CENTER)
        // The colour the IDE gives the file each box points into - green for a test - the same one
        // the gutter's popup and the tool window use, so a test caller reads as one everywhere.
        canvas.fileColorOf = { node ->
            ReadAction.compute<Color?, RuntimeException> { DtfFileColors.of(project, (node.target as? PointerNavigatable)?.virtualFile) }
        }
        canvas.isInTests = { node ->
            ReadAction.compute<Boolean, RuntimeException> {
                (node.target as? PointerNavigatable)?.virtualFile?.let { TestSourcesFilter.isTestSources(it, project) } == true
            }
        }
        canvas.installPopupMenu(scope)
        toolbar = createToolbar()
        setContent(content)
    }

    val preferredFocusComponent: JComponent get() = canvas

    /** Public so a test can assert what the canvas was handed without going through the service. */
    fun flowCanvas(): DtfFlowCanvas = canvas

    fun refresh() {
        status.text = DtfBundle.message("dtf.flow.progress")
        showStatus()
        running?.cancel()
        running = DtfFlowTabService.getInstance(project).build(scope) { show(it) }
    }

    /** The EDT half of a build. Public so that both of its outcomes can be asserted. */
    fun show(graph: DtfFlowGraph) {
        if (graph.isEmpty) {
            status.text = DtfBundle.message("dtf.flow.empty")
            showStatus()
            return
        }
        canvas.setGraph(graph)
        banner.text = DtfBundle.message("dtf.flow.truncated")
        banner.icon = AllIcons.General.Warning
        banner.isVisible = graph.truncated
        content.remove(status)
        if (content.componentCount < 2) content.add(ScrollPaneFactory.createScrollPane(canvas, true), BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun showStatus() {
        content.removeAll()
        content.add(banner, BorderLayout.NORTH)
        content.add(status, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    override fun dispose() {
        running?.cancel()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup(
            SimpleAction("dtf.flow.refresh", AllIcons.Actions.Refresh) { refresh() },
            Separator.getInstance(),
            DtfFlowLayoutActions.group(canvas),
            DtfFlowLayoutActions.showTests(canvas),
            Separator.getInstance(),
            SimpleAction("dtf.flow.zoom.in", AllIcons.General.ZoomIn) { canvas.zoomIn() },
            SimpleAction("dtf.flow.zoom.out", AllIcons.General.ZoomOut) { canvas.zoomOut() },
            SimpleAction("dtf.flow.zoom.actual", AllIcons.General.ActualZoom) { canvas.zoom = 1.0 },
            SimpleAction("dtf.flow.zoom.fit", AllIcons.General.FitContent) { canvas.fitContent(canvas.visibleRect.size) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = canvas
        return toolbar.component
    }

    private class SimpleAction(key: String, icon: javax.swing.Icon, private val run: () -> Unit) :
        DumbAwareAction({ DtfBundle.message(key) }, icon) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private companion object {
        const val TOOLBAR_PLACE = "DtfFlowDiagram"
    }
}
