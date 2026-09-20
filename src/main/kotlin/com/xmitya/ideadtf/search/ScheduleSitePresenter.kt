package com.xmitya.ideadtf.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.xmitya.ideadtf.DtfBundle
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement

/**
 * Renders schedule call sites for the popup.
 *
 * Must run inside the read action that produced the sites: everything PSI-dependent is turned into
 * plain strings here so that nothing is resolved later on the UI thread.
 */
class ScheduleSitePresenter(private val project: Project) {

    fun present(sites: List<ScheduleCallSite>): List<NavigableScheduleSite> = sites.map { site ->
        NavigableScheduleSite(
            pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(site.element),
            presentation = presentationFor(site),
            tier = site.tier,
        )
    }

    private fun presentationFor(site: ScheduleCallSite): TargetPresentation {
        val enclosing = generateSequence(site.element.toUElement()) { it.uastParent }
        val method = enclosing.filterIsInstance<UMethod>().firstOrNull()
        val owner = enclosing.filterIsInstance<UClass>().firstOrNull()

        val presentable = method?.name?.let { "$it()" }
            ?: owner?.javaPsi?.name
            ?: site.element.containingFile?.name
            ?: site.element.text
        val container = owner?.javaPsi?.qualifiedName
        val location = locationOf(site)

        var builder = TargetPresentation.builder(presentable)
            .icon(if (method != null) AllIcons.Nodes.Method else AllIcons.Nodes.Class)
        if (container != null) {
            val suffix = if (site.tier == ScheduleTier.WRAPPER) " - " + DtfBundle.message("dtf.tier.wrapper") else ""
            builder = builder.containerText(container + suffix)
        }
        if (location != null) {
            builder = builder.locationText(location)
        }
        return builder.presentation()
    }

    /** `File.kt:42`, the thing you actually scan the list for. */
    private fun locationOf(site: ScheduleCallSite): String? {
        val file = site.element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return file.name
        val offset = site.element.textRange.startOffset
        if (offset !in 0..document.textLength) return file.name
        return "${file.name}:${document.getLineNumber(offset) + 1}"
    }
}
