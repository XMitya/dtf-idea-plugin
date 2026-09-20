package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.cron.DtfCronConfigSource
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.search.CronSitePresenter
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSitePresenter
import com.xmitya.ideadtf.search.ScheduledTaskSearcher
import com.xmitya.ideadtf.search.TaskTargetPresenter

/**
 * Picking a row in the popup has to land somewhere.
 *
 * Each popup entry holds a smart pointer rather than live PSI - see [com.xmitya.ideadtf.search.NavigableScheduleSite] -
 * so the jump goes through the pointer's file and range, and a pointer built against the wrong
 * element is invisible until someone clicks. These tests click.
 */
class DtfPopupNavigationTest : DtfFixtureTestCase() {

    fun testScheduleSiteOpensTheCall() {
        addTask("app/HelloTask.java")
        val caller = myFixture.addFileToProject(
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

        val site = ReadAction.compute<_, RuntimeException> {
            val found = ScheduleCallSearcher(project).findScheduleSites(findClass("app.HelloTask"))
            ScheduleSitePresenter(project).present(found)
        }.single()
        site.navigate(true)

        assertEquals(caller.virtualFile, openedFile())
        assertEquals("distributedTaskService.schedule", textAtCaret(31))
    }

    fun testCronSiteOpensTheConfiguredExpression() {
        val yaml = myFixture.addFileToProject(
            "app/src/main/resources/application.yaml",
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            """.trimIndent(),
        )

        val site = ReadAction.compute<_, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val found = DtfCronConfigSource.EP.extensionList.flatMap { it.findSites(project, "HELLO_TASK", scope) }
            CronSitePresenter(project).present(found)
        }.single()
        site.navigate(true)

        assertEquals(yaml.virtualFile, openedFile())
        // The site anchors on the task's entry rather than the `cron` line under it, so that a
        // mapping holding several settings opens on the task rather than mid-block.
        assertEquals("HELLO_TASK:", textAtCaret(11))
    }

    fun testTaskTargetOpensTheTaskClass() {
        val task = addTask("app/HelloTask.java")
        myFixture.configureByText(
            "Scheduler.java",
            """
            import app.HelloTask;
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Scheduler {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        val target = ReadAction.compute<_, RuntimeException> {
            val call = leavesOfConfiguredFile().firstNotNullOf { DtfScheduleMarkers.scheduleCallAt(it) }
            TaskTargetPresenter(project).present(ScheduledTaskSearcher(project).findTasks(call))
        }.single()
        target.navigate(true)

        assertEquals(task.virtualFile, openedFile())
        assertEquals("HelloTask", textAtCaret(9))
    }

    private fun addTask(path: String): PsiFile = myFixture.addFileToProject(
        path,
        """
        package app;

        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;

        public class HelloTask implements Task<String> {
            public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("HELLO_TASK", String.class);

            @Override
            public TaskDef<String> getDef() { return HELLO; }
        }
        """.trimIndent(),
    )

    private fun leavesOfConfiguredFile(): List<PsiElement> {
        val leaves = mutableListOf<PsiElement>()
        myFixture.file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element.firstChild == null) leaves += element
                    super.visitElement(element)
                }
            },
        )
        return leaves
    }

    private fun openedFile() = FileEditorManager.getInstance(project).selectedEditor?.file

    /** [length] characters from the caret: where the jump landed, spelled out. */
    private fun textAtCaret(length: Int): String {
        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) { "nothing opened" }
        val offset = editor.caretModel.offset
        return editor.document.getText(TextRange(offset, offset + length))
    }
}
