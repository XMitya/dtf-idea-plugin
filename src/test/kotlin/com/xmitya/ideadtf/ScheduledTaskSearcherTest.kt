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

        ReadAction.run<RuntimeException> {
            val call = leavesOf(myFixture.file).firstNotNullOf { DtfScheduleMarkers.scheduleCallAt(it) }
            val callOffset = call.sourcePsi!!.textRange.startOffset
            val tasks = ScheduledTaskSearcher(project).findTasks(call)
            assertEquals(listOf("HelloTask", "OtherTask"), tasks.map { it.qualifiedName })

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

    /** The tasks resolved for the only schedule call in the file under the caret. */
    private fun tasksForSingleCall(): List<String> {
        val calls = allCalls()
        assertEquals("expected exactly one schedule call, got " + calls.map { it.second }, 1, calls.size)
        return calls.single().first
    }

    /** Every schedule call in the file, each with the tasks it resolves to and its own text. */
    private fun allCalls(): List<Pair<List<String>, String>> =
        ReadAction.compute<List<Pair<List<String>, String>>, RuntimeException> {
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
