package com.xmitya.ideadtf.search

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskHierarchy
import com.xmitya.ideadtf.model.DtfTaskModel

/** Whether anything outside a task starts it, as far as the source can tell. */
enum class TaskLaunch {
    /** The framework runs it on a cron, or production code schedules it. */
    LAUNCHED,

    /** Scheduled, but only by test code - production would never run it. */
    ONLY_FROM_TESTS,

    /** Nothing schedules it at all. */
    NEVER,
}

/**
 * Tells a task nothing ever starts from one that something does - the question the BPMN diagram
 * answers by a task box with no arrow coming in.
 *
 * Expects to be called inside a read action, off the EDT: underneath it is the gutter click's own
 * [ScheduleCallSearcher], a project-wide search.
 */
class TaskLaunchSearcher(private val project: Project) {

    private companion object {
        val TASK_LAUNCH: Key<CachedValue<TaskLaunch>> = Key.create("com.xmitya.ideadtf.taskLaunch")
    }

    /**
     * Memoized per class, because highlighting asks again on every pass over the file. Any PSI
     * change drops it - a new caller in some other file has to clear the warning - and so do the
     * VFS, for a `cron` arriving with a branch switch, and the roots, which decide what a test is.
     */
    fun launchOf(taskClass: PsiClass): TaskLaunch = CachedValuesManager.getCachedValue(taskClass, TASK_LAUNCH) {
        CachedValueProvider.Result.create(
            compute(taskClass),
            PsiModificationTracker.MODIFICATION_COUNT,
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            ProjectRootModificationTracker.getInstance(project),
        )
    }

    /**
     * A cron task needs no caller, so it is settled before anything is searched.
     *
     * Test callers count only for a task that is itself test code: a fixture task is meant to be
     * scheduled from tests and from nowhere else.
     */
    private fun compute(taskClass: PsiClass): TaskLaunch {
        if (DtfCronModel.cronMarkOf(taskClass) is CronMark.Cron) return TaskLaunch.LAUNCHED

        val ownCode = ownCodeOf(taskClass)
        val outside = ScheduleCallSearcher(project).findScheduleSites(taskClass).filter { site ->
            ProgressManager.checkCanceled()
            val owner = ScheduleSiteLabel.ownerOf(site.element)?.qualifiedName
            owner == null || owner !in ownCode
        }
        if (outside.isEmpty()) return TaskLaunch.NEVER
        // The declaration rather than the class: a Kotlin light class sits in a file of its own.
        val taskFile = (taskClass.navigationElement ?: taskClass).containingFile?.virtualFile
        if (isTestCode(taskFile)) return TaskLaunch.LAUNCHED
        return if (outside.all { isTestCode(it.element.containingFile?.virtualFile) }) TaskLaunch.ONLY_FROM_TESTS else TaskLaunch.LAUNCHED
    }

    /**
     * The classes whose code only ever runs as this very task: the task itself and the abstract
     * bases above it.
     *
     * A schedule written there is the task rescheduling itself, or the body of the `schedule(message)`
     * helper that other code launches it through - the diagram draws both as a loop onto the task.
     * Either way it needs the task to be running already, so it is no evidence that anything starts
     * it; the helper's callers are sites of their own. A concrete task above this one is not included:
     * its code runs as that task too, and scheduling this one from there is a genuine launch.
     */
    private fun ownCodeOf(taskClass: PsiClass): Set<String> = DtfTaskHierarchy.supertypeClosure(taskClass)
        .filter { it == taskClass || !DtfTaskModel.isMarkableTask(it) }
        .mapNotNullTo(HashSet()) { it.qualifiedName }

    private fun isTestCode(file: VirtualFile?): Boolean = file != null && TestSourcesFilter.isTestSources(file, project)
}
