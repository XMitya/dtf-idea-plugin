package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Disposer
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.scope.TestsScope
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.ui.FileColorManager
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowGraphBuilder
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.editor.DtfFlowPanel
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.search.NavigableScheduleSite
import com.xmitya.ideadtf.search.NavigableTaskConfigSite
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSitePresenter
import com.xmitya.ideadtf.search.TaskConfigSitePresenter
import com.xmitya.ideadtf.search.TaskTargetPresenter
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry
import com.xmitya.ideadtf.toolwindow.DtfTaskModuleGroup
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.awt.Color
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Where a result comes from, shown the way the IDE shows it everywhere else.
 *
 * Four rows reading `task settings` differ only by the tail of their container - `bin/main` against
 * `bin/test` - which is read rather than seen. The platform's answer to that is the file colour, so
 * this asserts the plugin asks for it rather than inventing a green of its own.
 */
class DtfTestSourceColorTest : DtfFixtureTestCase() {

    /**
     * The light fixture owns one production source root, under which nothing can be a test. A test
     * source root and a test resource root are what there is to colour at all. Both are registered
     * by URL: the directories do not exist yet, the first file written into them creates them.
     */
    private val withTestRoots = object : ProjectDescriptor(LanguageLevel.JDK_21_PREVIEW) {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            contentEntry.addSourceFolder("${contentEntry.url}/tst", JavaSourceRootType.TEST_SOURCE)
            contentEntry.addSourceFolder("${contentEntry.url}/tstRes", JavaResourceRootType.TEST_RESOURCE)
        }
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = withTestRoots

    /** The green itself is a theme colour, so it is asked for rather than written down. */
    private val testsColor: Color get() = requireNotNull(FileColorManager.getInstance(project).getScopeColor(TestsScope.NAME))

    fun testACallFromATestClassIsColouredAndAProductionOneIsNot() {
        addTask("app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        addCaller("app/Caller.java", "app", "Caller")
        addCaller("tst/app/CallerTest.java", "app", "CallerTest")

        val byLocation = scheduleSites().associate { it.presentation.locationText to it.presentation.backgroundColor }
        assertEquals(setOf("Caller.java:10", "CallerTest.java:10"), byLocation.keys)
        assertNull(byLocation["Caller.java:10"])
        assertEquals(testsColor, byLocation["CallerTest.java:10"])
    }

    /** The diagram paints a test caller's box the way the popup paints its row, and leaves the rest plain. */
    fun testAFlowBoxForATestCallerIsColouredAndAProductionOneIsNot() {
        addTask("app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        addCaller("app/Caller.java", "app", "Caller")
        addCaller("tst/app/CallerTest.java", "app", "CallerTest")
        val scope = DtfFlowScope.Task("app.HelloTask", "HELLO_TASK")
        val graph = ReadAction.compute<DtfFlowGraph, RuntimeException> { DtfFlowGraphBuilder(project).build(scope) }
        val panel = DtfFlowPanel(project, scope)
        try {
            panel.show(graph)

            val canvas = panel.flowCanvas()
            val byClass = graph.nodes.filterIsInstance<DtfFlowCallerNode>().associate { it.className to canvas.fileColorOfNode(it.id) }
            assertEquals(setOf("Caller", "CallerTest"), byClass.keys)
            assertNull(byClass["Caller"])
            assertEquals(testsColor, byClass["CallerTest"])
            assertNull(canvas.fileColorOfNode(graph.nodes.filterIsInstance<DtfFlowTaskNode>().single().id))
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testConfigurationInTestResourcesIsColouredAndInMainResourcesIsNot() {
        addTask("app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        addConfigYaml("app/src/main/resources/application.yaml")
        addConfigYaml("tstRes/application-test.yaml")

        val byLocation = configSites().associate { it.presentation.locationText to it.presentation.backgroundColor }
        assertEquals(setOf("application.yaml:4", "application-test.yaml:4"), byLocation.keys)
        assertNull(byLocation["application.yaml:4"])
        assertEquals(testsColor, byLocation["application-test.yaml:4"])
    }

    /** The other direction: the icon on a `schedule(...)` call, listing the task it launches. */
    fun testATaskDeclaredInTestSourcesIsColouredInTheReversePopup() {
        addTask("tst/app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        val presented = ReadAction.compute<List<Color?>, RuntimeException> {
            TaskTargetPresenter(project).present(listOf(findClass("app.HelloTask"))).map { it.presentation.backgroundColor }
        }
        assertEquals(listOf(testsColor), presented)
    }

    fun testATestOnlyTaskIsColouredInTheTreeAndAProductionOneIsNot() {
        addTask("app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        addTask("tst/app/FixtureTask.java", "app", "FixtureTask", "FIXTURE_TASK")

        assertEquals(testsColor, treeBackgroundOf { it is DtfTaskEntry && it.className == "FixtureTask" })
        assertNull(treeBackgroundOf { it is DtfTaskEntry && it.className == "HelloTask" })
    }

    /** A module stands for no file, so there is nothing to take a colour from. */
    fun testAModuleRowIsNotColoured() {
        addTask("tst/app/FixtureTask.java", "app", "FixtureTask", "FIXTURE_TASK")
        assertNull(treeBackgroundOf { it is DtfTaskModuleGroup })
    }

    /**
     * The point of taking the colour from File Colors rather than deciding it here: switching them
     * off switches this off too, in the popups and in the tree alike.
     */
    fun testTurningFileColoursOffLeavesEveryRowPlain() {
        addTask("tst/app/HelloTask.java", "app", "HelloTask", "HELLO_TASK")
        addCaller("tst/app/CallerTest.java", "app", "CallerTest")
        addConfigYaml("tstRes/application-test.yaml")
        val manager = FileColorManager.getInstance(project)
        manager.isEnabled = false
        try {
            assertNull(scheduleSites().single().presentation.backgroundColor)
            assertNull(configSites().single().presentation.backgroundColor)
            assertNull(treeBackgroundOf { it is DtfTaskEntry })
        } finally {
            manager.isEnabled = true
        }
    }

    private fun scheduleSites(): List<NavigableScheduleSite> = ReadAction.compute<List<NavigableScheduleSite>, RuntimeException> {
        ScheduleSitePresenter(project).present(ScheduleCallSearcher(project).findScheduleSites(findClass("app.HelloTask")))
    }

    private fun configSites(): List<NavigableTaskConfigSite> = ReadAction.compute<List<NavigableTaskConfigSite>, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        TaskConfigSitePresenter(project)
            .present(DtfTaskConfigSource.EP.extensionList.flatMap { it.findSites(project, "HELLO_TASK", scope) })
    }

    /**
     * Through `getPathBackground` rather than `getFileColorFor`, so that the tree's own switch is
     * part of what is asserted: with it off the platform never asks for a colour at all.
     */
    private fun treeBackgroundOf(matches: (Any?) -> Boolean): Color? {
        val tree = DtfTaskTreePanel(project).also { it.show(snapshot()) }.preferredFocusComponent as Tree
        val path = pathOf(tree, matches)
        return tree.getPathBackground(path, tree.getRowForPath(path))
    }

    private fun pathOf(tree: Tree, matches: (Any?) -> Boolean): TreePath {
        fun walk(node: DefaultMutableTreeNode): TreePath? {
            if (matches(node.userObject)) return TreePath(node.path)
            for (index in 0 until node.childCount) walk(node.getChildAt(index) as DefaultMutableTreeNode)?.let { return it }
            return null
        }
        return requireNotNull(walk(tree.model.root as DefaultMutableTreeNode)) { "no such row" }
    }

    private fun snapshot(): DtfTaskSnapshot = ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
        DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
    }

    private fun addTask(path: String, packageName: String, className: String, taskName: String) {
        myFixture.addFileToProject(
            path,
            """
            package $packageName;

            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class $className implements Task<String> {
                public static final TaskDef<String> DEF = TaskDef.privateTaskDef("$taskName", String.class);

                @Override
                public TaskDef<String> getDef() { return DEF; }
            }
            """.trimIndent(),
        )
    }

    /** The call lands on line 10 in both copies, which is what tells the two rows apart. */
    private fun addCaller(path: String, packageName: String, className: String) {
        myFixture.addFileToProject(
            path,
            """
            package $packageName;

            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class $className {
                private DistributedTaskService distributedTaskService;

                public void createTask() throws Exception {
                    distributedTaskService.schedule(HelloTask.DEF, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
    }

    private fun addConfigYaml(path: String) {
        myFixture.addFileToProject(
            path,
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            """.trimIndent(),
        )
    }
}
