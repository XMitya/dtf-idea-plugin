package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getUParentForIdentifier
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
        // Markers must sit on a leaf; bail out before touching UAST, which is not free.
        if (element.firstChild != null) return null

        val uClass = getUParentForIdentifier(element) as? UClass ?: return null
        // Only the class-name identifier, not every identifier inside the class.
        if (uClass.uastAnchor?.sourcePsi !== element) return null

        if (!DtfTaskModel.isDtfPresent(element.project)) return null
        if (!DtfTaskModel.isMarkableTask(uClass.javaPsi)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            DtfIcons.TaskGutter,
            { DtfBundle.message("dtf.gutter.tooltip") },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { DtfBundle.message("dtf.gutter.name") },
        )
    }
}
