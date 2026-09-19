# DTF support for IntelliJ IDEA

Editor support for the [Distributed Task Framework](https://github.com/cherkovskiyandrey/distributed-task-framework)
(DTF).

Marks every class that implements `com.distributed_task_framework.task.Task` with a gutter icon, and
lets you jump from the task to every place it is scheduled. Works for Java and Kotlin sources, and
needs only IntelliJ IDEA Community — no Ultimate features are used.

## What it does

A DTF task is launched through `distributedTaskService.schedule(SOME_TASK_DEF, ctx)`, where the
`TaskDef` constant often lives in a different class from the task itself. Finding "who schedules
this task" therefore means locating the constant first and running Find Usages on it.

This plugin puts a **T** icon next to the task class. Clicking it searches the project and lists
every scheduling site, with the enclosing method, its class and `file:line`; picking one navigates
straight to that call. A single result navigates immediately.

The search runs on click, under a cancellable progress dialog, never during highlighting.

## What it recognises

**Tasks** — any class whose supertype closure contains `Task`. A literal `implements Task<T>` covers
fewer than half of real cases; the rest arrive through project-local base classes and interfaces,
several levels deep. Abstract classes and interfaces are not marked.

**Task definitions** — a constant in the task class, in a separate holder class, in an interface, in
a Kotlin `object`, or in a `companion object`; with a block body or an expression body, with or
without a declared return type. Both `privateTaskDef` and `publicTaskDef`. Constant *names* are
never used as a signal, only the declared `TaskDef` type.

**Scheduling sites**

| Shape | Example |
| --- | --- |
| Direct constant | `schedule(ScanFileTask.SCAN_FILE, ctx)` |
| Static / companion import | `schedule(SCAN_FILE, ctx)` |
| Self-reschedule | `schedule(getDef(), ctx)`, or `schedule(def, ctx)` in Kotlin — including when it is written in an abstract base |
| Local variable, incl. conditionals | `val d = if (x) A.DEF else B.DEF; schedule(d, ctx)` — reported for *both* tasks |
| Project-local wrapper | `sneakyScheduler.schedule(DEF, ctx)`, recognised by the parameter type, not by name |
| Private helper | `schedule(taskDef, msg)` forwarding to the framework |
| Task that schedules itself | `sendVkNotificationTask.schedule(dto, key)`, where the base fills in the `TaskDef` |
| Kotlin named arguments | `schedule(context = ctx, taskDef = DEF)` |

Methods that take a `TaskDef` but do not launch anything — `cancelAllTaskByTaskDef`,
`rescheduleByTaskDef`, `getRegisteredTask` — are deliberately not reported.

## Known limitations

- **Dispatch that is not statically decidable** is not resolved: bean-selector registries
  (`selector.getTask(type).def`), `getRegisteredTask(name)` string lookups, and `TaskDef`s rebuilt
  at runtime from a name carried inside a message payload.
- **A `TaskDef` passed into an abstract base as a constructor argument** yields no anchor. The icon
  still appears; the list is empty.
- **A receiver typed as a shared base** (`anySchedulableTask.schedule(...)`) is not attributed to
  any single task, because it could be any of them.
- **Dataflow is two hops deep** by design — one through a local variable, one through a wrapper's
  parameter.
- **Cron tasks** have no explicit call site. Where the plugin can tell (`@TaskSchedule`), it says so
  instead of reporting nothing found; cron configured in YAML is not read.
- **Saga steps** (`@SagaMethod`) are a separate mechanism with no `TaskDef` in user code, and are
  not covered.

## Building

Requires JDK 21.

```sh
./gradlew buildPlugin     # -> build/distributions/idea-dtf-plugin-<version>.zip
./gradlew test            # fixture tests
./gradlew verifyPlugin    # IntelliJ Plugin Verifier
./gradlew runIde          # sandbox IDE with the plugin installed
```

Install the zip through *Settings | Plugins | ⚙ | Install Plugin from Disk…*. The plugin is built
against 2025.2 (build 252) and has no upper bound, so it also loads in newer IDEs.

### Behind a proxy

Nothing proxy-related is committed. Pass the usual JVM properties and the build — including the
verifier, which runs in a forked JVM and gets them forwarded — will use them:

```sh
./gradlew -Dhttps.proxyHost=... -Dhttps.proxyPort=... -Dhttp.proxyHost=... -Dhttp.proxyPort=... build
```

The fixture tests additionally need `org.jetbrains:annotations:24.0.0` and
`org.jetbrains.mockjdk:mockjdk-base-java:21.0` in the local Maven repository. The IDE test framework
downloads them itself given plain internet access; behind a proxy it cannot, and they have to be
placed in `~/.m2/repository` by hand.

## Notes on the implementation

`TargetPresentation`, used to render the popup entries, is marked experimental by the platform. It
is the API the IDE's own "go to target" popups use, and the verifier reports no compatibility
problems; the alternative was an internal API.
