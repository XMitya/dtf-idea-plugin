package com.xmitya.ideadtf.toolwindow.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.xmitya.ideadtf.toolwindow.DtfTaskDataKeys
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry

/**
 * Copying one field of the selected tasks.
 *
 * Cmd+C already copies the row as it reads, which is the usual want; these name a particular field
 * so that the one that is not on screen - the qualified class name - can be had without opening the
 * task. A multiple selection gives a line per row, the same as the keystroke.
 *
 * `DumbAware` on purpose: the fields were read when the tree was scanned, so there is nothing here
 * that indexing could make unavailable.
 */
abstract class DtfCopyTaskTextAction : DumbAwareAction() {

    /** Reads one data key holding plain strings; no PSI, so nothing needs the EDT. */
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = linesOf(e).isNotEmpty()
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val lines = linesOf(e)
        if (lines.isEmpty()) return
        CopyPasteManager.copyTextToClipboard(lines.joinToString("\n"))
    }

    protected abstract fun textOf(entry: DtfTaskEntry): String?

    private fun linesOf(e: AnActionEvent): List<String> =
        DtfTaskDataKeys.TASK_ENTRIES.getData(e.dataContext).orEmpty().mapNotNull { textOf(it) }
}

/**
 * The `TaskDef` name.
 *
 * Falls back to the class name for a task whose `getDef()` could not be read, so that the entry
 * copies what the row shows rather than disappearing - the same answer Cmd+C gives.
 */
class CopyTaskNameAction : DtfCopyTaskTextAction() {

    override fun textOf(entry: DtfTaskEntry): String = entry.displayName
}

/** The qualified name, which is the one thing about the row that is not on screen. */
class CopyTaskClassNameAction : DtfCopyTaskTextAction() {

    override fun textOf(entry: DtfTaskEntry): String? = entry.qualifiedName.takeIf { it.isNotEmpty() }
}
