package com.xmitya.ideadtf.toolwindow

import com.intellij.ide.TextCopyProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread

/**
 * Copying what the selected rows are called.
 *
 * Without this the platform's Copy is simply disabled - nothing here offered a `CopyProvider` - and
 * the keystroke falls through to Swing's own tree transfer handler, which builds its text from
 * `toString()` of the node's user object and so puts `DtfTaskEntry@55207c29` on the clipboard.
 *
 * Every kind of row answers, not only tasks: a provider that declined on module and project rows
 * would leave that same fallback, and that same hash code, in place for them.
 *
 * @param rows user objects of the selected rows, read when the data snapshot was taken.
 */
internal class DtfTaskCopyProvider(private val rows: List<Any>) : TextCopyProvider() {

    /** The rows were read on the EDT when the snapshot was taken, so nothing here goes back to Swing. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Null rather than an empty list: that is what the base class reads as "nothing to copy". */
    override fun getTextLinesToCopy(): Collection<String>? = rows.mapNotNull { DtfTaskRowName.of(it) }.takeIf { it.isNotEmpty() }
}
