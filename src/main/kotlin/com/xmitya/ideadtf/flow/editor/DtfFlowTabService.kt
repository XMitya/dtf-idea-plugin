package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowGraphBuilder
import com.xmitya.ideadtf.flow.DtfFlowScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens flow tabs, and keeps one per subject.
 *
 * A service rather than something the action owns, for the reason
 * [com.xmitya.ideadtf.toolwindow.DtfTaskScanService] is one: the build is a project-wide search that
 * belongs on a scope tied to the project, and asking for the same diagram twice must focus the tab
 * that is already open rather than start a second search.
 */
@Service(Service.Level.PROJECT)
class DtfFlowTabService(private val project: Project, private val coroutineScope: CoroutineScope) {

    private val openTabs = LinkedHashMap<DtfFlowScope, DtfFlowVirtualFile>()
    private val running = LinkedHashMap<DtfFlowScope, Job>()

    init {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file is DtfFlowVirtualFile) openTabs.remove(file.scope)
                }
            },
        )
    }

    /** Opens the diagram for [scope], or brings the one already open to the front. */
    fun show(scope: DtfFlowScope): DtfFlowVirtualFile {
        val file = openTabs.getOrPut(scope) {
            DtfFlowVirtualFile(scope, DtfBundle.message("dtf.flow.tab.title", scope.title)).also {
                // The test editor manager opens the provider named here rather than consulting the
                // extension point; the production one goes through the point and ignores this.
                it.putUserData(FileEditorProvider.KEY, DtfFlowEditorProvider())
            }
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return file
    }

    /**
     * Builds the graph off the EDT and hands it back on it.
     *
     * A build already in flight for the same subject is cancelled: its answer is about to be
     * superseded, and two overlapping project-wide searches are what the progress indicator exists to
     * avoid. No `try/catch` around the coroutine - a cancelled build must stay cancelled.
     */
    fun build(scope: DtfFlowScope, onResult: (DtfFlowGraph) -> Unit): Job {
        running.remove(scope)?.cancel()
        val job = coroutineScope.launch {
            val graph = withBackgroundProgress(project, DtfBundle.message("dtf.flow.progress")) {
                smartReadAction(project) { DtfFlowGraphBuilder(project).build(scope) }
            }
            withContext(Dispatchers.EDT) { onResult(graph) }
        }
        running[scope] = job
        return job
    }

    companion object {
        fun getInstance(project: Project): DtfFlowTabService = project.service()
    }
}
