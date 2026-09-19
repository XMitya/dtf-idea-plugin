package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskDefResolver

/**
 * Turns the task classes into the tree the panel shows.
 *
 * Must run inside the read action that produced them, like the popup presenters: everything
 * PSI-dependent is reduced to strings and smart pointers here so that painting the tree resolves
 * nothing.
 */
class DtfTaskSnapshotBuilder(private val project: Project) {

    fun build(tasks: List<PsiClass>, stamp: Long): DtfTaskSnapshot {
        val byModule = LinkedHashMap<String?, MutableList<DtfTaskEntry>>()
        for (task in tasks) {
            ProgressManager.checkCanceled()
            val anchor = sourceAnchorOf(task)
            byModule.getOrPut(moduleNameOf(anchor)) { mutableListOf() }.add(entryFor(task, anchor))
        }

        val modules = byModule.entries
            // Named modules alphabetically, then the leftovers - a group with no name is a footnote,
            // not something to lead with.
            .sortedWith(compareBy({ it.key == null }, { it.key.orEmpty().lowercase() }))
            .map { (name, tasksOfModule) ->
                DtfTaskModuleGroup(name, tasksOfModule.sortedBy { it.displayName.lowercase() })
            }
        return DtfTaskSnapshot(project.name, modules, stamp)
    }

    private fun entryFor(task: PsiClass, anchor: PsiElement): DtfTaskEntry {
        val cron = DtfCronModel.cronMarkOf(task) as? CronMark.Cron
        return DtfTaskEntry(
            taskName = DtfTaskDefResolver.resolveCached(task).taskName,
            className = task.name.orEmpty(),
            qualifiedName = task.qualifiedName.orEmpty(),
            cronExpression = cron?.expression,
            isCron = cron != null,
            pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchor),
        )
    }

    /**
     * The declaration to open, which for a Kotlin task is the `KtClass` rather than the light class
     * standing in for it - navigating to the latter lands nowhere.
     */
    private fun sourceAnchorOf(task: PsiClass): PsiElement =
        task.navigationElement?.takeIf { it.isValid } ?: task

    private fun moduleNameOf(anchor: PsiElement): String? {
        val virtualFile = anchor.containingFile?.virtualFile ?: return null
        return ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile)?.name
    }
}
