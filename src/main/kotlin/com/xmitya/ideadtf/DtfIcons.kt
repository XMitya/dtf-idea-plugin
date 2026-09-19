package com.xmitya.ideadtf

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object DtfIcons {

    /** Gutter marker for a DTF task class. 12x12, the gutter icon size. */
    @JvmField
    val TaskGutter: Icon = IconLoader.getIcon("/icons/dtfTask.svg", DtfIcons::class.java)
}
