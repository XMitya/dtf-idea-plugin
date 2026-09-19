package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.xmitya.ideadtf.search.ScheduleCallSite
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduleTier

/** Finding the places a task is scheduled, across the call-site shapes that occur in real code. */
class ScheduleCallSearcherTest : DtfFixtureTestCase() {

    fun testQualifiedConstantReference() {
        addJavaTaskWithOwnConstant()
        myFixture.addFileToProject(
            "Caller.java",
            """
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
        val sites = search("HelloTask")
        assertEquals(1, sites.size)
        assertEquals(ScheduleTier.SERVICE, sites.single().tier)
        assertTrue(sites.single().element.text.contains("schedule(HelloTask.HELLO"))
    }

    /** With a static import the reference is a bare identifier; only resolution can find it. */
    fun testStaticallyImportedConstantFromHolder() {
        myFixture.addFileToProject(
            "TaskDefinitions.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public final class TaskDefinitions {
                public static final TaskDef<String> S3_MOVE_TASK_DEF = TaskDef.privateTaskDef("S3_MOVE", String.class);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "S3MoveTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class S3MoveTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDefinitions.S3_MOVE_TASK_DEF; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "S3Caller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            import static TaskDefinitions.S3_MOVE_TASK_DEF;

            public class S3Caller {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.schedule(S3_MOVE_TASK_DEF, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, search("S3MoveTask").size)
    }

    fun testScheduleJoinAndScheduleImmediatelyCount() {
        addJavaTaskWithOwnConstant()
        myFixture.addFileToProject(
            "ManyCalls.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;
            import java.util.List;

            public class ManyCalls {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.scheduleImmediately(HelloTask.HELLO, ExecutionContext.simple("x"));
                    distributedTaskService.scheduleJoin(HelloTask.HELLO, ExecutionContext.simple("x"), List.of());
                    distributedTaskService.scheduleFork(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(3, search("HelloTask").size)
    }

    /** cancelAllTaskByTaskDef and friends take a TaskDef but do not launch anything. */
    fun testNonSchedulingServiceMethodsAreIgnored() {
        addJavaTaskWithOwnConstant()
        myFixture.addFileToProject(
            "Canceller.java",
            """
            import com.distributed_task_framework.service.DistributedTaskService;
            import java.time.Duration;

            public class Canceller {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.cancelAllTaskByTaskDef(HelloTask.HELLO);
                    distributedTaskService.rescheduleByTaskDef(HelloTask.HELLO, Duration.ZERO);
                }
            }
            """.trimIndent(),
        )
        assertEmpty(search("HelloTask"))
    }

    /** A project-local facade that forwards the TaskDef to the framework. */
    fun testWrapperServiceIsFound() {
        addJavaTaskWithOwnConstant()
        myFixture.addFileToProject(
            "SneakyScheduler.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class SneakyScheduler {
                private DistributedTaskService distributedTaskService;

                public <T> void schedule(TaskDef<T> taskDef, ExecutionContext<T> ctx) throws Exception {
                    distributedTaskService.schedule(taskDef, ctx);
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "WrapperCaller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;

            public class WrapperCaller {
                private SneakyScheduler sneakyScheduler;

                public void run() throws Exception {
                    sneakyScheduler.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        val sites = search("HelloTask")
        assertEquals(1, sites.size)
        assertEquals(ScheduleTier.WRAPPER, sites.single().tier)
    }

    /** A conditional local variable: one call site, and it belongs to both tasks. */
    fun testConditionalLocalVariableInKotlin() {
        myFixture.addFileToProject(
            "Tasks.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class InitialPublishTask : Task<String> {
                override fun getDef(): TaskDef<String> = TASK_DEF
                companion object {
                    val TASK_DEF: TaskDef<String> = TaskDef.privateTaskDef("INITIAL", String::class.java)
                }
            }

            class DownloadTask : Task<String> {
                override fun getDef(): TaskDef<String> = TASK_DEF
                companion object {
                    val TASK_DEF: TaskDef<String> = TaskDef.privateTaskDef("DOWNLOAD", String::class.java)
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Scheduler.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.service.DistributedTaskService

            class Scheduler(private val distributedTaskService: DistributedTaskService) {
                fun run(initial: Boolean) {
                    val taskDef = if (initial) {
                        InitialPublishTask.TASK_DEF
                    } else {
                        DownloadTask.TASK_DEF
                    }
                    distributedTaskService.schedule(taskDef, ExecutionContext.simple("x"))
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, search("InitialPublishTask").size)
        assertEquals(1, search("DownloadTask").size)
    }

    /** Self-reschedule written inside the task itself. */
    fun testSelfRescheduleViaGetDef() {
        myFixture.addFileToProject(
            "SelfTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public class SelfTask implements Task<String> {
                public static final TaskDef<String> SELF = TaskDef.privateTaskDef("SELF", String.class);

                private DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return SELF; }

                @Override
                public void execute(ExecutionContext<String> ctx) throws Exception {
                    distributedTaskService.schedule(getDef(), ctx);
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, search("SelfTask").size)
    }

    /** The self-reschedule lives in the abstract base, and `def` resolves to Task.getDef there. */
    fun testSelfRescheduleDeclaredInAbstractBaseInKotlin() {
        myFixture.addFileToProject(
            "BaseSitemapGenerationTask.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.service.DistributedTaskService
            import com.distributed_task_framework.task.Task

            abstract class BaseSitemapGenerationTask(
                private val distributedTaskService: DistributedTaskService,
            ) : Task<String> {
                override fun execute(ctx: ExecutionContext<String>) {
                    distributedTaskService.schedule(def, ExecutionContext.simple("next"))
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "AppSitemapTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.service.DistributedTaskService

            class AppSitemapTask(
                distributedTaskService: DistributedTaskService,
            ) : BaseSitemapGenerationTask(distributedTaskService) {
                override fun getDef(): TaskDef<String> = APP_SITEMAP
                companion object {
                    val APP_SITEMAP: TaskDef<String> = TaskDef.privateTaskDef("APP_SITEMAP", String::class.java)
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, search("AppSitemapTask").size)
    }

    private fun addJavaTaskWithOwnConstant() {
        myFixture.addFileToProject(
            "HelloTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class HelloTask implements Task<String> {
                public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("HELLO_TASK", String.class);

                @Override
                public TaskDef<String> getDef() { return HELLO; }
            }
            """.trimIndent(),
        )
    }

    private fun search(taskClassName: String): List<ScheduleCallSite> =
        ReadAction.compute<List<ScheduleCallSite>, RuntimeException> {
            ScheduleCallSearcher(project).findScheduleSites(findClass(taskClassName))
        }
}
