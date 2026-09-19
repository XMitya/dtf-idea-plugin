package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskModel
import javax.swing.Icon

/**
 * Puts a marker on every DTF task class and, from there, navigates to the places that schedule it.
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
        DtfTaskMarkers.taskClassAt(element) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            DtfIcons.TaskGutter,
            ::tooltipFor,
            ScheduleSitesNavigationHandler(),
            GutterIconRenderer.Alignment.LEFT,
            { DtfBundle.message("dtf.gutter.name") },
        )
    }

    /** Resolved on hover rather than during the pass, so the task name costs nothing up front. */
    private fun tooltipFor(element: PsiElement): String {
        val taskClass = DtfTaskMarkers.taskClassAt(element) ?: return DtfBundle.message("dtf.gutter.tooltip")
        val taskName = DtfTaskDefResolver.resolve(taskClass).taskName
            ?: return DtfBundle.message("dtf.gutter.tooltip")
        return DtfBundle.message("dtf.gutter.tooltip.named", taskName)
    }
}
