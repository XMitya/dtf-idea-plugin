package com.xmitya.ideadtf.model

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.xmitya.ideadtf.DtfFqns
import com.xmitya.ideadtf.cron.DtfCronConfigSource

/** Where a task's schedule was declared. */
enum class CronSource {
    /** `@TaskSchedule(cron = "...")` on the class. */
    ANNOTATION,

    /** `task-properties.<NAME>.cron` in a configuration file, which outranks the annotation. */
    CONFIG,
}

/** Whether the framework launches a task on a schedule, and what that schedule is. */
sealed interface CronMark {

    data object None : CronMark

    /**
     * @param expression the cron to show, taken from configuration when there is one, since that is
     *   what the framework would actually use. Null or empty when it could not be read.
     */
    data class Cron(val bySource: Set<CronSource>, val expression: String?) : CronMark
}

/**
 * Whether a task is launched on a schedule.
 *
 * Cheap enough for the highlighting pass: the annotation is a local lookup and the configuration is
 * an index hit, and the whole answer is memoized per class.
 */
object DtfCronModel {

    private val CRON_MARK: Key<CachedValue<CronMark>> = Key.create("com.xmitya.ideadtf.cronMark")

    /**
     * Depends on the VFS as well as on PSI: configuration usually changes by switching a branch
     * rather than by typing, and that moves no PSI modification count.
     */
    fun cronMarkOf(psiClass: PsiClass): CronMark =
        CachedValuesManager.getCachedValue(psiClass, CRON_MARK) {
            CachedValueProvider.Result.create(
                compute(psiClass),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    private fun compute(psiClass: PsiClass): CronMark {
        val annotation = taskScheduleAnnotation(psiClass)
        val annotationCron = annotation?.let {
            AnnotationUtil.getStringAttributeValue(it, DtfFqns.CRON_ATTRIBUTE)
        }

        var configCron: String? = null
        var configured = false
        val taskName = DtfTaskDefResolver.resolveCached(psiClass).taskName
        if (taskName != null) {
            val project = psiClass.project
            val scope = GlobalSearchScope.projectScope(project)
            for (source in DtfCronConfigSource.EP.extensionList) {
                val summary = source.summarise(project, taskName, scope) ?: continue
                // A file that only blanks the cron out disables the schedule rather than declaring
                // one, so it does not make the task a cron task by itself.
                if (!summary.anyNonBlank) continue
                configured = true
                if (configCron == null) configCron = summary.sampleExpression
            }
        }

        val sources = buildSet {
            if (annotation != null) add(CronSource.ANNOTATION)
            if (configured) add(CronSource.CONFIG)
        }
        if (sources.isEmpty()) return CronMark.None
        return CronMark.Cron(sources, configCron ?: annotationCron)
    }

    /**
     * The `@TaskSchedule` governing this class, directly or one meta-annotation deep.
     *
     * One level matches what the framework sees through `AnnotatedElementUtils.getMergedAnnotation`
     * for the shapes that occur. Superclasses are deliberately not walked: the annotation is not
     * `@Inherited`, so a base carrying it does not schedule its subclasses.
     */
    private fun taskScheduleAnnotation(psiClass: PsiClass): PsiAnnotation? {
        psiClass.getAnnotation(DtfFqns.TASK_SCHEDULE_ANNOTATION)?.let { return it }
        for (annotation in psiClass.annotations) {
            val meta = annotation.resolveAnnotationType() ?: continue
            meta.getAnnotation(DtfFqns.TASK_SCHEDULE_ANNOTATION)?.let { return it }
        }
        return null
    }
}
