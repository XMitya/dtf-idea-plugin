package com.xmitya.ideadtf

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo

/** The icon side of the reverse feature: which call sites get marked as scheduling a task. */
class DtfScheduleGutterTest : DtfFixtureTestCase() {

    fun testServiceScheduleCallIsMarked() {
        addJavaTask()
        myFixture.configureByText(
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
        assertEquals(1, scheduleGutters().size)
    }

    fun testKotlinScheduleCallIsMarked() {
        addKotlinTask()
        myFixture.configureByText(
            "Scheduler.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.service.DistributedTaskService

            class Scheduler(private val distributedTaskService: DistributedTaskService) {
                fun run() {
                    distributedTaskService.schedule(ScanFileTask.SCAN_FILE, ExecutionContext.simple("x"))
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, scheduleGutters().size)
    }

    /** Every scheduling overload takes the TaskDef first, so all of them are call sites. */
    fun testEveryScheduleOverloadIsMarked() {
        addJavaTask()
        myFixture.configureByText(
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
        assertEquals(3, scheduleGutters().size)
    }

    /** These take a TaskDef but launch nothing; the name gate alone keeps them out. */
    fun testNonSchedulingServiceMethodsAreNotMarked() {
        addJavaTask()
        myFixture.configureByText(
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
        assertEmpty(scheduleGutters())
    }

    /**
     * Inside a wrapper's own forward the TaskDef is whatever the caller passed, so there is no task
     * to name here and the icon would lead nowhere.
     */
    fun testWrapperForwardOfItsOwnParameterIsNotMarked() {
        myFixture.configureByText(
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
        assertEmpty(scheduleGutters())
    }

    /**
     * A project-local facade is recognised by its parameter type, and the receiver's own name
     * starts with "schedule" too - which is exactly the case that produces duplicate icons when the
     * marker does not check that the leaf really is the method name.
     */
    fun testWrapperCallIsMarkedExactlyOnce() {
        addJavaTask()
        myFixture.addFileToProject(
            "SneakyScheduler.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public class SneakyScheduler {
                public <T> void schedule(TaskDef<T> taskDef, ExecutionContext<T> ctx) throws Exception {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "WrapperCaller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;

            public class WrapperCaller {
                private SneakyScheduler scheduler;

                public void run() throws Exception {
                    scheduler.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, scheduleGutters().size)
    }

    /** Spring's TaskScheduler and friends share the name and nothing else. */
    fun testUnrelatedScheduleMethodIsNotMarked() {
        myFixture.addFileToProject(
            "TaskScheduler.java",
            """
            public interface TaskScheduler {
                void schedule(Runnable task, String trigger);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Cron.java",
            """
            public class Cron {
                private TaskScheduler taskScheduler;

                public void run() {
                    taskScheduler.schedule(() -> {}, "0 0 * * *");
                }
            }
            """.trimIndent(),
        )
        assertEmpty(scheduleGutters())
    }

    /** A base that fills in the TaskDef itself: no TaskDef at the call, but the receiver names it. */
    fun testSchedulableTaskBeanCallIsMarked() {
        myFixture.addFileToProject(
            "AffinitySchedulableTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskId;
            import com.distributed_task_framework.service.DistributedTaskService;
            import com.distributed_task_framework.task.Task;

            public abstract class AffinitySchedulableTask<T> implements Task<T> {
                private DistributedTaskService distributedTaskService;

                public TaskId schedule(T message, String affinity) throws Exception {
                    return distributedTaskService.schedule(getDef(), ExecutionContext.simple(message));
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "SendVkNotificationTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class SendVkNotificationTask extends AffinitySchedulableTask<String> {
                public static final TaskDef<String> TASK_DEF = TaskDef.privateTaskDef("SEND", String.class);

                @Override
                public TaskDef<String> getDef() { return TASK_DEF; }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Consumer.java",
            """
            public class Consumer {
                private SendVkNotificationTask sendVkNotificationTask;

                public void consume() throws Exception {
                    sendVkNotificationTask.schedule("payload", "affinity");
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, scheduleGutters().size)
    }

    /** A self-reschedule names its task through getDef() rather than through a constant. */
    fun testSelfRescheduleIsMarked() {
        myFixture.configureByText(
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
        assertEquals(1, scheduleGutters().size)
    }

    /**
     * A constructor is a call too, and a type named for scheduling that takes a TaskDef first is
     * indistinguishable from a wrapper on everything except the kind of call it is.
     */
    fun testConstructorNamedLikeASchedulerIsNotMarked() {
        addJavaTask()
        myFixture.addFileToProject(
            "ScheduleRequest.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public class ScheduleRequest<T> {
                public ScheduleRequest(TaskDef<T> taskDef, ExecutionContext<T> ctx) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Builder.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;

            public class Builder {
                public Object build() {
                    return new ScheduleRequest<>(HelloTask.HELLO, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEmpty(scheduleGutters())
    }

    /** The icon is only useful if it is wired to the navigation handler. */
    fun testMarkerIsClickableAndAnchorsOnTheMethodName() {
        addJavaTask()
        myFixture.configureByText(
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
        val marker = myFixture.findAllGutters()
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .single { it.icon === DtfIcons.ScheduleGutter }
            .lineMarkerInfo
        assertNotNull(marker.navigationHandler)
        assertEquals(DtfBundle.message("dtf.gutter.schedule.tooltip"), marker.lineMarkerTooltip)
        assertEquals("schedule", marker.element?.text)
    }

    /** A task that schedules itself carries both icons, and they must not be confused. */
    fun testTaskClassAndScheduleCallGetDistinctIcons() {
        myFixture.configureByText(
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
        assertEquals(1, myFixture.findAllGutters().filter { it.icon === DtfIcons.TaskGutter }.size)
        assertEquals(1, scheduleGutters().size)
    }

    private fun addJavaTask() {
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

    private fun addKotlinTask() {
        myFixture.addFileToProject(
            "ScanFileTask.kt",
            """
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
    }

    private fun scheduleGutters(): List<GutterMark> =
        myFixture.findAllGutters().filter { it.icon === DtfIcons.ScheduleGutter }
}
