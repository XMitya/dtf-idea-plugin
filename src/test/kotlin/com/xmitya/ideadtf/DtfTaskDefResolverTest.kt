package com.xmitya.ideadtf

import com.xmitya.ideadtf.model.DtfTaskDefResolver

/** Getting from a task class to the `TaskDef` that identifies it, across the declaration shapes. */
class DtfTaskDefResolverTest : DtfFixtureTestCase() {

    fun testJavaConstantInTaskClass() {
        myFixture.configureByText(
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
        val def = DtfTaskDefResolver.resolve(findClass("HelloTask"))
        assertEquals("HELLO_TASK", def.taskName)
        assertTrue(def.isResolved)
    }

    fun testJavaConstantInHolderClass() {
        myFixture.addFileToProject(
            "TaskDefinitions.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public final class TaskDefinitions {
                public static final TaskDef<String> S3_MOVE_TASK_DEF = TaskDef.privateTaskDef("S3_MOVE", String.class);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
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
        val def = DtfTaskDefResolver.resolve(findClass("S3MoveTask"))
        assertEquals("S3_MOVE", def.taskName)
        assertTrue(def.isResolved)
    }

    /** Fields in an interface are implicitly public static final, with no modifiers written. */
    fun testJavaConstantInInterface() {
        myFixture.addFileToProject(
            "PrivateTaskDefinitions.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public interface PrivateTaskDefinitions {
                TaskDef<String> PARENT_TASK = TaskDef.privateTaskDef("PARENT_TASK", String.class);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ParentTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class ParentTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return PrivateTaskDefinitions.PARENT_TASK; }
            }
            """.trimIndent(),
        )
        val def = DtfTaskDefResolver.resolve(findClass("ParentTask"))
        assertEquals("PARENT_TASK", def.taskName)
        assertTrue(def.isResolved)
    }

    /** No constant to search for, but the name is still worth reporting. */
    fun testJavaInlineDefinition() {
        myFixture.configureByText(
            "InlineTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class InlineTask implements Task<String> {
                @Override
                public TaskDef<String> getDef() { return TaskDef.privateTaskDef("INLINE", String.class); }
            }
            """.trimIndent(),
        )
        val def = DtfTaskDefResolver.resolve(findClass("InlineTask"))
        assertEquals("INLINE", def.taskName)
        assertFalse(def.isResolved)
    }

    /** publicTaskDef takes the application name first, so the task name is argument 1. */
    fun testJavaPublicTaskDefNameIsSecondArgument() {
        myFixture.configureByText(
            "RemoteTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class RemoteTask implements Task<String> {
                public static final TaskDef<String> REMOTE =
                    TaskDef.publicTaskDef("other-app", "REMOTE_TASK", String.class);

                @Override
                public TaskDef<String> getDef() { return REMOTE; }
            }
            """.trimIndent(),
        )
        val def = DtfTaskDefResolver.resolve(findClass("RemoteTask"))
        assertEquals("REMOTE_TASK", def.taskName)
    }

    fun testKotlinCompanionObjectConstant() {
        myFixture.configureByText(
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
        val def = DtfTaskDefResolver.resolve(findClass("ScanFileTask"))
        assertEquals("SCAN_FILE", def.taskName)
        assertTrue(def.isResolved)
    }

    /** Expression body with no declared return type - the most common Kotlin shape after the above. */
    fun testKotlinExpressionBodyWithoutDeclaredType() {
        myFixture.configureByText(
            "VersionAutoModerationTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class VersionAutoModerationTask : Task<String> {

                override fun getDef() = VERSION_AUTO_MODERATION

                companion object {
                    val VERSION_AUTO_MODERATION: TaskDef<String> =
                        TaskDef.privateTaskDef("VERSION_AUTO_MODERATION", String::class.java)
                }
            }
            """.trimIndent(),
        )
        val def = DtfTaskDefResolver.resolve(findClass("VersionAutoModerationTask"))
        assertEquals("VERSION_AUTO_MODERATION", def.taskName)
        assertTrue(def.isResolved)
    }

    fun testKotlinTopLevelObjectHolder() {
        myFixture.addFileToProject(
            "LlmModerationTaskDefinitions.kt",
            """
            import com.distributed_task_framework.model.TaskDef

            object LlmModerationTaskDefinitions {
                val LLM_MODERATION_JUDGE: TaskDef<String> =
                    TaskDef.privateTaskDef("LLM_MODERATION_JUDGE", String::class.java)
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "LlmModerationWorkerTask.kt",
            """
            import com.distributed_task_framework.model.TaskDef
            import com.distributed_task_framework.task.Task

            class LlmModerationWorkerTask : Task<String> {
                override fun getDef(): TaskDef<String> = LlmModerationTaskDefinitions.LLM_MODERATION_JUDGE
            }
            """.trimIndent(),
        )
        val def = DtfTaskDefResolver.resolve(findClass("LlmModerationWorkerTask"))
        assertEquals("LLM_MODERATION_JUDGE", def.taskName)
        assertTrue(def.isResolved)
    }
}
