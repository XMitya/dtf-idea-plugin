package com.xmitya.ideadtf.flow.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.editor.DtfFlowTabService

/**
 * Opens the BPMN flow diagram for whatever the context names.
 *
 * Deliberately not `DumbAware`: working out what was selected reads the stub index, and the search
 * behind the diagram needs the indexes too, so the platform disabling this during indexing is the
 * right answer rather than a message saying "try again later".
 */
class ShowDtfFlowAction : AnAction() {

    /** Resolving the selection touches PSI, which must not happen on the EDT. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val scope = e.project?.let { DtfFlowScopeResolver.from(e.dataContext, it) }
        // Invisible rather than greyed out: this sits in the Project view popup, where a permanently
        // disabled entry on every file is noise.
        e.presentation.isEnabledAndVisible = scope != null
        if (scope != null) {
            // Mnemonic parsing off: task names are SCREAMING_SNAKE_CASE, and the default would read
            // every underscore as a mnemonic marker and show HELLO_TASK as HELLOTASK.
            e.presentation.setText(DtfBundle.message("dtf.flow.action.text.named", scope.title), false)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val scope = resolveUnderProgress(e, project) ?: return
        DtfFlowTabService.getInstance(project).show(scope)
    }

    /**
     * `update` already resolved this, but its answer cannot be carried here safely, and re-resolving
     * on the EDT would break the threading rule - so it happens under a modal progress, the same way
     * the gutter handlers run their searches.
     */
    private fun resolveUnderProgress(e: AnActionEvent, project: Project): DtfFlowScope? = try {
        ActionUtil.underModalProgress(project, DtfBundle.message("dtf.flow.progress.resolving")) {
            DtfFlowScopeResolver.from(e.dataContext, project)
        }
    } catch (_: ProcessCanceledException) {
        null
    }
}
