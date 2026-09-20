package com.xmitya.ideadtf.model

/** How a run of a task's label reads. The two renderers decide what that looks like. */
enum class DtfTextStyle {
    /** The subject of the row: the task definition, or the class when there is no definition. */
    LEAD,

    /** Context beside it - the class name, the cron expression. */
    GREY,
}

data class DtfTextRun(val text: String, val style: DtfTextStyle)

/**
 * What a DTF task is called, wherever it is shown.
 *
 * The tool window tree and the flow diagram must not drift apart on what a task row says - the rules
 * are small but easy to get subtly wrong, particularly "do not repeat the class name when it is
 * already the label" - so the decision lives here and each renderer only decides how a
 * [DtfTextStyle] is painted.
 */
object DtfRowText {

    private const val SEPARATOR = "  "

    fun taskRuns(taskName: String?, className: String, cronExpression: String?): List<DtfTextRun> = buildList {
        add(DtfTextRun(taskName ?: className, DtfTextStyle.LEAD))
        // Only when it adds something: with no TaskDef to read, the class name is already the label.
        if (taskName != null && className.isNotEmpty()) {
            add(DtfTextRun(SEPARATOR + className, DtfTextStyle.GREY))
        }
        cronExpression?.takeIf { it.isNotBlank() }?.let {
            add(DtfTextRun(SEPARATOR + it, DtfTextStyle.GREY))
        }
    }
}
