package com.xmitya.ideadtf.search

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.SmartPsiElementPointer

/**
 * Opening the place a smart pointer points at.
 *
 * Every result this plugin hands to the UI - a schedule call, a task, a cron line, a tool window row,
 * a diagram box - is a pointer plus a label, and all of them have to open without resolving PSI on
 * the EDT. That is one behaviour, so it is written once here rather than five times.
 */
open class PointerNavigatable(private val pointer: SmartPsiElementPointer<*>) : Navigatable {

    /** The file pointed into. Navigating needs it, and so does colouring a row by its source root. */
    val virtualFile: VirtualFile? get() = pointer.virtualFile

    override fun navigate(requestFocus: Boolean) {
        val file = virtualFile ?: return
        val offset = pointer.range?.startOffset ?: return
        PsiNavigationSupport.getInstance()
            .createNavigatable(pointer.project, file, offset)
            .navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()
}
