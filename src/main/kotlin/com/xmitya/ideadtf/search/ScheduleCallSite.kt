package com.xmitya.ideadtf.search

import com.intellij.psi.PsiElement

/** How a task gets launched at a given call site. */
enum class ScheduleTier {
    /** A call straight into the framework, e.g. `distributedTaskService.schedule(DEF, ctx)`. */
    SERVICE,

    /** A project-local helper that takes a `TaskDef` and forwards it, e.g. a `SneakyScheduler`. */
    WRAPPER,
}

/** One place where a task is scheduled. */
data class ScheduleCallSite(
    /** Source PSI of the call, so navigation lands on the exact line. */
    val element: PsiElement,
    val tier: ScheduleTier,
) {
    /** Identity for de-duplication: the same call is reachable through several anchors. */
    val key: Pair<String, Int>
        get() = (element.containingFile?.virtualFile?.path ?: "") to element.textRange.startOffset
}
