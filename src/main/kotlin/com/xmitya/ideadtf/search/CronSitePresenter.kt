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
import com.xmitya.ideadtf.cron.CronConfigSite

/**
 * Renders configuration sites for the popup.
 *
 * Must run inside the read action that produced them: everything PSI-dependent is turned into plain
 * strings here so that nothing is resolved later on the UI thread.
 */
class CronSitePresenter(private val project: Project) {

    /** Live schedules first: a profile that blanks the cron out is rarely what is being looked for. */
    fun present(sites: List<CronConfigSite>): List<NavigableCronSite> =
        sites.sortedWith(compareBy({ it.expression.isEmpty() }, { it.file.virtualFile?.path.orEmpty() }, { it.offset }))
            .map { site ->
                NavigableCronSite(
                    pointer = SmartPointerManager.getInstance(project)
                        .createSmartPsiFileRangePointer(site.file, TextRange(site.offset, site.offset)),
                    presentation = presentationFor(site),
                    active = site.expression.isNotEmpty(),
                )
            }

    /**
     * The row leads with the cron itself: that is the question being asked, and it is what differs
     * between a production file and a test profile that switches the task off.
     */
    private fun presentationFor(site: CronConfigSite): TargetPresentation {
        val presentable = site.expression.ifEmpty { DtfBundle.message("dtf.cron.disabled") }
        val virtualFile = site.file.virtualFile

        var builder = TargetPresentation.builder(presentable)
        if (virtualFile != null) {
            builder = builder.icon(virtualFile.fileType.icon)
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
    private fun locationOf(site: CronConfigSite): String? {
        val document = PsiDocumentManager.getInstance(project).getDocument(site.file) ?: return site.file.name
        if (site.offset !in 0..document.textLength) return site.file.name
        return "${site.file.name}:${document.getLineNumber(site.offset) + 1}"
    }
}
