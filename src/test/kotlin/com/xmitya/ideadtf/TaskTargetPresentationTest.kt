package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.search.NavigableTaskTarget
import com.xmitya.ideadtf.search.ScheduledTaskSearcher
import com.xmitya.ideadtf.search.TaskTargetPresenter

/** What the popup ends up showing for each resolved task. */
class TaskTargetPresentationTest : DtfFixtureTestCase() {

    fun testPresentationNamesTheClassTheTaskAndTheLocation() {
        myFixture.addFileToProject(
            "app/HelloTask.java",
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
        myFixture.configureByText(
            "Caller.java",
            """
            import app.HelloTask;
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Caller {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        val target = present().single()
        assertEquals("HelloTask", target.presentation.presentableText)
        assertEquals(DtfBundle.message("dtf.target.named", "app.HelloTask", "HELLO_TASK"), target.presentation.containerText)
        assertEquals("HelloTask.java:6", target.presentation.locationText)
    }

    /** A Kotlin class arrives as a light class, so the location has to come from the source. */
    fun testKotlinTaskIsLocatedInItsSource() {
        myFixture.addFileToProject(
            "app/ScanFileTask.kt",
            """
            package app

            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class ScanFileTask : Task<String> {
                override fun getDef(): TaskDef<String> = SCAN_FILE

                companion object {
                    val SCAN_FILE: TaskDef<String> = TaskDef.privateTaskDef("SCAN_FILE", String::class.java)
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Scheduler.kt",
            """
            import app.ScanFileTask
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.service.DistributedTaskService

            class Scheduler(private val distributedTaskService: DistributedTaskService) {
                fun run() {
                    distributedTaskService.schedule(ScanFileTask.SCAN_FILE, ExecutionContext.simple("x"))
                }
            }
            """.trimIndent(),
        )

        val target = present().single()
        assertEquals("ScanFileTask", target.presentation.presentableText)
        assertEquals(DtfBundle.message("dtf.target.named", "app.ScanFileTask", "SCAN_FILE"), target.presentation.containerText)
        assertEquals("ScanFileTask.kt:6", target.presentation.locationText)
    }

    private fun present(): List<NavigableTaskTarget> = ReadAction.compute<List<NavigableTaskTarget>, RuntimeException> {
        val leaves = mutableListOf<PsiElement>()
        myFixture.file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.firstChild == null) leaves += element
                super.visitElement(element)
            }
        })
        val call = leaves.firstNotNullOf { DtfScheduleMarkers.scheduleCallAt(it) }
        TaskTargetPresenter(project).present(ScheduledTaskSearcher(project).findTasks(call))
    }
}
