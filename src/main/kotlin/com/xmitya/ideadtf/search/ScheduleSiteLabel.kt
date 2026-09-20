package com.xmitya.ideadtf.search

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement

/**
 * Who wrote a scheduling call, and how to name them.
 *
 * Both the popup row and a flow diagram's entry-point box answer the same question - which method,
 * in which class, at which line - so the climb and the formatting live here rather than in two
 * places that could drift.
 *
 * Must be called inside a read action.
 */
object ScheduleSiteLabel {

    /**
     * @param owner the class the call is written in, needed by callers that have to decide whether
     *   it is itself a task rather than merely name it.
     */
    class Site(
        val method: UMethod?,
        val owner: PsiClass?,
        val methodName: String?,
        val className: String?,
        val qualifiedName: String?,
        /** `AppUploadService.java:91`, or just the file name when there is no document. */
        val location: String?,
    ) {
        /** What the entry is led with: the method, falling back to the class and then the file. */
        fun presentable(fallback: String): String = methodName?.let { "$it()" } ?: className ?: fallback
    }

    fun of(project: Project, element: PsiElement): Site {
        val enclosing = generateSequence(element.toUElement()) { it.uastParent }
        val method = enclosing.filterIsInstance<UMethod>().firstOrNull()
        val owner = enclosing.filterIsInstance<UClass>().firstOrNull()?.javaPsi
        return Site(
            method = method,
            owner = owner,
            methodName = method?.name,
            className = owner?.name,
            qualifiedName = owner?.qualifiedName,
            location = locationOf(project, element),
        )
    }

    /** The class a call is written in, without building the rest of the label. */
    fun ownerOf(element: PsiElement): PsiClass? = generateSequence(element.toUElement()) { it.uastParent }
        .filterIsInstance<UClass>()
        .firstOrNull()
        ?.javaPsi

    private fun locationOf(project: Project, element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return file.name
        val offset = element.textRange.startOffset
        if (offset !in 0..document.textLength) return file.name
        return "${file.name}:${document.getLineNumber(offset) + 1}"
    }
}
