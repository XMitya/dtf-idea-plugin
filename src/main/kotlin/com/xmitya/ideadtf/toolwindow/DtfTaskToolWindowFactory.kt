package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.xmitya.ideadtf.model.DtfTaskModel

/**
 * The DTF Tasks tool window.
 *
 * `isApplicableAsync` is deliberately not overridden. The platform treats it as a gate on
 * *registration*: returning false there means no tool window object exists at all, and nothing can
 * switch it on later. Availability is decided by [shouldBeAvailable] instead, which only sets the
 * initial state of the stripe button and leaves the window registered, so [DtfToolWindowAvailability]
 * can flip it once indexing finishes or a dependency appears.
 */
class DtfTaskToolWindowFactory :
    ToolWindowFactory,
    DumbAware {

    /**
     * Read once, during project open - which is also when indexing is running, hence the dumb guard:
     * `isDtfPresent` goes through `JavaPsiFacade`, which throws while indexes are being built. A
     * `false` here is not the last word; the startup activity re-asks.
     */
    override fun shouldBeAvailable(project: Project): Boolean = !DumbService.isDumb(project) && DtfTaskModel.isDtfPresent(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DtfTaskTreePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.preferredFocusableComponent = panel.preferredFocusComponent
        toolWindow.contentManager.addContent(content)

        // Called on first open only, so the initial scan happens exactly when the user asks for it.
        project.messageBus.connect(toolWindow.disposable)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowShown(shown: ToolWindow) {
                        if (shown.id == ID) panel.refreshIfStale()
                    }
                },
            )
        panel.refreshIfStale()
    }

    companion object {
        /** Also the stripe label: the platform shows a third-party tool window's id verbatim. */
        const val ID = "DTF Tasks"
    }
}
