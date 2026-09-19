package com.xmitya.ideadtf.cron.yaml

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.xmitya.ideadtf.cron.CronConfigSite
import com.xmitya.ideadtf.cron.CronSummary
import com.xmitya.ideadtf.cron.DtfCronConfigSource
import org.jetbrains.yaml.psi.YAMLFile

/** Cron declared in `application*.yaml`, the way nearly every Spring service configures it. */
class YamlCronConfigSource : DtfCronConfigSource {

    override fun summarise(project: Project, taskName: String, scope: GlobalSearchScope): CronSummary? {
        val values = FileBasedIndex.getInstance().getValues(DtfCronYamlIndex.NAME, taskName, scope)
        if (values.isEmpty()) return null
        return CronSummary(
            anyNonBlank = values.any { it.isNotEmpty() },
            sampleExpression = values.firstOrNull { it.isNotEmpty() },
        )
    }

    override fun findSites(project: Project, taskName: String, scope: GlobalSearchScope): List<CronConfigSite> {
        val sites = mutableListOf<CronConfigSite>()
        val psiManager = PsiManager.getInstance(project)

        FileBasedIndex.getInstance().processValues(
            DtfCronYamlIndex.NAME,
            taskName,
            null,
            { virtualFile, _ ->
                ProgressManager.checkCanceled()
                val file = psiManager.findFile(virtualFile) as? YAMLFile
                if (file != null) {
                    for (document in file.documents) {
                        YamlCronPath.forEachTaskCron(document) { name, entry, expression ->
                            // The index answered for the file; inside it, only this task's entry.
                            if (name == taskName) {
                                sites += CronConfigSite(file, entry.textRange.startOffset, expression)
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
