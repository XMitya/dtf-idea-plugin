package com.xmitya.ideadtf.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.pom.Navigatable
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.action.DtfFlowDataKeys
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The DTF Tasks panel: project, module, task.
 *
 * The tree is a plain [DefaultTreeModel] rebuilt from a finished snapshot rather than an
 * `AbstractTreeStructure` loading children lazily. The data is a flat listing computed in one pass,
 * so lazy loading would buy nothing and cost the ability to hand the whole thing over in a single
 * EDT hop.
 */
class DtfTaskTreePanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)

    /** Stamp of the snapshot on screen, and of the one being fetched; see [refreshIfStale]. */
    private var shownStamp: Long? = null
    private var pendingStamp: Long? = null

    init {
        // Hidden until there is a snapshot, and again whenever that snapshot is empty, so that the
        // placeholder text gets a chance to show instead of a lone project row saying "no tasks".
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = DtfTaskTreeRenderer()
        tree.emptyText.text = DtfBundle.message("dtf.toolwindow.progress")
        // canExpand: most modules are collapsed, so searching only the visible rows would
        // find no task at all. The text function is not optional - see DtfTaskTreeSearchText.
        TreeSpeedSearch.installOn(tree, true) { DtfTaskTreeSearchText.of(it) }
        // Two handlers because they resolve the target differently: double-click reads the node's
        // user object, Enter reads NAVIGATABLE_ARRAY out of the data context below.
        EditSourceOnDoubleClickHandler.install(tree)
        EditSourceOnEnterKeyHandler.install(tree)
        installPopupMenu()

        toolbar = createToolbar()
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
    }

    /** What the tool window should focus when it opens. */
    val preferredFocusComponent: JComponent get() = tree

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink.lazy(CommonDataKeys.NAVIGATABLE_ARRAY) {
            selectedEntries().takeIf { it.isNotEmpty() }?.toTypedArray<Navigatable>()
        }
        sink.lazy(DtfFlowDataKeys.FLOW_SCOPE) { selectedFlowScope() }
    }

    /**
     * The one selected row, as something the flow action can act on.
     *
     * A single row only: two selected tasks are two diagrams, and guessing which one was meant is
     * worse than offering none.
     */
    private fun selectedFlowScope(): DtfFlowScope? =
        when (val node = (tree.selectionPaths?.singleOrNull()?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is DtfTaskEntry -> DtfFlowScope.Task(node.qualifiedName, node.displayName)
            is DtfTaskModuleGroup -> node.moduleName?.let { DtfFlowScope.Module(it, it) }
            else -> null
        }

    /**
     * Right-click selects the row under the cursor first, which the plain popup installer does not
     * do - without it a right-click on an unselected row acts on whatever was selected before.
     */
    private fun installPopupMenu() {
        val group = ActionManager.getInstance().getAction(POPUP_GROUP_ID) as? ActionGroup ?: return
        PopupHandler.installFollowingSelectionTreePopup(tree, group, POPUP_PLACE)
    }

    /**
     * Scans unless the tree already reflects the project as it is now.
     *
     * Called every time the panel is shown, which is why the cheap stamp comparison matters: going
     * back and forth between the editor and the panel must not re-search the project each time.
     */
    fun refreshIfStale() {
        val stamp = DtfTaskScanService.stampOf(project)
        if (shownStamp == stamp || pendingStamp == stamp) return
        refresh()
    }

    fun refresh() {
        pendingStamp = DtfTaskScanService.stampOf(project)
        tree.setPaintBusy(true)
        tree.emptyText.text = DtfBundle.message("dtf.toolwindow.progress")
        DtfTaskScanService.getInstance(project).scan { snapshot ->
            pendingStamp = null
            shownStamp = snapshot.stamp
            show(snapshot)
        }
    }

    /** The EDT half of a scan. Public so that both of its outcomes can be asserted. */
    fun show(snapshot: DtfTaskSnapshot) {
        root.userObject = snapshot
        root.removeAllChildren()
        for (module in snapshot.modules) {
            val moduleNode = DefaultMutableTreeNode(module)
            module.tasks.forEach { moduleNode.add(DefaultMutableTreeNode(it, false)) }
            root.add(moduleNode)
        }
        treeModel.reload()

        tree.setPaintBusy(false)
        tree.emptyText.text = DtfBundle.message("dtf.toolwindow.empty")
        tree.isRootVisible = snapshot.modules.isNotEmpty()
        // Root always, and the single module when there is only one - but not 55 of them, which is
        // a wall rather than an overview.
        tree.expandPath(TreePath(root))
        if (snapshot.modules.size == 1) tree.expandPath(TreePath(arrayOf(root, root.getChildAt(0))))
    }

    private fun selectedEntries(): List<DtfTaskEntry> = tree.selectionPaths.orEmpty().mapNotNull {
        ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DtfTaskEntry)
    }

    private fun createToolbar(): JComponent {
        // The header variants, which is what gives the paired chevrons and the same shortcuts the
        // Project view has - expand-selected on the plain one, expand-all on shift.
        val common = CommonActionsManager.getInstance()
        val group = DefaultActionGroup(
            RefreshAction(),
            Separator.getInstance(),
            common.createExpandAllHeaderAction(tree),
            common.createCollapseAllHeaderAction(tree),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private inner class RefreshAction :
        DumbAwareAction(
            { DtfBundle.message("dtf.toolwindow.refresh") },
            { DtfBundle.message("dtf.toolwindow.refresh.description") },
            AllIcons.Actions.Refresh,
        ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = refresh()
    }

    private companion object {
        const val TOOLBAR_PLACE = "DtfTasksToolWindow"
        const val POPUP_PLACE = "DtfTasksToolWindowPopup"
        const val POPUP_GROUP_ID = "Dtf.Flow.PopupMenu"
    }
}
