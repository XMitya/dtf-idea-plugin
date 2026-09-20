package com.xmitya.ideadtf.toolwindow

import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * What speed search matches a row against.
 *
 * It has to be spelled out. `TreeSpeedSearch.installOn(tree)` matches `toString()` of the node's
 * user object, which here is `com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup@1f2e3d` - so every
 * row "contains" a c and none contains "ca", while the highlight is computed from the text actually
 * painted. Typing then walks rows that plainly do not match and stops dead on the second character.
 *
 * So this returns what the row shows, minus the parts that are not identifiers: the task count would
 * make "task" match every module, and a cron expression would make digits match at random. Only the
 * task row differs from what the row is simply called - see [DtfTaskRowName].
 */
internal object DtfTaskTreeSearchText {

    fun of(path: TreePath): String? = when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is DtfTaskEntry -> of(node)
        else -> DtfTaskRowName.of(node)
    }

    /** Both halves of the row, so a task is findable by its definition or by its class. */
    private fun of(entry: DtfTaskEntry): String = if (entry.taskName == null || entry.className.isEmpty()) {
        entry.displayName
    } else {
        "${entry.taskName} ${entry.className}"
    }
}
