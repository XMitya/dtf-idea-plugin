package com.xmitya.ideadtf.cron.properties

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import com.xmitya.ideadtf.cron.CronConfigSite
import com.xmitya.ideadtf.cron.CronSummary
import com.xmitya.ideadtf.cron.DtfCronConfigSource

/** Cron declared in a flattened `application.properties`. */
class PropertiesCronConfigSource : DtfCronConfigSource {

    override fun summarise(project: Project, taskName: String, scope: GlobalSearchScope): CronSummary? {
        val values = FileBasedIndex.getInstance().getValues(DtfCronPropertiesIndex.NAME, taskName, scope)
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
            DtfCronPropertiesIndex.NAME,
            taskName,
            null,
            { virtualFile, _ ->
                ProgressManager.checkCanceled()
                val file = psiManager.findFile(virtualFile)
                if (file != null) {
                    // Re-scanned rather than read back from the index: the offset has to match the
                    // file as it is now, not as it was when it was last indexed.
                    for (entry in PropertiesCronScanner.scan(file.viewProvider.contents)) {
                        if (entry.name == taskName) {
                            sites += CronConfigSite(file, entry.offset, entry.expression)
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
