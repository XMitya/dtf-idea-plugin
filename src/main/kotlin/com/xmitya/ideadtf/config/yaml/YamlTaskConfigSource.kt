package com.xmitya.ideadtf.config.yaml

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.xmitya.ideadtf.config.CronSummary
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.config.TaskConfigSite
import com.xmitya.ideadtf.config.TaskConfigValue
import org.jetbrains.yaml.psi.YAMLFile

/** Task settings declared in `application*.yaml`, the way nearly every Spring service configures them. */
class YamlTaskConfigSource : DtfTaskConfigSource {

    override fun summarise(project: Project, taskName: String, scope: GlobalSearchScope): CronSummary? =
        TaskConfigValue.summaryOf(FileBasedIndex.getInstance().getValues(DtfTaskConfigYamlIndex.NAME, taskName, scope))

    override fun findSites(project: Project, taskName: String, scope: GlobalSearchScope): List<TaskConfigSite> {
        val sites = mutableListOf<TaskConfigSite>()
        val psiManager = PsiManager.getInstance(project)

        FileBasedIndex.getInstance().processValues(
            DtfTaskConfigYamlIndex.NAME,
            taskName,
            null,
            { virtualFile, _ ->
                ProgressManager.checkCanceled()
                val file = psiManager.findFile(virtualFile) as? YAMLFile
                if (file != null) {
                    for (document in file.documents) {
                        ProgressManager.checkCanceled()
                        YamlTaskPropertiesPath.forEachTaskEntry(document) { name, entry, cron ->
                            // The index answered for the file; inside it, only this task's entry.
                            if (name == taskName) {
                                sites += TaskConfigSite(file, entry.textRange.startOffset, cron)
                            }
                        }
                    }
                }
                true
            },
            scope,
        )
        return sites
    }
}
