package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.search.ScheduleCallSearcher
import com.xmitya.ideadtf.search.ScheduledTaskSearcher

/** Finding the task behind a schedule call, across the shapes the first argument takes. */
class ScheduledTaskSearcherTest : DtfFixtureTestCase() {

    /** The constant lives in the task that returns it - the common case, and the one with no search. */
    fun testConstantDeclaredInTheTaskItself() {
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
        assertEquals(listOf("HelloTask"), tasksForSingleCall())
    }

    /** The constant lives in a holder, so only a search over its references can name the task. */
    fun testConstantDeclaredInASeparateHolder() {
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
        myFixture.configureByText(
            "S3Caller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class S3Caller {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.schedule(TaskDefinitions.S3_MOVE_TASK_DEF, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("S3MoveTask"), tasksForSingleCall())
    }

    /** A self-reschedule names no constant; the task is the class the call is written in. */
    fun testSelfRescheduleResolvesToTheEnclosingTask() {
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
        assertEquals(listOf("SelfTask"), tasksForSingleCall())
    }

    /** A ternary straight in the argument, with no local variable to hang the branches on. */
    fun testConditionalArgumentResolvesToBothTasks() {
        addTwoTasks()
        myFixture.configureByText(
            "Listener.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Listener {
                private DistributedTaskService distributedTaskService;

                public void onEvent(boolean flag) throws Exception {
                    distributedTaskService.schedule(
                        flag ? HelloTask.HELLO : OtherTask.OTHER,
                        ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("HelloTask", "OtherTask"), tasksForSingleCall())
    }

    /** A definition can be passed from local to local; two hops is the budget. */
    fun testChainedLocalVariables() {
        addTwoTasks()
        myFixture.configureByText(
            "Relay.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Relay {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    TaskDef<String> first = HelloTask.HELLO;
                    TaskDef<String> second = first;
                    distributedTaskService.schedule(second, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("HelloTask"), tasksForSingleCall())
    }

    /** One constant in a holder can be the identity of more than one task; list them all. */
    fun testSharedHolderConstantBelongsToEveryTaskThatReturnsIt() {
        myFixture.addFileToProject(
            "TaskDefinitions.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public final class TaskDefinitions {
                public static final TaskDef<String> SHARED = TaskDef.privateTaskDef("SHARED", String.class);
            }
            """.trimIndent(),
        )
        for (name in listOf("FirstTask", "SecondTask")) {
            myFixture.addFileToProject(
                "$name.java",
                """
                import com.distributed_task_framework.model.TaskDef;
                import com.distributed_task_framework.task.Task;

                public class $name implements Task<String> {
                    @Override
                    public TaskDef<String> getDef() { return TaskDefinitions.SHARED; }
                }
                """.trimIndent(),
            )
        }
        myFixture.configureByText(
            "SharedCaller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class SharedCaller {
                private DistributedTaskService distributedTaskService;

                public void run() throws Exception {
                    distributedTaskService.schedule(TaskDefinitions.SHARED, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("FirstTask", "SecondTask"), tasksForSingleCall())
    }

    private fun addTwoTasks() {
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
        myFixture.addFileToProject(
            "OtherTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class OtherTask implements Task<String> {
                public static final TaskDef<String> OTHER = TaskDef.privateTaskDef("OTHER", String.class);

                @Override
                public TaskDef<String> getDef() { return OTHER; }
            }
            """.trimIndent(),
        )
    }

    /** With a static import the argument is a bare identifier; only resolution can place it. */
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
        myFixture.configureByText(
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
        assertEquals(listOf("S3MoveTask"), tasksForSingleCall())
    }

    /** Every overload takes the definition first, so each resolves on its own. */
    fun testEveryScheduleOverloadResolves() {
        addTwoTasks()
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
                    distributedTaskService.scheduleFork(OtherTask.OTHER, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(listOf("HelloTask"), listOf("HelloTask"), listOf("OtherTask")),
            allCalls().map { it.first },
        )
    }

    /** A project-local facade, recognised by its parameter type rather than by its name. */
    fun testWrapperServiceCallResolves() {
        addTwoTasks()
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
        myFixture.configureByText(
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
        assertEquals(listOf("HelloTask"), tasksForSingleCall())
    }

    /** Named arguments put the definition second in the source, which source order would misread. */
    fun testKotlinNamedArguments() {
        myFixture.addFileToProject(
            "NamedTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class NamedTask : Task<String> {
                override fun getDef(): TaskDef<String> = TASK_DEF
                companion object {
                    val TASK_DEF: TaskDef<String> = TaskDef.privateTaskDef("NAMED", String::class.java)
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "KotlinScheduler.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.service.DistributedTaskService

            class KotlinScheduler(private val distributedTaskService: DistributedTaskService) {
                fun <T> schedule(taskDef: TaskDef<T>, context: ExecutionContext<T>) {
                    distributedTaskService.schedule(taskDef, context)
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Caller.kt",
            """
            import com.distributed_task_framework.model.ExecutionContext

            class Caller(private val scheduler: KotlinScheduler) {
                fun run() {
                    scheduler.schedule(context = ExecutionContext.simple("x"), taskDef = NamedTask.TASK_DEF)
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("NamedTask"), tasksForSingleCall())
    }

    /** One conditional local, and the call belongs to both tasks. */
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
        myFixture.configureByText(
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
        assertEquals(listOf("DownloadTask", "InitialPublishTask"), tasksForSingleCall())
    }

    /**
     * A ternary into a local handed to a private helper. The helper call resolves to both tasks;
     * the helper's own forward carries a parameter and is not a call site at all.
     */
    fun testJavaTernaryThroughPrivateHelper() {
        addTwoTasks()
        // Both the call into the helper and the helper's own forward are marked, and the second
        // one answers by asking the callers - so the two agree.
        myFixture.configureByText(
            "Listener.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Listener {
                private DistributedTaskService distributedTaskService;

                public void onEvent(boolean flag) throws Exception {
                    var taskDef = flag ? HelloTask.HELLO : OtherTask.OTHER;
                    schedule(taskDef, "payload");
                }

                void schedule(TaskDef<String> taskDef, String payload) throws Exception {
                    distributedTaskService.schedule(taskDef, ExecutionContext.simple(payload));
                }
            }
            """.trimIndent(),
        )
        val calls = allCalls()
        assertEquals("expected both calls to be marked, got " + calls.map { it.second }, 2, calls.size)
        for ((tasks, text) in calls) {
            assertEquals("wrong tasks for " + text, listOf("HelloTask", "OtherTask"), tasks)
        }
    }

    /** The self-reschedule is inherited, so every concrete task below the base is an answer. */
    fun testSelfRescheduleInAnAbstractKotlinBaseListsTheInheritors() {
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

            class GameSitemapTask(
                distributedTaskService: DistributedTaskService,
            ) : BaseSitemapGenerationTask(distributedTaskService) {
                override fun getDef(): TaskDef<String> = GAME_SITEMAP
                companion object {
                    val GAME_SITEMAP: TaskDef<String> = TaskDef.privateTaskDef("GAME_SITEMAP", String::class.java)
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
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
        assertEquals(listOf("AppSitemapTask", "GameSitemapTask"), tasksForSingleCall())
    }

    /**
     * A definition declared on a shared task *interface*: that type is a Task subtype without
     * being any one task, so the answer is the implementation that returns it.
     */
    fun testConstantOnATaskInterfaceResolvesToTheImplementor() {
        myFixture.addFileToProject(
            "AabProcTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public interface AabProcTask<T> extends Task<T> {
                TaskDef<String> UNIVERSAL_APK_TASK_DEF = TaskDef.privateTaskDef("UNIVERSAL_APK", String.class);
                TaskDef<String> VALIDATE_TASK_DEF = TaskDef.privateTaskDef("VALIDATE", String.class);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "UniversalApkGenTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class UniversalApkGenTask implements AabProcTask<String> {
                @Override
                public TaskDef<String> getDef() { return UNIVERSAL_APK_TASK_DEF; }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ValidateAabTask.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class ValidateAabTask implements AabProcTask<String> {
                private DistributedTaskService distributedTaskService;

                @Override
                public TaskDef<String> getDef() { return VALIDATE_TASK_DEF; }

                @Override
                public void execute(ExecutionContext<String> ctx) throws Exception {
                    distributedTaskService.schedule(UNIVERSAL_APK_TASK_DEF, ctx);
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("UniversalApkGenTask"), tasksForSingleCall())
    }

    /** A base that fills in the definition itself: the receiver is the only thing naming the task. */
    fun testSchedulableBeanCallResolvesToTheReceiverTask() {
        addSchedulableBase()
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
        assertEquals(listOf("SendVkNotificationTask"), tasksForSingleCall())
    }

    /**
     * A receiver typed as the shared base could be any task below it, so there is nothing to
     * navigate to and no icon is offered - the same non-attribution the forward direction makes.
     */
    fun testBaseTypedReceiverIsNotRecognised() {
        addSchedulableBase()
        myFixture.configureByText(
            "GenericConsumer.java",
            """
            public class GenericConsumer {
                private AffinitySchedulableTask<String> anyTask;

                public void consume() throws Exception {
                    anyTask.schedule("payload", "affinity");
                }
            }
            """.trimIndent(),
        )
        assertEmpty(allCalls())
    }

    private fun addSchedulableBase() {
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
    }

    /**
     * The two directions have to agree: every task this call resolves to must list this very call
     * among its own schedule sites. They resolve through different paths, so nothing but a test
     * stops them drifting apart.
     */
    fun testRoundTripAgreesWithTheForwardSearch() {
        addTwoTasks()
        myFixture.configureByText(
            "Listener.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Listener {
                private DistributedTaskService distributedTaskService;

                public void onEvent(boolean flag) throws Exception {
                    TaskDef<String> taskDef = flag ? HelloTask.HELLO : OtherTask.OTHER;
                    distributedTaskService.schedule(taskDef, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        assertRoundTrip("distributedTaskService.schedule(taskDef", listOf("HelloTask", "OtherTask"))
    }

    /**
     * The same agreement for the shape that used to fall through the floor: a conditional inside a
     * lambda, handed to a project wrapper.
     *
     * The call checked is the one the caller wrote, not the wrapper's own forward. That forward now
     * resolves backwards too, by asking the callers, but the forward search deliberately reports
     * the caller's line instead - one line per task rather than the wrapper's shared internals.
     */
    fun testRoundTripAgreesForAConditionalInsideALambda() {
        addTwoTasks()
        myFixture.configureByText(
            "NewAppFileEventListener.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.service.DistributedTaskService;
            import java.util.List;

            public class NewAppFileEventListener {
                private DistributedTaskService distributedTaskService;

                public void onReceive(List<String> events) {
                    events.forEach(event -> {
                        var taskDef = event.isEmpty() ? HelloTask.HELLO : OtherTask.OTHER;
                        try {
                            schedule(taskDef, event);
                        } catch (Exception e) {
                            // ignored
                        }
                    });
                }

                void schedule(TaskDef<String> taskDef, String payload) throws Exception {
                    distributedTaskService.schedule(taskDef, ExecutionContext.simple(payload));
                }
            }
            """.trimIndent(),
        )

        assertRoundTrip("schedule(taskDef, event)", listOf("HelloTask", "OtherTask"))
    }

    /**
     * Both directions agree about the marked call whose text contains [callText]: it resolves to
     * [expectedTasks], and each of those lists this very call among its own schedule sites.
     */
    private fun assertRoundTrip(callText: String, expectedTasks: List<String>) {
        ReadAction.run<RuntimeException> {
            val call = leavesOf(myFixture.file)
                .mapNotNull { DtfScheduleMarkers.scheduleCallAt(it) }
                .first { it.sourcePsi?.text?.contains(callText) == true }
            val callOffset = call.sourcePsi!!.textRange.startOffset
            val tasks = ScheduledTaskSearcher(project).findTasks(call)
            assertEquals(expectedTasks, tasks.map { it.qualifiedName })

            val forward = ScheduleCallSearcher(project)
            for (task in tasks) {
                val sites = forward.findScheduleSites(task)
                assertTrue(
                    "the forward search for " + task.name + " lost this call, found " + sites.map { it.element.text },
                    sites.any { it.element.textRange.startOffset == callOffset },
                )
            }
        }
    }

    /**
     * Inside a wrapper's own forward the definition is whatever each caller passed, so the answer
     * is the union over the callers - which is why the icon is worth showing there at all.
     */
    fun testWrapperParameterResolvesThroughCallers() {
        addTwoTasks()
        myFixture.addFileToProject(
            "WrapperCaller.java",
            """
            import com.distributed_task_framework.model.ExecutionContext;

            public class WrapperCaller {
                private SneakyScheduler sneakyScheduler;

                public void run() throws Exception {
                    sneakyScheduler.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
                    sneakyScheduler.schedule(OtherTask.OTHER, ExecutionContext.simple("y"));
                }
            }
            """.trimIndent(),
        )
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
        assertEquals(listOf("HelloTask", "OtherTask"), tasksForSingleCall())
    }

    /** A TaskDef parameter its own method never forwards leads nowhere, so nothing is reported. */
    fun testParameterOfANonForwardingMethodResolvesToNothing() {
        addTwoTasks()
        myFixture.addFileToProject(
            "AuditCaller.java",
            """
            public class AuditCaller {
                private DefLogger defLogger;

                public void run() {
                    defLogger.scheduleAudit(HelloTask.HELLO, "x");
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "DefLogger.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class DefLogger {
                public void scheduleAudit(TaskDef<String> taskDef, String payload) {
                    System.out.println(taskDef.getTaskName() + payload);
                }
            }
            """.trimIndent(),
        )
        assertEmpty(allCalls())
    }

    /** The tasks resolved for the only schedule call in the file under the caret. */
    private fun tasksForSingleCall(): List<String> {
        val calls = allCalls()
        assertEquals("expected exactly one schedule call, got " + calls.map { it.second }, 1, calls.size)
        return calls.single().first
    }

    /** Every schedule call in the file, each with the tasks it resolves to and its own text. */
    private fun allCalls(): List<Pair<List<String>, String>> = ReadAction.compute<List<Pair<List<String>, String>>, RuntimeException> {
        val searcher = ScheduledTaskSearcher(project)
        leavesOf(myFixture.file)
            .mapNotNull { DtfScheduleMarkers.scheduleCallAt(it) }
            .map { call ->
                val tasks = searcher.findTasks(call).map { it.qualifiedName ?: it.name.orEmpty() }
                tasks to (call.sourcePsi?.text.orEmpty())
            }
    }

    private fun leavesOf(root: PsiElement): List<PsiElement> {
        val leaves = mutableListOf<PsiElement>()
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.firstChild == null) leaves += element
                super.visitElement(element)
            }
        })
        return leaves
    }
}
