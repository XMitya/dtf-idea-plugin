# DTF support for IntelliJ IDEA

Editor support for the [Distributed Task Framework](https://github.com/cherkovskiyandrey/distributed-task-framework)
(DTF).

Navigates between a DTF task and the places it is scheduled, in both directions, from a gutter icon,
marks the tasks the framework launches on a schedule, and lists every task in the project in a tool
window of its own. Works for Java and Kotlin sources, and needs only IntelliJ IDEA Community — no
Ultimate features are used.

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

The same detour exists in reverse. Reading `schedule(SOME_TASK_DEF, ctx)` and wanting the task that
actually runs means Go To Declaration on the constant, landing in a holder class, and Find Usages
from there. So the call gets an icon of its own — the same **T**, with a red arrow down — that
resolves the task and jumps to it. A definition shared by several tasks, or picked by a conditional,
opens a popup naming each task, its definition and `file:line`.

A cron task has no call site to find. It carries a yellow **clock** instead of the **T**, and the
click opens the place its schedule is configured — the `<TASK_NAME>:` line in `application.yaml`.
Where several profiles configure the same task, all of them are listed, each showing its own cron
expression, so the one that is actually switched off is visible rather than assumed.

Both searches run on click, under a cancellable progress dialog, never during highlighting.

Those three icons each answer a question about one task you are already looking at. The **DTF Tasks**
tool window answers the one they cannot — *which tasks are there at all* — as a tree of project,
module and task. Each row is named by its `TaskDef` and carries the icon it has in the gutter, with
the class name and, for a cron task, the expression beside it in grey; double-click or Enter opens
the task. Typing finds a task or a module by name, reaching into collapsed modules, so nothing has
to be opened first. The stripe button appears only in projects that have DTF on the classpath. The scan runs
when the panel is first opened, and again when it is shown after the project has changed — so
returning to it costs nothing when nothing has moved.

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

**Schedule calls** — going the other way, a call is marked when the task can be named from the call
site itself.

| Shape | Resolves to |
| --- | --- |
| `schedule(ScanFileTask.SCAN_FILE, ctx)` | the task declaring the constant, with no search at all |
| `schedule(TaskDefinitions.S3_MOVE, ctx)` | the task whose `getDef()` returns it — holder class, interface or Kotlin `object` alike |
| A constant returned by two tasks | both of them |
| `schedule(getDef(), ctx)` | the enclosing task; written in an abstract base, every concrete task below it |
| `val d = if (x) A.DEF else B.DEF; schedule(d, ctx)` | *both* tasks |
| `schedule(flag ? A.DEF : B.DEF, ctx)` | likewise, with no local to go through |
| `sneakyScheduler.schedule(DEF, ctx)` | through the project-local wrapper |
| `schedule(context = ctx, taskDef = DEF)` | Kotlin named arguments, in any order |
| `sendVkNotificationTask.schedule(dto, key)` | the receiver's own type, no `TaskDef` in sight |

**Cron tasks** — a task is treated as scheduled when either of these says so, and they are read in
the order the framework merges them, so configuration wins over the annotation.

| Shape | Example |
| --- | --- |
| `@TaskSchedule` on the class | `@TaskSchedule(cron = "0 0/10 * ? * *")` |
| Per-task YAML | `distributed-task.task-properties-group.task-properties.<NAME>.cron` |
| The same, flattened | `…task-properties[NAME].cron=0 0 1 * * *` in `application.properties` |

The key under `task-properties` is the string in `TaskDef.privateTaskDef("…")`, never the class
name — the framework looks it up with a plain `Map#get`. The path leading to it goes through Spring's
relaxed binding, so `distributedTask`, `distributed-task` and a compressed
`distributed-task.task-properties-group:` all reach the same place. Multiple YAML documents in one
file are searched separately, which is how profiles are usually written.

## Known limitations

- **Dispatch that is not statically decidable** is not resolved: bean-selector registries
  (`selector.getTask(type).def`), `getRegisteredTask(name)` string lookups, and `TaskDef`s rebuilt
  at runtime from a name carried inside a message payload.
- **A `TaskDef` passed into an abstract base as a constructor argument** yields no anchor. The icon
  still appears; the list is empty.
- **A receiver typed as a shared base** (`anySchedulableTask.schedule(...)`) is not attributed to
  any single task, because it could be any of them. Neither direction reports it.
- **Reverse navigation needs the definition to be visible at the call.** Inside a wrapper's own
  forward — `schedule(taskDef, ctx)`, where `taskDef` is the method's parameter — the task is
  whatever the caller passed, so there is no icon. Reach that call from the task's own popup
  instead: the forward direction crosses the wrapper boundary, this one stops at it.
- **The reverse icon keys on the method name.** A wrapper called `launch(DEF, ctx)` is still found
  going forward, which keys on the parameter type, but carries no icon going back — resolving every
  call in the file during highlighting would not be worth it.
- **A `TaskDef` built inline at the call site** (`schedule(TaskDef.privateTaskDef(name, …), ctx)`)
  names no declaration, so there is nothing to navigate to.
- **Dataflow is two hops deep** by design. Going forward that is one hop through a local variable
  and one through a wrapper's parameter; coming back it is two chained locals, the wrapper
  parameter being where the reverse direction stops rather than continues.
- **A group-wide `default-properties.cron` is ignored.** It sits *below* `@TaskSchedule` in the
  framework's merge order, and honouring it would put a clock on every task in the module from one
  line of YAML.
- **A blank cron means disabled**, since `hasCron()` is `StringUtils.hasText`. `cron:`, `cron: ''`
  and `cron: ""` do not make a task a cron task; such entries are still listed in the popup, labelled,
  because "configured here, and switched off" is the thing worth seeing.
- **Spring profiles are not evaluated.** Every file configuring the task is listed; which one is
  active at runtime is not something the IDE knows.
- **`${VAR}` is not resolved** — it is shown as written and counts as a schedule. The expression is
  never validated either; DTF accepts a cron expression or an ISO-8601 `Duration`.
- **Configuration is matched across the whole project.** Two modules using the same task name would
  see each other's files. Narrowing to the module would lose tasks that arrive as a binary dependency.
- **`.properties` needs bracket notation** for the usual `SCREAMING_SNAKE` names: Spring normalises a
  dotted map key to lower case, so only `task-properties[NAME].cron` binds. The dotted form is
  recognised too, for the lower-case kebab names that do bind.
- **A cron configured in code is not detected** — `registerTask(task, TaskSettings.builder().cron(…)
  .build())`. Knowing which task such a call registers means resolving its first argument, which is a
  project-wide search, and the gutter is decided during highlighting where searching is not allowed.
  An index cannot stand in: indexing runs without resolve, so it would key on a textual guess and put
  wrong icons in the gutter.
- **`@TaskSchedule` is read on the class and one meta-annotation deep.** It is not `@Inherited`, so a
  base class carrying it does not schedule its subclasses — which is what the framework does too.
- **YAML-configured cron needs the YAML plugin.** It is bundled and enabled by default; with it
  switched off the plugin still loads and falls back to `@TaskSchedule` alone.
- **The clock replaces the T**, so a cron task that is *also* scheduled explicitly no longer offers
  its list of call sites.
- **The tool window lists project sources only.** A task arriving as a binary dependency has no
  source to open and would be a row you cannot click, so it is left out — the same scope every other
  search here uses.
- **The tree groups by module**, and a task belonging to no module falls into a trailing *Outside
  modules* group. Modules without tasks are not shown at all.
- **The tree refreshes when the panel is shown**, not while you type. A project-wide search on every
  keystroke is not worth the accuracy; the toolbar has a Refresh button for the impatient.
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

The build passes `-Xjvm-default=all`. Implementing a Kotlin interface from the platform — such as
`ToolWindowFactory` — otherwise materialises an override of every default method it declares, and
the plugin verifier fails the build on internal-API usages that appear nowhere in the source. The
platform ships no `DefaultImpls` for those interfaces, so there is nothing to stay compatible with.
`DtfTaskToolWindowTest` asserts the flag is still in effect, because only the verifier would notice
otherwise.
