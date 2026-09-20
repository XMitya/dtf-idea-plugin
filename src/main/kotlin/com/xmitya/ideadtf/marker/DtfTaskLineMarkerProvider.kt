package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.model.CronMark
import com.xmitya.ideadtf.model.DtfCronModel
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskModel
import javax.swing.Icon

/**
 * Puts a marker on every DTF task class and navigates from it to how the task gets launched.
 *
 * Which is two different questions. An ordinary task is launched by a `schedule(...)` call, so the
 * icon lists those; a cron task is launched by the framework, so it gets a clock instead and the
 * icon opens the configuration. One provider rather than two, because a task is exactly one of the
 * two and the marker has to land on the same anchor either way - and because two providers would let
 * a cron task lose its icon entirely if the user switched one of them off.
 *
 * Registered for the `UAST` meta-language, which covers Java and Kotlin from a single provider.
 *
 * Deliberately not `DumbAware`: the platform then drops the provider while indexing, which keeps
 * `IndexNotReadyException` out of the highlighting pass for free.
 */
class DtfTaskLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = DtfBundle.message("dtf.gutter.name")

    /** Literal, so that renaming the class does not reset the user's gutter-icon setting. */
    override fun getId(): String = "com.xmitya.ideadtf.taskGutter"

    override fun getIcon(): Icon = DtfIcons.TaskGutter

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!DtfTaskModel.isDtfPresent(element.project)) return null
        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return null
        val cron = DtfCronModel.cronMarkOf(taskClass) is CronMark.Cron

        return LineMarkerInfo(
            element,
            element.textRange,
            if (cron) DtfIcons.CronGutter else DtfIcons.TaskGutter,
            ::tooltipFor,
            if (cron) CronConfigNavigationHandler() else ScheduleSitesNavigationHandler(),
            GutterIconRenderer.Alignment.LEFT,
            { DtfBundle.message(if (cron) "dtf.gutter.cron.name" else "dtf.gutter.name") },
        )
    }

    /** Resolved on hover rather than during the pass, so the task name costs nothing up front. */
    private fun tooltipFor(element: PsiElement): String {
        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return DtfBundle.message("dtf.gutter.tooltip")
        val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName

        return when (val cron = DtfCronModel.cronMarkOf(taskClass)) {
            is CronMark.Cron -> when {
                taskName != null && !cron.expression.isNullOrEmpty() ->
                    DtfBundle.message("dtf.gutter.cron.tooltip.expression", taskName, cron.expression)

                taskName != null -> DtfBundle.message("dtf.gutter.cron.tooltip.named", taskName)

                else -> DtfBundle.message("dtf.gutter.cron.tooltip")
            }

            CronMark.None ->
                if (taskName != null) {
                    DtfBundle.message("dtf.gutter.tooltip.named", taskName)
                } else {
                    DtfBundle.message("dtf.gutter.tooltip")
                }
        }
    }
}
