# DTF support for IntelliJ IDEA

Editor support for the [Distributed Task Framework](https://github.com/cherkovskiyandrey/distributed-task-framework)
(DTF).

Marks every class that implements `com.distributed_task_framework.task.Task` with a gutter icon, and
lets you jump from the task to every place it is scheduled. Works for Java and Kotlin sources, and
needs only IntelliJ IDEA Community — no Ultimate features are used.

## Installing

**Get updates automatically.** Add this URL once under *Settings | Plugins | ⚙ | Manage Plugin
Repositories…*:

```
https://github.com/XMitya/dtf-idea-plugin/releases/latest/download/updatePlugins.xml
```

The plugin then appears under *Marketplace* in the plugin dialog, and every new release arrives
through the normal update flow. `releases/latest/download/` always resolves to the newest release,
so the URL itself never changes.

**Or install a single build.** Download the zip from
[Releases](https://github.com/XMitya/dtf-idea-plugin/releases) and use *Settings | Plugins | ⚙ |
Install Plugin from Disk…*.

Built against 2025.2 (build 252) with no upper bound, so it loads in newer IDEs as well.

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

# custom-repository manifest, pointing at the directory that serves the zip
./gradlew generateUpdatePluginsXml -PpluginBaseUrl=https://host/path
```

`pluginVersion` from `gradle.properties` names the build; `-PpluginVersion=<v>` overrides it, which
is how the release workflow keeps the zip in step with the tag.

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

## Releasing

`.github/workflows/build.yml` tests, builds and verifies every push to `main` and every pull
request.

A release is cut by pushing a tag. The tag carries the version, so what the release page shows and
what is inside the zip cannot disagree:

```sh
git tag v0.2.0
git push origin v0.2.0
```

`.github/workflows/release.yml` then builds at that version, generates the `updatePlugins.xml`
manifest pointing at that release's own zip, and attaches both to the GitHub release — which is
what makes the repository URL above serve updates. The same workflow can be started by hand from
the Actions tab with a version instead of a tag.

Publishing to the JetBrains Marketplace is wired up and skips itself until the `PUBLISH_TOKEN`,
`CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` repository secrets exist. The first
version of a plugin has to go through the Marketplace web form in any case; the API only accepts
updates to a plugin that already exists.

## Notes on the implementation

`TargetPresentation`, used to render the popup entries, is marked experimental by the platform. It
is the API the IDE's own "go to target" popups use, and the verifier reports no compatibility
problems; the alternative was an internal API.
