package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.xmitya.ideadtf.search.NavigableScheduleSite
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleSitePresenter

/** What the popup ends up showing for each call site. */
class ScheduleSitePresentationTest : DtfFixtureTestCase() {

    fun testPresentationNamesTheEnclosingMethodAndLocation() {
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
        myFixture.addFileToProject(
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

        val site = present().single()
        assertEquals("createTask()", site.presentation.presentableText)
        assertEquals("app.Caller", site.presentation.containerText)
        assertEquals("Caller.java:10", site.presentation.locationText)
    }

    private fun present(): List<NavigableScheduleSite> = ReadAction.compute<List<NavigableScheduleSite>, RuntimeException> {
        val sites = ScheduleCallSearcher(project).findScheduleSites(findClass("app.HelloTask"))
        ScheduleSitePresenter(project).present(sites)
    }
}
