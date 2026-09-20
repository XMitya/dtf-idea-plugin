package com.xmitya.ideadtf.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import java.util.function.Supplier
import javax.swing.Icon

/**
 * A gutter marker whose right-click offers the flow diagram.
 *
 * Left-clicking either icon already answers a question - where is this scheduled, what does this
 * launch - and those must not change. The diagram answers a third one, and a right-click is where a
 * gutter icon puts extra answers, so it goes there rather than into a menu on the click.
 *
 * Shared by both providers, because the question is the same from either end of the arrow.
 */
class DtfFlowMenuLineMarkerInfo<T : PsiElement>(
    element: T,
    range: TextRange,
    icon: Icon,
    tooltipProvider: Function<in T, String>?,
    navigationHandler: GutterIconNavigationHandler<T>?,
    alignment: GutterIconRenderer.Alignment,
    accessibleNameProvider: Supplier<String>,
) : LineMarkerInfo<T>(element, range, icon, tooltipProvider, navigationHandler, alignment, accessibleNameProvider) {

    override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerGutterIconRenderer<T>(this) {
        override fun getPopupMenuActions(): ActionGroup? = ActionManager.getInstance().getAction(FLOW_MENU_GROUP_ID) as? ActionGroup
    }

    private companion object {
        const val FLOW_MENU_GROUP_ID = "Dtf.Flow.PopupMenu"
    }
}
