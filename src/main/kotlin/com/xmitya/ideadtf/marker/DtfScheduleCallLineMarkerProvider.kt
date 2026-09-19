package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.model.DtfTaskModel
import javax.swing.Icon

/**
 * Puts a marker on every call that schedules a DTF task and, from there, navigates to the task.
 *
 * The mirror image of [DtfTaskLineMarkerProvider], and registered the same way: for the `UAST`
 * meta-language, so Java and Kotlin come from one provider, and not `DumbAware`, so the platform
 * drops it while indexing.
 */
class DtfScheduleCallLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = DtfBundle.message("dtf.gutter.schedule.name")

    /** Literal, so that renaming the class does not reset the user's gutter-icon setting. */
    override fun getId(): String = "com.xmitya.ideadtf.scheduleGutter"

    override fun getIcon(): Icon = DtfIcons.ScheduleGutter

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!DtfTaskModel.isDtfPresent(element.project)) return null
        DtfScheduleMarkers.scheduleCallAt(element) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            DtfIcons.ScheduleGutter,
            { DtfBundle.message("dtf.gutter.schedule.tooltip") },
            TaskTargetsNavigationHandler(),
            GutterIconRenderer.Alignment.LEFT,
            { DtfBundle.message("dtf.gutter.schedule.name") },
        )
    }
}
