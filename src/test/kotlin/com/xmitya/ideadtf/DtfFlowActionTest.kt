package com.xmitya.ideadtf

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.testFramework.TestActionEvent
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.action.DtfFlowDataKeys
import com.xmitya.ideadtf.flow.action.ShowDtfFlowAction
import com.xmitya.ideadtf.flow.editor.DtfFlowVirtualFile

/**
 * What offers the diagram, and from where.
 *
 * Four invocation sites share one action, so what is asserted here is that each context it can be
 * handed resolves to the right subject - and, just as importantly, that a context naming nothing DTF
 * leaves the menu alone.
 */
class DtfFlowActionTest : DtfFlowFixtureTestCase() {

    private val action = ShowDtfFlowAction()

    /** Resolving the selection reads the stub index, which must not happen on the EDT. */
    fun testUpdateRunsOffTheEventThread() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun testTheActionAndItsPopupGroupAreRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("Dtf.ShowFlow"))
        val group = ActionManager.getInstance().getAction("Dtf.Flow.PopupMenu")
        assertTrue("Dtf.Flow.PopupMenu must be a group", group is ActionGroup)
    }

    /** The tool window already knows what is selected, and says so through its own key. */
    fun testScopeOfferedByTheToolWindowIsUsedAsIs() {
        val context = SimpleDataContext.builder()
            .add(DtfFlowDataKeys.FLOW_SCOPE, DtfFlowScope.Task("HelloTask", "HELLO"))
            .build()

        val presentation = update(context)

        assertTrue(presentation.isEnabledAndVisible)
        assertEquals(DtfBundle.message("dtf.flow.action.text.named", "HELLO"), presentation.text)
    }

    /** A module row in the Project view is a whole module's worth of flows. */
    fun testModuleContextResolvesToTheModule() {
        val presentation = update(SimpleDataContext.builder().add(LangDataKeys.MODULE_CONTEXT, module).build())

        assertTrue(presentation.isEnabledAndVisible)
        assertEquals(DtfBundle.message("dtf.flow.action.text.named", module.name), presentation.text)
    }

    fun testTaskClassInTheProjectViewResolvesToThatTask() {
        addJavaTask("HelloTask", "HELLO")

        val presentation = update(SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT, findClass("HelloTask")).build())

        assertTrue(presentation.isEnabledAndVisible)
        assertEquals(DtfBundle.message("dtf.flow.action.text.named", "HELLO"), presentation.text)
    }

    /**
     * Task names are SCREAMING_SNAKE_CASE, and a presentation parses `_` as a mnemonic marker by
     * default - which would quietly show HELLO_TASK as HELLOTASK in every menu the action sits in.
     */
    fun testUnderscoresInATaskNameSurviveIntoTheMenu() {
        addJavaTask("HelloTask", "HELLO_TASK")

        val presentation = update(SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT, findClass("HelloTask")).build())

        assertEquals(DtfBundle.message("dtf.flow.action.text.named", "HELLO_TASK"), presentation.text)
    }

    /**
     * The entry sits in the Project view popup, on every file: a class that is not a task must leave
     * no trace there at all, rather than a permanently greyed-out row.
     */
    fun testAnOrdinaryClassOffersNothing() {
        myFixture.addFileToProject("Plain.java", "public class Plain {}")

        assertFalse(update(SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT, findClass("Plain")).build()).isEnabledAndVisible)
    }

    fun testAnEmptyContextOffersNothing() {
        assertFalse(update(SimpleDataContext.EMPTY_CONTEXT).isEnabledAndVisible)
    }

    /** A file holding a task is what the Project view hands over for a file row. */
    fun testATaskFileResolvesToItsTask() {
        addJavaTask("HelloTask", "HELLO")
        val file = myFixture.findFileInTempDir("HelloTask.java")
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file)

        assertTrue(update(SimpleDataContext.builder().add(CommonDataKeys.PSI_FILE, psiFile!!).build()).isEnabledAndVisible)
    }

    fun testPerformingTheActionOpensTheDiagram() {
        addJavaTask("HelloTask", "HELLO")
        val context = SimpleDataContext.builder().add(DtfFlowDataKeys.FLOW_SCOPE, DtfFlowScope.Task("HelloTask", "HELLO")).build()

        action.actionPerformed(TestActionEvent.createTestEvent(action, withProject(context)))

        assertEquals(1, FileEditorManager.getInstance(project).openFiles.filterIsInstance<DtfFlowVirtualFile>().size)
    }

    /** Right-clicking a `schedule(...)` call in the editor asks about the flow that call starts. */
    fun testAScheduleCallInTheEditorResolvesToItsFlow() {
        addJavaTask("HelloTask", "HELLO")
        val caller = myFixture.addFileToProject(
            "Caller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Caller {
                private DistributedTaskService distributedTaskService;

                public void start() throws Exception {
                    distributedTaskService.schedule(HelloTask.DEF, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(caller.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caller.text.indexOf("schedule(HelloTask"))

        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(CommonDataKeys.CARET, myFixture.editor.caretModel.currentCaret)
            .build()

        assertTrue(update(context).isEnabledAndVisible)
    }

    /**
     * In an editor popup `PSI_ELEMENT` is whatever the caret resolves to, and outside a JVM file that
     * can be a fake element standing in for a reference target - a config key in a YAML file resolves
     * to one. Such an element has no text, so reading it as source blew the menu up with an NPE.
     */
    fun testAnElementWithoutTextOffersNothing() {
        addJavaTask("HelloTask", "HELLO")
        val config = myFixture.addFileToProject("application.yaml", "commons:\n  jackson:\n    disable-nulls: true\n")

        val context = SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT, TextlessElement(config)).build()

        assertFalse(update(context).isEnabledAndVisible)
    }

    /** Stands in for the platform's own textless elements, as every [FakePsiElement] is. */
    private class TextlessElement(private val anchor: PsiElement) : FakePsiElement() {
        override fun getParent(): PsiElement = anchor
    }

    private fun update(context: DataContext) = TestActionEvent.createTestEvent(action, withProject(context))
        .also { action.update(it) }
        .presentation

    private fun withProject(context: DataContext): DataContext =
        SimpleDataContext.builder().setParent(context).add(CommonDataKeys.PROJECT, project).build()
}
