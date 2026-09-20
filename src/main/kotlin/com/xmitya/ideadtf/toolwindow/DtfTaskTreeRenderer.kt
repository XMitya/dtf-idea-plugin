package com.xmitya.ideadtf.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.model.DtfRowText
import com.xmitya.ideadtf.model.DtfTextStyle
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Paints the three levels of the tree.
 *
 * Tasks carry the very icons the gutter uses, so that "this one runs on a schedule" is the same
 * piece of knowledge in both places rather than two conventions to learn.
 */
class DtfTaskTreeRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
            is DtfTaskEntry -> renderTask(node)
            is DtfTaskModuleGroup -> renderModule(node)
            is DtfTaskSnapshot -> renderProject(node)
        }
    }

    private fun renderTask(entry: DtfTaskEntry) {
        icon = if (entry.isCron) DtfIcons.CronGutter else DtfIcons.TaskGutter
        for (run in DtfRowText.taskRuns(entry.taskName, entry.className, entry.cronExpression)) {
            append(run.text, attributesOf(run.style))
        }
    }

    private fun attributesOf(style: DtfTextStyle): SimpleTextAttributes = when (style) {
        DtfTextStyle.LEAD -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        DtfTextStyle.GREY -> SimpleTextAttributes.GRAYED_ATTRIBUTES
    }

    private fun renderModule(group: DtfTaskModuleGroup) {
        icon = AllIcons.Nodes.Module
        append(group.moduleName ?: DtfBundle.message("dtf.toolwindow.module.none"))
        append(
            "  " + DtfBundle.message("dtf.toolwindow.module.count", group.tasks.size),
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
    }

    private fun renderProject(snapshot: DtfTaskSnapshot) {
        icon = AllIcons.Nodes.Project
        append(snapshot.projectName)
        append(
            "  " + DtfBundle.message("dtf.toolwindow.module.count", snapshot.taskCount),
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
    }
}
