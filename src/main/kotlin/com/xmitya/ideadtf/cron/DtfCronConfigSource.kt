package com.xmitya.ideadtf.cron

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * A configuration format cron schedules can be declared in.
 *
 * An extension point rather than direct calls so that the YAML implementation can live behind an
 * optional dependency: with the YAML plugin disabled its descriptor is not loaded, this list is
 * simply empty, and nothing in the core ever names a class it could not load.
 */
interface DtfCronConfigSource {

    /**
     * Whether [taskName] is configured with a cron anywhere in [scope].
     *
     * Runs in the highlighting pass, so it must be an index lookup - no PSI, no parsing.
     */
    fun summarise(project: Project, taskName: String, scope: GlobalSearchScope): CronSummary?

    /**
     * Every place [taskName] is configured, for the popup.
     *
     * Runs on click, inside a read action under a cancellable progress, so PSI is fair game here.
     */
    fun findSites(project: Project, taskName: String, scope: GlobalSearchScope): List<CronConfigSite>

    companion object {
        val EP: ExtensionPointName<DtfCronConfigSource> =
            ExtensionPointName.create("com.xmitya.ideadtf.cronConfigSource")
    }
}

/**
 * What the index knows about a task without opening a file.
 *
 * @param anyNonBlank whether at least one declaration actually schedules the task. A file that only
 *   blanks the cron out - the usual way a test profile disables one - does not.
 * @param sampleExpression one non-blank expression, for the tooltip. Which one is unspecified when
 *   several files disagree; the popup shows them all.
 */
data class CronSummary(
    val anyNonBlank: Boolean,
    val sampleExpression: String?,
)
