package com.xmitya.ideadtf.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPointerManager
import com.xmitya.ideadtf.DtfBundle

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
        val label = ScheduleSiteLabel.of(project, site.element)
        val fallback = site.element.containingFile?.name ?: site.element.text

        var builder = TargetPresentation.builder(label.presentable(fallback))
            .icon(if (label.method != null) AllIcons.Nodes.Method else AllIcons.Nodes.Class)
        label.qualifiedName?.let { container ->
            val suffix = if (site.tier == ScheduleTier.WRAPPER) " - " + DtfBundle.message("dtf.tier.wrapper") else ""
            builder = builder.containerText(container + suffix)
        }
        label.location?.let { builder = builder.locationText(it) }
        return builder.presentation()
    }
}
