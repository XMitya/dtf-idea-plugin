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
}
