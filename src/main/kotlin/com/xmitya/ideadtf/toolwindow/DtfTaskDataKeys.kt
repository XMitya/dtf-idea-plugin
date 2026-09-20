package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.actionSystem.DataKey

object DtfTaskDataKeys {

    /**
     * The tasks selected in the DTF Tasks tree.
     *
     * The tree already knows them, and each one carries its name, its class and a pointer, so an
     * action reading this key needs no PSI of its own to decide whether it applies.
     */
    val TASK_ENTRIES: DataKey<List<DtfTaskEntry>> = DataKey.create("com.xmitya.ideadtf.taskEntries")
}
