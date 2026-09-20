package com.xmitya.ideadtf.search

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPsiElementPointer

/**
 * A schedule call site, prepared for display.
 *
 * Holds a smart pointer and an already-rendered presentation rather than live PSI, so that showing
 * the popup and navigating from it never touch PSI on the UI thread.
 */
class NavigableScheduleSite(private val pointer: SmartPsiElementPointer<*>, val presentation: TargetPresentation, val tier: ScheduleTier) {
    fun navigate() {
        val file = pointer.virtualFile ?: return
        val offset = pointer.range?.startOffset ?: return
        PsiNavigationSupport.getInstance()
            .createNavigatable(pointer.project, file, offset)
            .navigate(true)
    }
}
