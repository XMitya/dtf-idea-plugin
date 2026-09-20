package com.xmitya.ideadtf.config.properties

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.xmitya.ideadtf.config.CronSummary
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.config.TaskConfigSite
import com.xmitya.ideadtf.config.TaskConfigValue

/** Task settings declared in a flattened `application.properties`. */
class PropertiesTaskConfigSource : DtfTaskConfigSource {

    override fun summarise(project: Project, taskName: String, scope: GlobalSearchScope): CronSummary? =
        TaskConfigValue.summaryOf(FileBasedIndex.getInstance().getValues(DtfTaskConfigPropertiesIndex.NAME, taskName, scope))

    override fun findSites(project: Project, taskName: String, scope: GlobalSearchScope): List<TaskConfigSite> {
        val sites = mutableListOf<TaskConfigSite>()
        val psiManager = PsiManager.getInstance(project)

        FileBasedIndex.getInstance().processValues(
            DtfTaskConfigPropertiesIndex.NAME,
            taskName,
            null,
            { virtualFile, _ ->
                ProgressManager.checkCanceled()
                psiManager.findFile(virtualFile)?.let { file -> siteIn(file, taskName)?.let { sites += it } }
                true
            },
            scope,
        )
        return sites
    }

    /**
     * One row per file, not per line.
     *
     * YAML gives a task a block to point at; here it has a line per setting, and eight rows for one
     * task in one file would be a list nobody reads. The cron line is the anchor when there is one -
     * a live one ahead of a blank one, the same order the index merges in - because that is the line
     * the row is labelled with; otherwise the first setting written for the task.
     *
     * Re-scanned rather than read back from the index: the offset has to match the file as it is
     * now, not as it was when it was last indexed.
     */
    private fun siteIn(file: PsiFile, taskName: String): TaskConfigSite? {
        val entries = PropertiesTaskConfigScanner.scan(file.viewProvider.contents).filter { it.name == taskName }
        if (entries.isEmpty()) return null
        val cron = entries.firstOrNull { it.isCron && it.value.isNotEmpty() } ?: entries.firstOrNull { it.isCron }
        return TaskConfigSite(file, (cron ?: entries.first()).offset, cron?.value)
    }
}
