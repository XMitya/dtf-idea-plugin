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
     * The Spring property path a task's settings are bound from, as
     * `distributed-task.task-properties-group.task-properties.<TASK_NAME>.cron`.
     *
     * Segment names go through Spring's relaxed binding, so `distributedTask` reaches the same
     * place; the task name below [CONFIG_TASK_PROPERTIES] does not, because the framework looks it
     * up with a plain case-sensitive `Map#get`.
     */
    const val CONFIG_PREFIX = "distributed-task"
    const val CONFIG_GROUP = "task-properties-group"
    const val CONFIG_TASK_PROPERTIES = "task-properties"

    /**
     * Group-wide defaults. Read only so that the sentinel can be indexed; they sit *below*
     * [TASK_SCHEDULE_ANNOTATION] in the framework's merge order, so a cron here does not make a task
     * a cron task as far as the gutter is concerned.
     */
    const val CONFIG_DEFAULT_PROPERTIES = "default-properties"

    const val CONFIG_CRON = "cron"

    /** The only attribute of [TASK_SCHEDULE_ANNOTATION]. */
    const val CRON_ATTRIBUTE = "cron"

    /** Waits for a set of already-scheduled tasks; the only static evidence a join exists. */
    const val SCHEDULE_JOIN = "scheduleJoin"

    /** Schedules a task *outside* the enclosing join hierarchy - not a fan-out, despite the name. */
    const val SCHEDULE_FORK = "scheduleFork"

    const val SCHEDULE_IMMEDIATELY = "scheduleImmediately"

    /**
     * Methods that launch a task. `scheduleUnsafe` only exists since DTF 2.x; listing it here is
     * harmless on 1.x.
     */
    val SCHEDULE_METHODS: Set<String> = setOf(
        "schedule",
        SCHEDULE_FORK,
        SCHEDULE_IMMEDIATELY,
        "scheduleUnsafe",
        SCHEDULE_JOIN,
    )
}
