package com.xmitya.ideadtf

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.testFramework.DumbModeTestUtils
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel

/**
 * What a click on the gutter icon does.
 *
 * The line marker tests assert that the icon appears and that it carries a handler; this asserts
 * what the handler does with it. The interesting part is not the popup - shown only when there are
 * several answers - but everything before it: the search runs under a modal progress, off the
 * highlighting pass, and a single answer is opened straight away rather than shown as a list of one.
 */
class DtfGutterClickTest : DtfFixtureTestCase() {

    /** An ordinary task: the icon leads to the call that schedules it. */
    fun testClickingATaskWithOneCallSiteOpensThatCall() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        val caller = myFixture.addFileToProject(
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
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.TaskGutter)

        assertEquals(caller.virtualFile, openedFile())
        assertEquals("distributedTaskService.schedule", textAtCaret(31))
    }

    /** A cron task: the clock icon leads to where the schedule is configured instead. */
    fun testClickingACronTaskOpensTheConfiguration() {
        addTask("app/HelloTask.java", "HELLO_TASK", cron = true)
        val yaml = myFixture.addFileToProject(
            "app/src/main/resources/application.yaml",
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.CronGutter)

        assertEquals(yaml.virtualFile, openedFile())
        assertEquals("HELLO_TASK:", textAtCaret(11))
    }

    /** And the mirror image: from the schedule call back to the task it launches. */
    fun testClickingAScheduleCallOpensTheTask() {
        val task = addTask("app/HelloTask.java", "HELLO_TASK")
        myFixture.configureByText(
            "Caller.java",
            """
            import app.HelloTask;
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

        clickGutter(DtfIcons.ScheduleGutter)

        assertEquals(task.virtualFile, openedFile())
        assertEquals("HelloTask", textAtCaret(9))
    }

    /**
     * Nothing to jump to still has to say something.
     *
     * A task nobody schedules is the common way to land here, and the handler must fall through to
     * a message rather than open the first thing it can find - or open nothing at all, which reads
     * as a broken icon.
     */
    fun testClickingATaskNobodySchedulesOpensNothing() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.TaskGutter)

        assertEquals("nothing should have been opened", findFile("app/HelloTask.java"), openedFile())
    }

    /**
     * Several answers are offered rather than one of them picked.
     *
     * The popup is built from presentations rendered inside the progress, so the only thing that
     * can go wrong here goes wrong on the UI thread of a running IDE: nothing is opened, and the
     * list has to come up instead.
     */
    fun testATaskWithTwoCallSitesOffersBothRatherThanOpeningOne() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addCaller("app/First.java", "First")
        addCaller("app/Second.java", "Second")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.TaskGutter)

        assertEquals(findFile("app/HelloTask.java"), openedFile())
    }

    fun testACronTaskConfiguredTwiceOffersBothPlaces() {
        addTask("app/HelloTask.java", "HELLO_TASK", cron = true)
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        addYaml("app/src/test/resources/application-test.yaml", "")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.CronGutter)

        assertEquals(findFile("app/HelloTask.java"), openedFile())
    }

    /**
     * The feature this all exists for: an ordinary task opens its own configuration block.
     *
     * Nobody schedules this task from code, so before the configuration was searched the click had
     * nothing to offer at all - even though the project says plainly how the task is set up.
     */
    fun testClickingAPlainTaskWithOnlyConfigurationOpensIt() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        val yaml = addSettingsYaml("app/src/main/resources/application.yaml")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.TaskGutter)

        assertEquals(yaml.virtualFile, openedFile())
        assertEquals("HELLO_TASK:", textAtCaret(11))
    }

    /** Two kinds of answer in one list, so neither is picked for the reader. */
    fun testATaskWithACallSiteAndConfigurationOffersBoth() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addCaller("app/First.java", "First")
        addSettingsYaml("app/src/main/resources/application.yaml")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.TaskGutter)

        assertEquals(findFile("app/HelloTask.java"), openedFile())
    }

    /**
     * A cron task that is also scheduled explicitly keeps its call sites.
     *
     * The clock used to replace the T and with it the whole list of callers; one handler for both
     * icons is what gives them back, so the click has two answers and opens neither.
     */
    fun testACronTaskThatIsAlsoScheduledOffersItsCallSites() {
        addTask("app/HelloTask.java", "HELLO_TASK", cron = true)
        addCaller("app/First.java", "First")
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.CronGutter)

        assertEquals(findFile("app/HelloTask.java"), openedFile())
    }

    /**
     * A cron that exists only as the annotation is still an answer.
     *
     * There is nothing to open - the schedule is in the source the icon sits on - so the click has
     * to name the expression instead of reporting that it found nothing, which reads as the plugin
     * having lost the schedule it just drew a clock for.
     */
    fun testACronSetOnlyByTheAnnotationIsNamedRatherThanReportedMissing() {
        addTask("app/HelloTask.java", "HELLO_TASK", cron = true)
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        clickGutter(DtfIcons.CronGutter)

        assertEquals("nothing should have been opened", findFile("app/HelloTask.java"), openedFile())
    }

    /** A call whose task has no implementation anywhere: a message, not a silent click. */
    fun testClickingAScheduleCallWithNoTaskBehindItOpensNothing() {
        myFixture.addFileToProject(
            "app/Defs.java",
            """
            package app;

            import com.distributed_task_framework.model.TaskDef;

            public class Defs {
                public static final TaskDef<String> ORPHAN = TaskDef.privateTaskDef("ORPHAN", String.class);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Caller.java",
            """
            import app.Defs;
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Caller {
                private DistributedTaskService distributedTaskService;

                public void createTask() throws Exception {
                    distributedTaskService.schedule(Defs.ORPHAN, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        clickGutter(DtfIcons.ScheduleGutter)

        assertEquals("nothing should have been opened", configuredFile(), openedFile())
    }

    /** Two implementations of one definition: both offered, neither guessed at. */
    fun testAScheduleCallResolvingToTwoTasksOffersBoth() {
        myFixture.addFileToProject(
            "app/Defs.java",
            """
            package app;

            import com.distributed_task_framework.model.TaskDef;

            public class Defs {
                public static final TaskDef<String> SHARED = TaskDef.privateTaskDef("SHARED", String.class);
            }
            """.trimIndent(),
        )
        for (name in listOf("FirstTask", "SecondTask")) {
            myFixture.addFileToProject(
                "app/$name.java",
                """
                package app;

                import com.distributed_task_framework.model.TaskDef;
                import com.distributed_task_framework.task.Task;

                public class $name implements Task<String> {
                    @Override
                    public TaskDef<String> getDef() { return Defs.SHARED; }
                }
                """.trimIndent(),
            )
        }
        myFixture.configureByText(
            "Caller.java",
            """
            import app.Defs;
            import com.distributed_task_framework.model.ExecutionContext;
            import com.distributed_task_framework.service.DistributedTaskService;

            public class Caller {
                private DistributedTaskService distributedTaskService;

                public void createTask() throws Exception {
                    distributedTaskService.schedule(Defs.SHARED, ExecutionContext.simple("x"));
                }
            }
            """.trimIndent(),
        )

        clickGutter(DtfIcons.ScheduleGutter)

        assertEquals("neither task should have been picked", configuredFile(), openedFile())
    }

    /**
     * Clicking while the IDE is still indexing says so.
     *
     * Everything behind the icon searches indices, so without this check the click throws
     * `IndexNotReadyException` at whoever opened a project and went straight for the gutter.
     */
    fun testClickingWhileIndexingSaysSoRatherThanThrowing() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        addCaller("app/First.java", "First")
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))

        // Found before dumb mode is entered: locating it highlights the file, and highlighting
        // inside dumb mode waits for indexing that this very block is holding off.
        val marker = gutterFor(DtfIcons.TaskGutter)
        DumbModeTestUtils.runInDumbModeSynchronously(project) { click(marker) }

        assertEquals("indexing must not navigate", findFile("app/HelloTask.java"), openedFile())
    }

    /** The same, from the other icon - it reaches the indices through a different handler. */
    fun testClickingAScheduleCallWhileIndexingSaysSoRatherThanThrowing() {
        addTask("app/HelloTask.java", "HELLO_TASK")
        myFixture.configureByText(
            "Caller.java",
            """
            import app.HelloTask;
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

        val marker = gutterFor(DtfIcons.ScheduleGutter)
        DumbModeTestUtils.runInDumbModeSynchronously(project) { click(marker) }

        assertEquals("indexing must not navigate", configuredFile(), openedFile())
    }

    /** The icon that decides which of the two handlers a task class gets. */
    fun testACronTaskGetsTheClockRatherThanTheArrow() {
        addTask("app/HelloTask.java", "HELLO_TASK", cron = true)
        myFixture.configureFromExistingVirtualFile(findFile("app/HelloTask.java"))
        assertNotNull(markerFor(DtfIcons.CronGutter))
        assertNull(markerFor(DtfIcons.TaskGutter))
    }

    private fun clickGutter(icon: Icon) = click(gutterFor(icon))

    private fun click(marker: LineMarkerInfo<*>) {
        val handler = requireNotNull(marker.navigationHandler) { "gutter icon has no handler" }
        @Suppress("UNCHECKED_CAST")
        (handler as GutterIconNavigationHandler<PsiElement>).navigate(mouseEvent(), marker.element as PsiElement)
    }

    private fun gutterFor(icon: Icon): LineMarkerInfo<*> = requireNotNull(markerFor(icon)) { "no gutter icon" }

    private fun markerFor(icon: Icon): LineMarkerInfo<*>? = myFixture.findAllGutters()
        .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
        .singleOrNull { it.icon === icon }
        ?.lineMarkerInfo

    /** The handler only reads the event to place a popup, so any component-anchored click will do. */
    private fun mouseEvent() = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0L, 0, 0, 0, 1, false)

    private fun findFile(path: String) = requireNotNull(myFixture.findFileInTempDir(path)) { "no $path" }

    /** The file the click started from: what stays open when the handler opens nothing. */
    private fun configuredFile() = myFixture.file.virtualFile

    private fun openedFile() = FileEditorManager.getInstance(project).selectedEditor?.file

    private fun textAtCaret(length: Int): String {
        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) { "nothing opened" }
        val offset = editor.caretModel.offset
        return editor.document.getText(TextRange(offset, offset + length))
    }

    private fun addCaller(path: String, className: String) = myFixture.addFileToProject(
        path,
        """
        package app;

        import com.distributed_task_framework.model.ExecutionContext;
        import com.distributed_task_framework.service.DistributedTaskService;

        public class $className {
            private DistributedTaskService distributedTaskService;

            public void createTask() throws Exception {
                distributedTaskService.schedule(HelloTask.HELLO, ExecutionContext.simple("x"));
            }
        }
        """.trimIndent(),
    )

    private fun addYaml(path: String, cron: String) = myFixture.addFileToProject(
        path,
        """
        distributed-task:
          task-properties-group:
            task-properties:
              HELLO_TASK:
                cron: $cron
        """.trimIndent(),
    )

    private fun addSettingsYaml(path: String) = myFixture.addFileToProject(
        path,
        """
        distributed-task:
          task-properties-group:
            task-properties:
              HELLO_TASK:
                max-parallel-in-cluster: 1
        """.trimIndent(),
    )

    private fun addTask(path: String, taskName: String, cron: Boolean = false) = myFixture.addFileToProject(
        path,
        """
        package app;

        import com.distributed_task_framework.model.TaskDef;
        import com.distributed_task_framework.task.Task;
        ${if (cron) "import com.distributed_task_framework.autoconfigure.annotation.TaskSchedule;" else ""}

        ${if (cron) "@TaskSchedule(cron = \"0 0 1 * * *\")" else ""}
        public class HelloTask implements Task<String> {
            public static final TaskDef<String> HELLO = TaskDef.privateTaskDef("$taskName", String.class);

            @Override
            public TaskDef<String> getDef() { return HELLO; }
        }
        """.trimIndent(),
    )
}
