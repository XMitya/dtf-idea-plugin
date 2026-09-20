package com.xmitya.ideadtf.flow

/**
 * What a flow diagram is built from.
 *
 * Holds no PSI - only enough to find the starting point again in a project that may have changed
 * since. That is what lets a tab rebuild itself after an edit, and what lets two invocations for the
 * same subject be recognised as one diagram rather than opening a second tab.
 */
sealed interface DtfFlowScope {

    /** Identity of the tab. Two scopes with the same key are the same diagram. */
    val key: String

    /** What the tab is called. */
    val title: String

    /** The flow one task takes part in, followed in both directions. */
    data class Task(val qualifiedName: String, override val title: String) : DtfFlowScope {
        override val key: String get() = "task:$qualifiedName"
    }

    /** Every flow of every task belonging to one module, drawn side by side. */
    data class Module(val moduleName: String, override val title: String) : DtfFlowScope {
        override val key: String get() = "module:$moduleName"
    }

    /**
     * The flow that starts at one `schedule(...)` call.
     *
     * Keyed by file and offset rather than by the task it launches, because the point of invoking it
     * from a call site is to see the flow *from there* - a task scheduled in ten places has ten
     * answers to "where does this one go".
     */
    data class Call(val fileUrl: String, val offset: Int, override val title: String) : DtfFlowScope {
        override val key: String get() = "call:$fileUrl:$offset"
    }
}
