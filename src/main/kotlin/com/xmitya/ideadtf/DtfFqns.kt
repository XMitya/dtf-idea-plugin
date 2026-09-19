package com.xmitya.ideadtf

/**
 * Fully qualified names of the Distributed Task Framework API the plugin keys off.
 *
 * Kept in one place so that a framework rename is a single edit.
 */
object DtfFqns {

    /** Root interface every DTF task implements, directly or transitively. */
    const val TASK = "com.distributed_task_framework.task.Task"

    /** Identity of a task; always parameter 0 of every scheduling method. */
    const val TASK_DEF = "com.distributed_task_framework.model.TaskDef"

    /**
     * Where the scheduling methods are actually declared. [DISTRIBUTED_TASK_SERVICE] only inherits
     * them, so a containing-class check has to accept this supertype.
     */
    const val TASK_COMMAND_SERVICE = "com.distributed_task_framework.service.TaskCommandService"

    const val DISTRIBUTED_TASK_SERVICE = "com.distributed_task_framework.service.DistributedTaskService"

    /**
     * Marks a task the framework launches on a cron schedule, so it can legitimately have no
     * explicit call site.
     */
    const val TASK_SCHEDULE_ANNOTATION = "com.distributed_task_framework.autoconfigure.annotation.TaskSchedule"

    /** The name of the accessor that hands out a task's [TASK_DEF]. */
    const val GET_DEF = "getDef"

    /**
     * Methods that launch a task. `scheduleUnsafe` only exists since DTF 2.x; listing it here is
     * harmless on 1.x.
     */
    val SCHEDULE_METHODS: Set<String> = setOf(
        "schedule",
        "scheduleFork",
        "scheduleImmediately",
        "scheduleUnsafe",
        "scheduleJoin",
    )
}
