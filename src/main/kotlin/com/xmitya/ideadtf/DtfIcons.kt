package com.xmitya.ideadtf

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DtfIcons {

    /**
     * Gutter marker for a DTF task class.
     *
     * 16x16 rather than the 12x12 gutter default: at 12 the circled letter reads as a dot.
     */
    @JvmField
    val TaskGutter: Icon = IconLoader.getIcon("/icons/dtfTask.svg", DtfIcons::class.java)

    /**
     * Gutter marker for a place that schedules a task.
     *
     * Deliberately the task icon with a downward arrow added rather than a glyph of its own: the two
     * directions of the same navigation should read as a pair. The arrow keeps a light outline so it
     * stays legible where it crosses the blue.
     */
    @JvmField
    val ScheduleGutter: Icon = IconLoader.getIcon("/icons/dtfSchedule.svg", DtfIcons::class.java)

    /**
     * Gutter marker for a task the framework launches on a schedule.
     *
     * A clock rather than another variant of the T: this answers a different question - when does it
     * run - and the yellow separates it at a glance from the blue pair that answers who launches it.
     * The hands read 9:00, which is unambiguous at 16x16 without any numerals.
     */
    @JvmField
    val CronGutter: Icon = IconLoader.getIcon("/icons/dtfCron.svg", DtfIcons::class.java)
}
