package com.xmitya.ideadtf.search

import com.intellij.psi.PsiElement
import org.jetbrains.uast.toUElement

/**
 * Comparing what a reference resolved to against the declaration it is supposed to be.
 *
 * Kotlin resolves a read of a local to a synthetic `UastKotlinPsiVariable` rather than to the
 * `KtProperty` that declares it, and its navigation element does not lead back either. Going through
 * UAST and comparing source PSI is what bridges the two, and every walk that follows a local needs
 * it, so it lives here.
 */
object UastDeclarations {

    /** Whether [resolved] denotes [declaration]. */
    fun denotes(resolved: PsiElement?, declaration: PsiElement): Boolean {
        if (resolved == null) return false
        if (resolved === declaration || resolved.navigationElement === declaration) return true
        return resolved.toUElement()?.sourcePsi === declaration
    }
}
