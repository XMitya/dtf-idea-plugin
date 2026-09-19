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
}
