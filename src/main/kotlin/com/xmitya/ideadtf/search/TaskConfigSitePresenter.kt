package com.xmitya.ideadtf.search

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfFileColors
import com.xmitya.ideadtf.config.TaskConfigSite

/**
 * Renders task configuration sites for the popup.
 *
 * Must run inside the read action that produced them: everything PSI-dependent is turned into plain
 * strings here so that nothing is resolved later on the UI thread.
 */
class TaskConfigSitePresenter(private val project: Project) {

    /**
     * Live schedules first: a profile that blanks the cron out is rarely what is being looked for,
     * and an entry that configures a task without scheduling it is a different question again.
     *
     * For an ordinary task every row is settings-only, so the order there comes down to the file.
     */
    fun present(sites: List<TaskConfigSite>): List<NavigableTaskConfigSite> =
        sites.sortedWith(compareBy({ rankOf(it.cron) }, { it.file.virtualFile?.path.orEmpty() }, { it.offset }))
            .map { site ->
                NavigableTaskConfigSite(
                    pointer = SmartPointerManager.getInstance(project)
                        .createSmartPsiFileRangePointer(site.file, TextRange(site.offset, site.offset)),
                    presentation = presentationFor(site),
                    active = !site.cron.isNullOrEmpty(),
                )
            }

    private fun rankOf(cron: String?): Int = when {
        cron == null -> 2
        cron.isEmpty() -> 1
        else -> 0
    }

    /**
     * The row leads with the cron itself: that is the question being asked, and it is what differs
     * between a production file and a test profile that switches the task off. An entry that sets
     * no cron says so instead - it configures the task's timeout or its retries, and the file and
     * line beside it are what tells two of them apart.
     */
    private fun presentationFor(site: TaskConfigSite): TargetPresentation {
        val presentable = when {
            site.cron == null -> DtfBundle.message("dtf.config.settings")
            site.cron.isEmpty() -> DtfBundle.message("dtf.cron.disabled")
            else -> site.cron
        }
        val virtualFile = site.file.virtualFile

        var builder = TargetPresentation.builder(presentable)
        if (virtualFile != null) {
            builder = builder.icon(virtualFile.fileType.icon)
                .backgroundColor(DtfFileColors.of(project, virtualFile))
            containerOf(virtualFile)?.let { builder = builder.containerText(it) }
        }
        locationOf(site)?.let { builder = builder.locationText(it) }
        return builder.presentation()
    }

    /** Module and source root, which is what tells three same-named files apart. */
    private fun containerOf(virtualFile: VirtualFile): String? {
        val index = ProjectFileIndex.getInstance(project)
        val module = index.getModuleForFile(virtualFile)?.name
        val relative = index.getContentRootForFile(virtualFile)
            ?.let { VfsUtilCore.getRelativePath(virtualFile.parent ?: virtualFile, it) }
        val parts = listOfNotNull(module, relative?.takeIf { it.isNotEmpty() })
        return parts.joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    /** `application-local.yaml:37`, the thing you actually scan the list for. */
    private fun locationOf(site: TaskConfigSite): String? {
        val document = PsiDocumentManager.getInstance(project).getDocument(site.file) ?: return site.file.name
        if (site.offset !in 0..document.textLength) return site.file.name
        return "${site.file.name}:${document.getLineNumber(site.offset) + 1}"
    }
}
