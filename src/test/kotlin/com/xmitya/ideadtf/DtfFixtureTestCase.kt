package com.xmitya.ideadtf

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Base for the plugin's fixture tests.
 *
 * Adds a hand-written stub of the slice of the DTF API the plugin keys off, so that tests never
 * need the real framework jar. The shapes mirror the real ones: `getDef()` is the only abstract
 * method of `Task`, and the scheduling methods live on `TaskCommandService`, which
 * `DistributedTaskService` merely inherits.
 */
abstract class DtfFixtureTestCase : LightJavaCodeInsightFixtureTestCase() {

    /** Pinned so the language level does not drift with the SDK the tests are built against. */
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        addDtfApiStubs()
    }

    private fun addDtfApiStubs() {
        myFixture.addFileToProject(
            "com/distributed_task_framework/model/TaskDef.java",
            """
            package com.distributed_task_framework.model;

            public final class TaskDef<T> {
                private TaskDef() {}
                public static <T> TaskDef<T> privateTaskDef(String taskName, Class<T> inputMessageClass) { return null; }
                public static TaskDef<Void> privateTaskDef(String taskName) { return null; }
                public static <T> TaskDef<T> publicTaskDef(String appName, String taskName, Class<? extends T> cls) { return null; }
                public String getTaskName() { return null; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/distributed_task_framework/model/ExecutionContext.java",
            """
            package com.distributed_task_framework.model;

            public class ExecutionContext<T> {
                public static <T> ExecutionContext<T> simple(T inputMessage) { return null; }
                public static <T> ExecutionContext<T> empty() { return null; }
                public <U> ExecutionContext<U> withNewMessage(U inputMessage) { return null; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/distributed_task_framework/model/TaskId.java",
            """
            package com.distributed_task_framework.model;

            public class TaskId {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/distributed_task_framework/task/Task.java",
            """
            package com.distributed_task_framework.task;

            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;

            public interface Task<T> {
                TaskDef<T> getDef();
                default void execute(ExecutionContext<T> executionContext) throws Exception {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/distributed_task_framework/service/TaskCommandService.java",
            """
            package com.distributed_task_framework.service;

            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.model.TaskDef;
            import com.distributed_task_framework.model.TaskId;
            import java.time.Duration;
            import java.util.List;

            public interface TaskCommandService {
                <T> TaskId schedule(TaskDef<T> taskDef, ExecutionContext<T> ctx) throws Exception;
                <T> TaskId schedule(TaskDef<T> taskDef, ExecutionContext<T> ctx, Duration delay) throws Exception;
                <T> TaskId scheduleFork(TaskDef<T> taskDef, ExecutionContext<T> ctx) throws Exception;
                <T> TaskId scheduleImmediately(TaskDef<T> taskDef, ExecutionContext<T> ctx) throws Exception;
                <T> TaskId scheduleJoin(TaskDef<T> taskDef, ExecutionContext<T> ctx, List<TaskId> joinList) throws Exception;
                <T> boolean cancelAllTaskByTaskDef(TaskDef<T> taskDef);
                <T> void rescheduleByTaskDef(TaskDef<T> taskDef, Duration delay) throws Exception;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/distributed_task_framework/service/DistributedTaskService.java",
            """
            package com.distributed_task_framework.service;

            public interface DistributedTaskService extends TaskCommandService {
            }
            """.trimIndent(),
        )
    }
}
