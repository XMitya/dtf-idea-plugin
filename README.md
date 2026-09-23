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
straight to that call.

The same icon answers the other question a task raises — *how is this one set up*. A task's
settings live under `task-properties.<TASK_NAME>` in `application.yaml`, keyed by the string in its
`TaskDef` rather than by its class, so finding them means knowing that name and grepping for it.
Every file declaring that block is listed below the call sites, labelled *task settings*, and opens
on the `<TASK_NAME>:` line. A single result, of either kind, navigates immediately.

The same detour exists in reverse. Reading `schedule(SOME_TASK_DEF, ctx)` and wanting the task that
actually runs means Go To Declaration on the constant, landing in a holder class, and Find Usages
from there. So the call gets an icon of its own — the same **T**, with a red arrow down — that
resolves the task and jumps to it. A definition shared by several tasks, or picked by a conditional,
opens a popup naming each task, its definition and `file:line`.

A cron task is launched by the framework rather than by any call, so it carries a yellow **clock**
instead of the **T**. The click is the same one: `cron` is a setting in the same block as `timeout`
and `retry`, so a scheduled task is just a task whose configuration happens to say when it runs.
Where several profiles configure it, all of them are listed, each showing its own cron expression —
so the one that is actually switched off is visible rather than assumed — and a task that is *also*
scheduled explicitly still lists its callers above them.

Anything coming out of a test source root or a test resource root sits on the green background
IDEA gives test files in *Find in Files*, in every one of these lists — and on the flow diagram's
boxes too — `application-test.yml` is told from `application.yml`, and a call from an integration
test from a production one, without reading the container beside it. The colour is the IDE's own: whatever *Settings | Appearance &
Behavior | File Colors* says, including nothing at all when it is switched off.

Both searches run on click, under a cancellable progress dialog, never during highlighting.

Those three icons, and the tool window, all answer questions about one task at a time. The one they
cannot answer is what the *chain* looks like — where a flow starts, what hands work to what, and where
parallel branches come back together. **Show BPMN Flow** draws it, in an editor tab of its own, the
way *Diagrams | Show Diagram…* opens a class diagram.

It is offered in four places, and each means something slightly different:

| Where | What it draws |
| --- | --- |
| A task row in the **DTF Tasks** tool window | the whole flow that task takes part in, upstream and downstream |
| A module row there | every flow in that module, side by side |
| A task class, or a module, in the **Project** view | the same two |
| A `schedule(...)` call, in the editor or from its gutter icon | the flow that call starts |

The diagram runs left to right. Calling code — the method whose body schedules the first task — is a
dashed box on the left; a cron task starts from a timer instead, showing its expression. Tasks are
boxes named the way the tool window names them: the `TaskDef` in bold, the class beside it in grey,
carrying the same **T** or **clock** icon. `scheduleJoin(...)` draws a BPMN parallel gateway — a
diamond with a `+` — that the branches converge on before the join task runs. A box standing for
code in test sources is green, as it is in the gutter's popup and the tool window.

Double-click opens what a box stands for: a task opens its class, calling code opens the exact
`schedule()` line, a timer opens the entry configuring its `cron` — passing over a profile that
only tunes the same task, since a timer stands for the schedule. Hovering names the thing in full.
Ctrl/Cmd with the wheel zooms, and so does a **pinch** on a Mac trackpad — both about the point under
the cursor, which stays put while everything around it grows or shrinks. Dragging the background
pans, and the toolbar has *Fit Content* for when a module turns out larger than expected; it zooms out
as far as 10%, far enough to take in a whole module and zoom back into the part that matters.

A module's diagram is usually many small flows that share nothing. Each is arranged on its own —
its callers directly in front of the task they call, each box opposite the boxes it is connected to
— and the flows are then **tiled** in rows towards the proportions of a screen, rather than merged
into one set of columns fifty tasks tall. Between two columns every square arrow turns on a **track
of its own**: arrows into the same task share one, as a bus, and arrows into different tasks never
do, so where a line goes can be followed rather than guessed. A gap widens when more arrows have to
turn in it than it has room for.

To follow one chain through a crowded diagram, right-click a box and choose **Highlight Flow**:
everything that leads to it and everything it leads to, as far as the arrows go, is drawn in orange
and the rest fades out. **Esc** — or *Clear Highlight* on the same menu — puts it back.

Where one arrow crosses another it makes a small **bridge** over it, so that two lines passing and
two lines meeting can be told apart at a glance — only one of any pair hops, or the crossing would
read as a knot. *Layout* also switches between **square arrows** and **curved** ones; the bridges
belong to the square style, since a semicircle on a curve reads as a kink rather than a crossing.

Some crossings belong to the flow rather than to the arrangement — a task scheduled from six places
will have six arrows into it however the boxes are placed — so there are two ways to untangle a
diagram by hand. **Dragging a box** moves it and only it: everything else stays where the arrangement
put it, and the arrows follow, meeting each box on whichever side now faces the other. *Layout* in
the toolbar, or on a right-click, turns the whole flow **left to right, right to left, top to bottom
or bottom to top**, and *Reset Moved Boxes* puts everything back.

The search runs once, in the background under a cancellable progress, and the tab is rebuilt only
when *Refresh* is pressed. Asking for the same diagram twice brings the tab you already have to the
front rather than opening a second one.

Those three icons each answer a question about one task you are already looking at. The **DTF Tasks**
tool window answers the one they cannot — *which tasks are there at all* — as a tree of project,
module and task. Each row is named by its `TaskDef` and carries the icon it has in the gutter, with
the class name and, for a cron task, the expression beside it in grey; double-click or Enter opens
the task. A right-click carries the gutter's two questions over — *Go to Task Configuration* and *Go
to Schedule Calls* open the same lists the icon does — and copies either name the row is known by:
the `TaskDef` or the qualified class name. Ctrl/Cmd+C copies what the selected rows are called,
tasks, modules and the project alike. A task declared in test sources carries the same green
background its file has elsewhere in the IDE. Typing finds a task or a module by name, reaching into
collapsed modules, so nothing has to be opened first. The stripe button appears only in projects that have DTF on the classpath. The scan runs
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
| Helper taking the definition elsewhere | `createTask(event, taskDef)`, `scheduleAggregate(name, props, DEF)` |
| Anything of the above inside a lambda | `events.forEach(e -> { var d = flag ? A.DEF : B.DEF; schedule(d, e); })` |
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
| `schedule(taskDef, ctx)` inside the wrapper itself | every task its callers pass in |
| `schedule(context = ctx, taskDef = DEF)` | Kotlin named arguments, in any order |
| `sendVkNotificationTask.schedule(dto, key)` | the receiver's own type, no `TaskDef` in sight |

**Joins** — a `scheduleJoin(def, ctx, joinList)` call, which is the only thing in the source that
states outright that a flow branches. Nothing marks a join task itself: it is an ordinary `Task<T>`
that happens to read `getInputJoinTaskMessages()`. The branches are whatever produced the `TaskId`s
in `joinList`, and how far that can be read decides how the gateway is drawn.

| Shape | Read as |
| --- | --- |
| `List.of(idA, idB)`, each id a local holding a `schedule(...)` | exactly those two branches |
| `new ArrayList<>()` plus `add(taskId)` in a loop | the task scheduled in the loop |
| Anything else in the same method — a stream collected to a list, `schedule(...).apply(list::add)` | every task scheduled in that method |
| A `List<TaskId>` handed back by a helper | every task scheduled in the class, drawn **dashed** as the guess it is |

`scheduleFork` is *not* a fan-out — its javadoc is "the same as `schedule` but exclude itself from
join hierarchy of parent task" — so a forked branch is drawn leaving the flow rather than entering
the gateway.

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

A block with no `cron` in it — `timeout`, `retry`, `max-parallel-in-cluster` — is a place to
navigate to but **not** a schedule: it leaves the task on the **T**, and nothing about it puts a
clock in the gutter.

## Known limitations

- **Dispatch that is not statically decidable** is not resolved: bean-selector registries
  (`selector.getTask(type).def`), `getRegisteredTask(name)` string lookups, and `TaskDef`s rebuilt
  at runtime from a name carried inside a message payload.
- **A `TaskDef` passed into an abstract base as a constructor argument** yields no anchor. The icon
  still appears; the list is empty.
- **A receiver typed as a shared base** (`anySchedulableTask.schedule(...)`) is not attributed to
  any single task, because it could be any of them. Neither direction reports it.
- **A wrapper is recognised by what it does, not by what it takes.** A project method counts as a
  scheduler when one of its `TaskDef` parameters actually reaches a scheduling call in its own body,
  at most one wrapper deep. That is what makes accepting the definition at *any* argument position
  safe; a method that takes a `TaskDef` to log its name or build a key is not a call site.
- **Inside a wrapper's own forward the definition belongs to the callers**, and that is where the
  reverse direction goes to look — one hop, one project-wide search, on click. A wrapper called from
  fifty places lists every task any of them passes. The forward direction still reports the caller's
  own line rather than the wrapper's shared internals, so the two meet at different lines by design.
- **The reverse icon keys on the method name.** A wrapper called `launch(DEF, ctx)` is still found
  going forward, which keys on the parameter type, but carries no icon going back — resolving every
  call in the file during highlighting would not be worth it.
- **A `TaskDef` built inline at the call site** (`schedule(TaskDef.privateTaskDef(name, …), ctx)`)
  names no declaration, so there is nothing to navigate to.
- **Dataflow is two hops deep** by design: one hop through a local variable and one through a
  wrapper's parameter, in either direction. A reference is followed through a local even when it
  sits inside a lambda, but not out of one — the body of a lambda is a value, not an argument of
  whatever the lambda was handed to.
- **A group-wide `default-properties.cron` is ignored**, and `default-properties` is not offered as
  a navigation target either. It sits *below* `@TaskSchedule` in the framework's merge order, so
  honouring it would put a clock on every task in the module from one line of YAML — and offering it
  would put the identical row under every task in the project, which is noise rather than
  navigation.
- **A blank cron means disabled**, since `hasCron()` is `StringUtils.hasText`. `cron:`, `cron: ''`
  and `cron: ""` do not make a task a cron task; such entries are still listed in the popup, labelled,
  because "configured here, and switched off" is the thing worth seeing. A block with no `cron` key
  at all is listed too, labelled *task settings* — the three are told apart rather than lumped
  together, since they answer different questions.
- **Test sources are marked, not filtered.** Every list still includes them; they are only easier
  to see. The green comes from *File Colors*, so it follows whatever is configured there and
  disappears with it — the plugin defines no scope and no colour of its own.
- **Spring profiles are not evaluated.** Every file configuring the task is listed; which one is
  active at runtime is not something the IDE knows.
- **`${VAR}` is not resolved** — it is shown as written and counts as a schedule. The expression is
  never validated either; DTF accepts a cron expression or an ISO-8601 `Duration`.
- **Configuration is matched across the whole project.** Two modules using the same task name would
  see each other's files. Narrowing to the module would lose tasks that arrive as a binary dependency.
- **`.properties` needs bracket notation** for the usual `SCREAMING_SNAKE` names: Spring normalises a
  dotted map key to lower case, so only `task-properties[NAME].<setting>` binds. The dotted form is
  recognised too, for the lower-case kebab names that do bind.
- **A `.properties` file gives one row per task, not per setting.** A task is spread over as many
  lines as it has settings there, and eight rows for one file is a list nobody reads; the row
  anchors on the `cron` line when there is one, since that is what it is labelled with, and on the
  first setting written otherwise. YAML gives one row per document, which is how profiles in a
  single file stay distinguishable.
- **A bare `<TASK_NAME>:` with nothing under it is not a target.** It configures nothing, and
  accepting it would mean accepting scalars and sequences under `task-properties` as well.
- **A task with one call site and one configuration block opens a popup** rather than jumping, where
  it used to jump straight to the call. Call sites are listed first and the popup opens on the first
  row, so Enter still lands on the call.
- **A cron configured in code is not detected** — `registerTask(task, TaskSettings.builder().cron(…)
  .build())`. Knowing which task such a call registers means resolving its first argument, which is a
  project-wide search, and the gutter is decided during highlighting where searching is not allowed.
  An index cannot stand in: indexing runs without resolve, so it would key on a textual guess and put
  wrong icons in the gutter.
- **`@TaskSchedule` is read on the class and one meta-annotation deep.** It is not `@Inherited`, so a
  base class carrying it does not schedule its subclasses — which is what the framework does too.
- **YAML-configured cron needs the YAML plugin.** It is bundled and enabled by default; with it
  switched off the plugin still loads and falls back to `@TaskSchedule` alone.
- **The tool window lists project sources only.** A task arriving as a binary dependency has no
  source to open and would be a row you cannot click, so it is left out — the same scope every other
  search here uses.
- **The tree groups by module**, and a task belonging to no module falls into a trailing *Outside
  modules* group. Modules without tasks are not shown at all.
- **The tree's two navigation entries work on one row.** Two selected tasks are two lists, and
  guessing which was meant is worse than offering neither, so both entries hide on a multiple
  selection — the copies still give a line per row. *Go to Task Configuration* also hides on a task
  whose `TaskDef` could not be read, since configuration is looked up by that name.
- **The tree refreshes when the panel is shown**, not while you type. A project-wide search on every
  keystroke is not worth the accuracy; the toolbar has a Refresh button for the impatient.
- **The diagram draws types, not runs.** A `repeat(n)` fan-out is one branch box, because how many
  there will be is decided at runtime. The gateway says what waits for what, not how many.
- **A join assembled inside a helper is approximated.** When the `joinList` is built several calls
  deep, the branches are taken to be everything the class schedules; the gateway is drawn dashed and
  says so on hover, rather than passing a guess off as a reading.
- **Message types and conditions are not drawn yet.** The model carries both and the layout already
  reserves the space on each arrow, but nothing writes into it: a task scheduled from inside an `if`
  is an ordinary arrow for now.
- **A task's own `schedule(message)` helper is not a loop.** Some project bases give every task one,
  and its body is `distributedTaskService.schedule(getDef(), ...)` — which reads exactly like a
  self-reschedule and is the opposite of one. The callers are drawn instead, and both directions of
  the walk ask the same question so they cannot disagree.
- **A project scheduler is stepped through, once.** A helper holding the `TaskDef` itself —
  `workflowScheduler.scheduleCleanup(payload)` — would otherwise break the chain in two, so its
  callers are searched and a *task* among them becomes the arrow. Called only from a controller or a
  listener, the helper's own method stays the start of the flow, as one box rather than one per
  trigger site.
- **Flows that are not `schedule()` calls are not drawn.** A project framework that *returns* its
  next step rather than scheduling it, and DTF 2.x's own `registerToRun(...).thenRun(...)` saga DSL,
  are invisible here for the same reason `@SagaMethod` is.
- **The walk is capped** at 300 boxes and 12 hops — far above anything observed, real chains being
  three to six — and the diagram says so when a cap is reached.
- **Saga steps** (`@SagaMethod`) are a separate mechanism with no `TaskDef` in user code, and are
  not covered.

## Building

Requires JDK 21.

```sh
./gradlew check           # ktlint, the fixture tests and the coverage gate
./gradlew buildPlugin     # -> build/distributions/idea-dtf-plugin-<version>.zip
./gradlew test            # fixture tests alone
./gradlew verifyPlugin    # IntelliJ Plugin Verifier
./gradlew runIde          # sandbox IDE with the plugin installed

# custom-repository manifest, pointing at the directory that serves the zip
./gradlew generateUpdatePluginsXml -PpluginBaseUrl=https://host/path
```

### Style and coverage

`ktlintFormat` fixes what `ktlintCheck` reports. The layout comes from `.editorconfig`, which picks
ktlint's `intellij_idea` code style - the one the IDE this plugin is written in already applies, so
the linter stays a gate rather than a reformatting campaign.

`koverHtmlReport` writes `build/reports/kover/html`. `koverVerify` requires 90% line coverage over
the whole plugin, nothing excluded; the suite currently sits at about 96%. Lines rather than
branches, because most branches here are Kotlin's null checks on platform API that is nullable in
principle and never null in a fixture.

Both run as part of `check`, which is what CI calls.

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

`.github/workflows/build.yml` checks - style, tests, coverage - then builds and verifies every push
to `main` and every pull request.

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
