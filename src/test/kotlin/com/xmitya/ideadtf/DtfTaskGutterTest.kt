package com.xmitya.ideadtf

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo

/** The icon side of the feature: which classes get marked as DTF tasks. */
class DtfTaskGutterTest : DtfFixtureTestCase() {

    fun testTaskImplementingTaskDirectlyIsMarked() {
        myFixture.configureByText(
            "HelloTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.task.Task;

            public class HelloTask implements Task<String> {
                public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("HELLO", String.class);

                @Override
                public TaskDef<String> getDef() { return HELLO; }
            }
            """.trimIndent(),
        )
        assertEquals(1, taskGutters().size)
    }

    fun testTaskInheritingThroughAbstractBaseIsMarked() {
        myFixture.addFileToProject(
            "BaseLogOnFailureTask.java",
            """
            import com.distributed_task_framework.task.Task;

            public abstract class BaseLogOnFailureTask<T> implements Task<T> {}
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ChildTask.java",
            """
            import com.distributed_task_framework.model.TaskDef;

            public class ChildTask extends BaseLogOnFailureTask<String> {
                public static final TaskDef<String> CHILD = TaskDef.privateTaskDef("CHILD", String.class);

                @Override
                public TaskDef<String> getDef() { return CHILD; }
            }
            """.trimIndent(),
        )
        assertEquals(1, taskGutters().size)
    }

    fun testAbstractBaseIsNotMarked() {
        myFixture.configureByText(
            "AbstractTask.java",
            """
            import com.distributed_task_framework.task.Task;

            public abstract class AbstractTask<T> implements Task<T> {}
            """.trimIndent(),
        )
        assertEmpty(taskGutters())
    }

    fun testPlainClassIsNotMarked() {
        myFixture.configureByText(
            "NotATask.java",
            """
            public class NotATask {
                public String getDef() { return "nope"; }
            }
            """.trimIndent(),
        )
        assertEmpty(taskGutters())
    }

    /** Matched on the icon: the tooltip varies with whether the task name could be resolved. */
    /** The icon is only useful if it is wired to the navigation handler. */
    fun testMarkerIsClickableAndNamesTheTask() {
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
        val marker = myFixture.findAllGutters()
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .single { it.icon === DtfIcons.TaskGutter }
            .lineMarkerInfo
        assertNotNull(marker.navigationHandler)
        assertEquals(
            DtfBundle.message("dtf.gutter.tooltip.named", "HELLO_TASK"),
            marker.lineMarkerTooltip,
        )
    }

    private fun taskGutters(): List<GutterMark> =
        myFixture.findAllGutters().filter { it.icon === DtfIcons.TaskGutter }
}
