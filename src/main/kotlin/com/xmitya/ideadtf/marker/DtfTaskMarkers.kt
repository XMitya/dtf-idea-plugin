package com.xmitya.ideadtf.marker

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getUParentForIdentifier

/** Shared between the line marker provider and its navigation handler. */
object DtfTaskMarkers {

    /**
     * The task class whose name identifier [element] is, if it is one.
     *
     * The marker anchors on the class-name leaf, so both the provider and the click handler can
     * derive the class from it and neither has to hold on to PSI between them.
     */
    fun taskClassAt(element: PsiElement): PsiClass? {
        if (element.firstChild != null) return null
        val uClass = getUParentForIdentifier(element) as? UClass ?: return null
        if (uClass.uastAnchor?.sourcePsi !== element) return null
        val psiClass = uClass.javaPsi
        return psiClass.takeIf { DtfTaskModel.isMarkableTask(it) }
    }
}
