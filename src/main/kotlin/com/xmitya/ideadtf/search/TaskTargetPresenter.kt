package com.xmitya.ideadtf.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfFileColors
import com.xmitya.ideadtf.model.DtfTaskDefResolver

/**
 * Renders task classes for the popup.
 *
 * Must run inside the read action that produced them: everything PSI-dependent is turned into plain
 * strings here so that nothing is resolved later on the UI thread.
 */
class TaskTargetPresenter(private val project: Project) {

    fun present(tasks: List<PsiClass>): List<NavigableTaskTarget> = tasks.map { taskClass ->
        val anchor = sourceAnchorOf(taskClass)
        NavigableTaskTarget(
            pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchor),
            presentation = presentationFor(taskClass, anchor),
        )
    }

    /**
     * The row carries the task name as well as the class, because that is what the schedule call
     * spells out and what the popup is being asked about.
     */
    private fun presentationFor(taskClass: PsiClass, anchor: PsiElement): TargetPresentation {
        val presentable = taskClass.name ?: taskClass.qualifiedName ?: anchor.containingFile?.name.orEmpty()
        val qualifiedName = taskClass.qualifiedName
        val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName

        var builder = TargetPresentation.builder(presentable)
            .icon(AllIcons.Nodes.Class)
            .backgroundColor(DtfFileColors.of(project, anchor.containingFile?.virtualFile))
        if (qualifiedName != null) {
            val container = if (taskName != null) {
                DtfBundle.message("dtf.target.named", qualifiedName, taskName)
            } else {
                qualifiedName
            }
            builder = builder.containerText(container)
        }
        locationOf(anchor)?.let { builder = builder.locationText(it) }
        return builder.presentation()
    }

    /**
     * The declaration in source.
     *
     * A Kotlin class reaches us as a light class whose range points at the generated element, so
     * both the popup's `file:line` and the jump would otherwise be off.
     */
    private fun sourceAnchorOf(taskClass: PsiClass): PsiElement = taskClass.navigationElement?.takeIf { it.isValid } ?: taskClass

    /** `ScanFileTask.kt:7`, the thing you actually scan the list for. */
    private fun locationOf(anchor: PsiElement): String? {
        val file = anchor.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return file.name
        val offset = anchor.textRange?.startOffset ?: return file.name
        if (offset !in 0..document.textLength) return file.name
        return "${file.name}:${document.getLineNumber(offset) + 1}"
    }
}
