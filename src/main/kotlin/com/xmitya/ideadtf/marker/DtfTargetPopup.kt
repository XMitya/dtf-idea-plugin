package com.xmitya.ideadtf.marker

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.xmitya.ideadtf.search.DtfPopupTarget
import java.awt.event.MouseEvent

/**
 * What every gutter icon and menu entry here does with the results of its search.
 *
 * The handlers answered different questions and then dispatched on the answer in exactly the same
 * way, down to the comment, so the dispatch is written once. A gutter click knows where it happened
 * and an action does not, hence the two ways in: a [MouseEvent], or a point the caller guessed from
 * its data context.
 */
internal object DtfTargetPopup {

    fun show(event: MouseEvent, targets: List<DtfPopupTarget>, title: String, emptyMessage: () -> String) =
        show(RelativePoint(event), targets, title, emptyMessage)

    /** Nothing found says so, one result opens straight away, several are offered as a list. */
    fun show(at: RelativePoint, targets: List<DtfPopupTarget>, title: String, emptyMessage: () -> String) {
        when (targets.size) {
            0 -> message(at, emptyMessage())

            1 -> targets.single().navigate(true)

            // The overload taking presentations as a parallel list, rather than the one taking a
            // function: that one is marked internal API.
            else -> createTargetPopup(title, targets, targets.map { it.presentation }) { it.navigate(true) }
                .show(at)
        }
    }

    fun message(event: MouseEvent, text: String) = message(RelativePoint(event), text)

    fun message(at: RelativePoint, text: String) {
        JBPopupFactory.getInstance()
            .createMessage(text)
            .show(at)
    }
}
