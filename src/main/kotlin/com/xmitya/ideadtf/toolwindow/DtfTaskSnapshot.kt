package com.xmitya.ideadtf.toolwindow

import com.intellij.psi.SmartPsiElementPointer
import com.xmitya.ideadtf.search.PointerNavigatable

/**
 * One task, as the tool window shows it.
 *
 * Holds no live PSI, only a smart pointer: the tree outlives the read action that built it, and the
 * EDT must never resolve anything - the same rule the popup results follow.
 *
 * It is itself a [Navigatable] because that is what makes double-click work:
 * `EditSourceOnDoubleClickHandler` resolves the target through `TreeUtil.getNavigatable`, which
 * looks at the node's user object rather than at the data context. Enter goes the other way, through
 * `CommonDataKeys.NAVIGATABLE_ARRAY` - see `DtfTaskTreePanel.uiDataSnapshot`.
 */
class DtfTaskEntry(
    /** From `TaskDef.privateTaskDef("...")`; null when `getDef()` could not be read. */
    val taskName: String?,
    val className: String,
    val qualifiedName: String,
    /** The cron this task runs on, when it is a cron task and the expression could be read. */
    val cronExpression: String?,
    val isCron: Boolean,
    pointer: SmartPsiElementPointer<*>,
) : PointerNavigatable(pointer) {

    /** What the row leads with, and what sorting and speed search key on. */
    val displayName: String get() = taskName ?: className
}

/** The tasks of one module. [moduleName] is null for the sources that belong to no module. */
class DtfTaskModuleGroup(val moduleName: String?, val tasks: List<DtfTaskEntry>)

/**
 * The whole tree, as of one scan.
 *
 * [stamp] is what the scan saw, so that showing the panel again can tell "nothing has changed" from
 * "re-scan" without doing the work to find out.
 */
class DtfTaskSnapshot(val projectName: String, val modules: List<DtfTaskModuleGroup>, val stamp: Long) {
    val taskCount: Int get() = modules.sumOf { it.tasks.size }
}
