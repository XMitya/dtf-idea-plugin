package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.xmitya.ideadtf.search.DtfTaskSearcher

/** What the tool window lists: every concrete task in the project, and nothing else. */
class DtfTaskSearcherTest : DtfFixtureTestCase() {

    fun testJavaTaskIsFound() {
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
        assertEquals(listOf("HelloTask"), names())
    }

    fun testKotlinTaskIsFound() {
        myFixture.addFileToProject(
            "ScanFileTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class ScanFileTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("SCAN_FILE", String::class.java)
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ScanFileTask"), names())
    }

    /**
     * The shape most real tasks arrive in: `Task` is reached through a project-local base, so a
     * search that only looked at direct implementors would find nothing at all.
     */
    fun testTaskInheritingThroughAbstractBaseIsFoundAndTheBaseIsNot() {
        myFixture.addFileToProject(
            "BaseTask.java",
            """
            import com.distributed_task_framework.task.Task;

            public abstract class BaseTask<T> implements Task<T> {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "ChildTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class ChildTask extends BaseTask<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("CHILD", String.class); }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ChildTask"), names())
    }

    fun testInterfacesAndPlainClassesAreNotListed() {
        myFixture.addFileToProject(
            "Schedulable.java",
            """
            import com.distributed_task_framework.task.Task;

            public interface Schedulable<T> extends Task<T> {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "NotATask.java",
            """
            public class NotATask {
                public String getDef() { return "nope"; }
            }
            """.trimIndent(),
        )
        assertEmpty(names())
    }

    /** One Kotlin file routinely holds several tasks; each is its own row. */
    fun testTwoTasksInOneKotlinFileAreBothFound() {
        myFixture.addFileToProject(
            "Workers.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class SpecialistTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("SPECIALIST", String::class.java)
            }

            class JudgeTask : Task<String> {
                override fun getDef(): TaskDef<String> = TaskDef.privateTaskDef("JUDGE", String::class.java)
            }
            """.trimIndent(),
        )
        assertEquals(listOf("JudgeTask", "SpecialistTask"), names().sorted())
    }

    /** A class reachable through two supertype paths must still be listed once. */
    fun testTaskReachableThroughTwoPathsIsListedOnce() {
        myFixture.addFileToProject(
            "Schedulable.java",
            """
            import com.distributed_task_framework.task.Task;

            public interface Schedulable<T> extends Task<T> {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "BothTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class BothTask implements Task<String>, Schedulable<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("BOTH", String.class); }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("BothTask"), names())
    }

    fun testProjectWithoutTasksYieldsNothing() {
        assertEmpty(names())
    }

    private fun names(): List<String> =
        ReadAction.compute<List<String>, RuntimeException> {
            DtfTaskSearcher(project).findAllTasks().map { it.name.orEmpty() }
        }
}
