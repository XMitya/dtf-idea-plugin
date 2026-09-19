package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.util.PsiModificationTracker
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.search.DtfTaskSearcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the project-wide scan the tool window is built from.
 *
 * A service rather than something the panel owns, so that the work lives on a coroutine scope tied
 * to the project and is cancelled with it. The reference monorepo has 133 modules and several
 * hundred tasks, which is seconds of `ClassInheritorsSearch` - far too much for the EDT, and too
 * much to repeat for nothing.
 */
@Service(Service.Level.PROJECT)
class DtfTaskScanService(private val project: Project, private val scope: CoroutineScope) {

    private var running: Job? = null

    /**
     * Scans, then hands the result to [onResult] on the EDT.
     *
     * A scan already in flight is cancelled: its answer is about to be superseded anyway, and two
     * overlapping project-wide searches are exactly what the progress indicator is there to avoid.
     */
    fun scan(onResult: (DtfTaskSnapshot) -> Unit) {
        running?.cancel()
        running = scope.launch {
            val snapshot = withBackgroundProgress(project, DtfBundle.message("dtf.toolwindow.progress")) {
                smartReadAction(project) {
                    // Read before searching, never after: a change landing mid-scan then leaves the
                    // snapshot looking older than it is, which costs one re-scan rather than
                    // silently showing a stale tree.
                    val stamp = stampOf(project)
                    DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp)
                }
            }
            withContext(Dispatchers.EDT) { onResult(snapshot) }
        }
    }

    companion object {
        fun getInstance(project: Project): DtfTaskScanService = project.service()

        /**
         * How stale a snapshot is allowed to be judged.
         *
         * The VFS and the roots count as well as PSI, because a cron can change without anyone
         * typing: switching a branch rewrites `application.yaml` under the IDE, and adding a module
         * brings in tasks that were never edited.
         */
        fun stampOf(project: Project): Long {
            var stamp = PsiModificationTracker.getInstance(project).modificationCount
            stamp = stamp * 31 + VirtualFileManager.getInstance().structureModificationCount
            stamp = stamp * 31 + ProjectRootModificationTracker.getInstance(project).modificationCount
            return stamp
        }
    }
}
