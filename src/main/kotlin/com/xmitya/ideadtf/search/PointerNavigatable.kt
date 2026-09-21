package com.xmitya.ideadtf.search

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.SmartPsiElementPointer

/**
 * Opening the place a smart pointer points at.
 *
 * Every result this plugin hands to the UI - a schedule call, a task, a cron line, a tool window row,
 * a diagram box - is a pointer plus a label, and all of them open the same way. That is one
 * behaviour, so it is written once here rather than five times.
 */
open class PointerNavigatable(private val pointer: SmartPsiElementPointer<*>) : Navigatable {

    /** The file pointed into. Navigating needs it, and so does colouring a row by its source root. */
    val virtualFile: VirtualFile? get() = pointer.virtualFile

    /**
     * Where in the file to put the caret; null once the pointer points at nothing.
     *
     * Inside a read action of its own, because every caller navigates straight from a Swing event -
     * a double-click on a diagram box, `EditSourceOnDoubleClickHandler` on a tree row - and holds
     * nothing but the EDT's write-intent lock, which is not read access. Restoring a pointer is a
     * PSI read: for a file the IDE has not parsed yet it goes through the stub index, and the
     * platform reports "Read access is allowed from inside read-action only" instead of navigating.
     */
    fun targetOffset(): Int? = ReadAction.compute<Int?, RuntimeException> { pointer.range?.startOffset }

    override fun navigate(requestFocus: Boolean) {
        val file = virtualFile ?: return
        val offset = targetOffset() ?: return
        PsiNavigationSupport.getInstance()
            .createNavigatable(pointer.project, file, offset)
            .navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()
}
