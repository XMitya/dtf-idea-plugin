package com.xmitya.ideadtf

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.TestActionEvent
import com.xmitya.ideadtf.search.DtfTaskSearcher
import com.xmitya.ideadtf.toolwindow.DtfTaskDataKeys
import com.xmitya.ideadtf.toolwindow.DtfTaskEntry
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshot
import com.xmitya.ideadtf.toolwindow.DtfTaskSnapshotBuilder
import com.xmitya.ideadtf.toolwindow.action.CopyTaskClassNameAction
import com.xmitya.ideadtf.toolwindow.action.CopyTaskNameAction
import com.xmitya.ideadtf.toolwindow.action.GoToScheduleCallsAction
import com.xmitya.ideadtf.toolwindow.action.GoToTaskConfigurationAction
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import javax.swing.JPanel

/**
 * The DTF Tasks tree's own context menu.
 *
 * The tree lists tasks but used to say nothing about any of them: everything the plugin knows was
 * reachable only from the gutter, which needs the task's source open first. These entries carry the
 * same two searches to the panel, and copy the two names a row is known by.
 *
 * Driven through the data context rather than the tree, which is what the actions themselves read -
 * so no showing component hierarchy is needed, the same way `DtfFlowActionTest` works.
 */
class DtfTaskTreeActionTest : DtfFixtureTestCase() {

    private val copyName = CopyTaskNameAction()
    private val copyClassName = CopyTaskClassNameAction()
    private val goToConfig = GoToTaskConfigurationAction()
    private val goToCalls = GoToScheduleCallsAction()

    fun testTheTreeMenuOffersNavigationCopyAndTheDiagram() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Dtf.TaskTree.PopupMenu")
        assertTrue("Dtf.TaskTree.PopupMenu must be a group", group is ActionGroup)

        assertEquals(
            listOf(
                "EditSource",
                "Dtf.GoToTaskConfiguration",
                "Dtf.GoToScheduleCalls",
                null, // separator
                "Dtf.CopyTaskName",
                "Dtf.CopyTaskClassName",
                null, // separator
                "Dtf.ShowFlow",
            ),
            (group as ActionGroup).getChildren(null).map { manager.getId(it) },
        )
    }

    /** The whole point of a menu of its own: a gutter icon has no selected row to act on. */
    fun testTheGutterMenuStaysJustTheDiagram() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Dtf.Flow.PopupMenu") as ActionGroup

        assertEquals(listOf("Dtf.ShowFlow"), group.getChildren(null).map { manager.getId(it) })
    }

    /** None of them resolve PSI while deciding whether to show, but none of them need the EDT either. */
    fun testEveryEntryRunsItsUpdateOffTheEventThread() {
        for (action in listOf<AnAction>(copyName, copyClassName, goToConfig, goToCalls)) {
            assertEquals(action.javaClass.simpleName, ActionUpdateThread.BGT, action.actionUpdateThread)
        }
    }

    fun testCopyingTheTaskNamePutsItOnTheClipboard() {
        addTask("app/HelloTask.java", "HELLO_TASK")

        perform(copyName, entryOf("HelloTask"))

        assertEquals("HELLO_TASK", clipboard())
    }

    /** The qualified name is the one thing about the row that is not already on screen. */
    fun testCopyingTheClassNamePutsTheQualifiedNameOnTheClipboard() {
        addTask("app/HelloTask.java", "HELLO_TASK")

        perform(copyClassName, entryOf("HelloTask"))

        assertEquals("app.HelloTask", clipboard())
    }

    fun testCopyingSeveralRowsGivesALinePerRow() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addSecondTask()

        perform(copyName, entryOf("HelloTask"), entryOf("ByeTask"))

        assertEquals(setOf("HELLO_TASK", "BYE_TASK"), clipboard()?.split("\n")?.toSet())
    }

    /** A task whose `getDef()` cannot be read still has a name on screen, and that is what is copied. */
    fun testCopyingATaskWithAnUnreadableDefinitionFallsBackToItsClassName() {
        addMysteryTask()

        perform(copyName, entryOf("MysteryTask"))

        assertEquals("MysteryTask", clipboard())
    }

    fun testTheCopyEntriesAreHiddenWithNothingSelected() {
        assertFalse(update(copyName).isEnabledAndVisible)
        assertFalse(update(copyClassName).isEnabledAndVisible)
    }

    fun testGoToConfigurationOpensTheTaskPropertiesBlock() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        val yaml = addSettingsYaml()

        perform(goToConfig, entryOf("HelloTask"))

        assertEquals(yaml.virtualFile, openedFile())
        assertEquals("HELLO_TASK:", textAtCaret(11))
    }

    /** Two profiles are two answers, so the click opens neither and offers the list instead. */
    fun testGoToConfigurationOffersBothPlacesWhenThereAreTwo() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addSettingsYaml()
        addSettingsYaml("app/src/test/resources/application-test.yaml")

        perform(goToConfig, entryOf("HelloTask"))

        assertNull("nothing should have been opened", openedFile())
    }

    fun testGoToConfigurationSaysSoWhenThereIsNone() {
        addTask("app/HelloTask.java", "HELLO_TASK")

        perform(goToConfig, entryOf("HelloTask"))

        assertNull("nothing should have been opened", openedFile())
    }

    /** Configuration is looked up by the task's name, so without one there is nothing to ask. */
    fun testGoToConfigurationIsHiddenWhenTheDefinitionCouldNotBeRead() {
        addMysteryTask()

        assertFalse(update(goToConfig, entryOf("MysteryTask")).isEnabledAndVisible)
    }

    fun testGoToScheduleCallsOpensTheOnlyCaller() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        val caller = addCaller()

        perform(goToCalls, entryOf("HelloTask"))

        assertEquals(caller.virtualFile, openedFile())
    }

    fun testGoToScheduleCallsSaysSoWhenNobodySchedulesIt() {
        addTask("app/HelloTask.java", "HELLO_TASK")

        perform(goToCalls, entryOf("HelloTask"))

        assertNull("nothing should have been opened", openedFile())
    }

    /** The row holds no live PSI, so a class deleted since the scan has to come out as "none". */
    fun testGoToScheduleCallsSurvivesARowWhoseClassIsGone() {
        val anchor = addTask("app/HelloTask.java", "HELLO_TASK")

        perform(goToCalls, staleEntry(anchor))

        assertNull("nothing should have been opened", openedFile())
    }

    /** Two selected tasks are two popups; offering neither beats guessing which was meant. */
    fun testTheNavigationEntriesAreOfferedForOneRowOnly() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addSecondTask()
        val two = arrayOf(entryOf("HelloTask"), entryOf("ByeTask"))

        assertFalse(update(goToConfig, *two).isEnabledAndVisible)
        assertFalse(update(goToCalls, *two).isEnabledAndVisible)
    }

    private fun update(action: AnAction, vararg entries: DtfTaskEntry) =
        TestActionEvent.createTestEvent(action, contextOf(*entries)).also { action.update(it) }.presentation

    private fun perform(action: AnAction, vararg entries: DtfTaskEntry) =
        action.actionPerformed(TestActionEvent.createTestEvent(action, contextOf(*entries)))

    /**
     * A context component is not optional: placing the popup asks the platform to guess a location,
     * and with nothing focused and no component it has nowhere to measure from. A parentless panel
     * is enough, the same one the gutter tests anchor their click on.
     */
    private fun contextOf(vararg entries: DtfTaskEntry): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, JPanel() as Component)
        .add(DtfTaskDataKeys.TASK_ENTRIES, entries.toList())
        .build()

    private fun entryOf(className: String): DtfTaskEntry = ReadAction.compute<DtfTaskSnapshot, RuntimeException> {
        DtfTaskSnapshotBuilder(project).build(DtfTaskSearcher(project).findAllTasks(), stamp = 1L)
    }.modules.flatMap { it.tasks }.single { it.className == className }

    /** A row for a class that no longer exists; only the pointer has to be real. */
    private fun staleEntry(anchor: PsiFile): DtfTaskEntry = DtfTaskEntry(
        taskName = "GONE_TASK",
        className = "GoneTask",
        qualifiedName = "app.GoneTask",
        cronExpression = null,
        isCron = false,
        pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchor),
    )

    private fun clipboard(): String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)

    private fun openedFile() = FileEditorManager.getInstance(project).selectedEditor?.file

    private fun textAtCaret(length: Int): String {
        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) { "nothing opened" }
        val offset = editor.caretModel.offset
        return editor.document.getText(TextRange(offset, offset + length))
    }

    private fun addTask(path: String, taskName: String) = myFixture.addFileToProject(
        path,
        """
        package app;

        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        public class HelloTask implements Task<String> {
            public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("$taskName", String.class);

            @Override
            public TaskDef<String> getDef() { return HELLO; }
        }
        """.trimIndent(),
    )

    private fun addSecondTask() = myFixture.addFileToProject(
        "app/ByeTask.java",
        """
        package app;

        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        public class ByeTask implements Task<String> {
            @Override
            public TaskDef<String> getDef() { return TaskDef.privateTaskDef("BYE_TASK", String.class); }
        }
        """.trimIndent(),
    )

    /** Its definition arrives as a constructor argument, so there is no name to read off it. */
    private fun addMysteryTask() = myFixture.addFileToProject(
        "app/MysteryTask.java",
        """
        package app;

        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        public class MysteryTask implements Task<String> {
            private final TaskDef<String> def;

            public MysteryTask(TaskDef<String> def) { this.def = def; }

            @Override
            public TaskDef<String> getDef() { return def; }
        }
        """.trimIndent(),
    )

    private fun addCaller() = myFixture.addFileToProject(
        "app/Caller.java",
        """
        package app;

        import com.distributed_task_framework.model.ExecutionContext;
        import com.distributed_task_framework.service.DistributedTaskService;

        public class Caller {
            private DistributedTaskService distributedTaskService;

            public void createTask() throws Exception {
                distributedTaskService.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
            }
        }
        """.trimIndent(),
    )

    private fun addSettingsYaml(path: String = "app/src/main/resources/application.yaml") = myFixture.addFileToProject(
        path,
        """
        distributed-task:
          task-properties-group:
            task-properties:
              HELLO_TASK:
                max-parallel-in-cluster: 1
        """.trimIndent(),
    )
}
