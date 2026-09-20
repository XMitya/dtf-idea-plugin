package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
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
     * Read once, during project open, on a background thread that holds no read action of its own -
     * the platform calls this straight from the tool window initializer's coroutine - hence the
     * explicit one: `isDtfPresent` goes through `JavaPsiFacade` into the stub index, and touching an
     * index off a read action fails the platform's threading assertion, which costs the whole tool
     * window ("Cannot process toolwindow DTF Tasks") rather than just the answer.
     *
     * Project open is also when indexing runs, hence the dumb guard; the catch covers the remaining
     * race, indexing starting after that guard but before the index is read - a read action does not
     * keep the project smart. A `false` here is not the last word; the startup activity re-asks.
     */
    override fun shouldBeAvailable(project: Project): Boolean = runReadAction {
        if (DumbService.isDumb(project)) {
            false
        } else {
            try {
                DtfTaskModel.isDtfPresent(project)
            } catch (_: IndexNotReadyException) {
                false
            }
        }
    }

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
