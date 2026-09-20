package com.xmitya.ideadtf.marker

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.xmitya.ideadtf.search.DtfPopupTarget
import java.awt.event.MouseEvent

/**
 * What every gutter icon here does with the results of its search.
 *
 * The three handlers answered three different questions and then dispatched on the answer in
 * exactly the same way, down to the comment, so the dispatch is written once.
 */
internal object DtfTargetPopup {

    /** Nothing found says so, one result opens straight away, several are offered as a list. */
    fun show(event: MouseEvent, targets: List<DtfPopupTarget>, title: String, emptyMessage: () -> String) {
        when (targets.size) {
            0 -> message(event, emptyMessage())

            1 -> targets.single().navigate(true)

            // The overload taking presentations as a parallel list, rather than the one taking a
            // function: that one is marked internal API.
            else -> createTargetPopup(title, targets, targets.map { it.presentation }) { it.navigate(true) }
                .show(RelativePoint(event))
        }
    }

    fun message(event: MouseEvent, text: String) {
        JBPopupFactory.getInstance()
            .createMessage(text)
            .show(RelativePoint(event))
    }
}
