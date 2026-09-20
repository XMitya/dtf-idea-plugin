package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.xmitya.ideadtf.DtfBundle
import javax.swing.Icon

/**
 * What the flow tab's icon and name come from.
 *
 * A plain [FileType] rather than a registered `<fileType>`: nothing ever has to recognise this type
 * from a file name, and registering one would put an entry in *Settings | File Types* that no user
 * could do anything with.
 */
object DtfFlowFileType : FileType {

    override fun getName(): String = "DTF Flow"

    override fun getDescription(): String = DtfBundle.message("dtf.flow.filetype.description")

    override fun getDefaultExtension(): String = ""

    override fun getIcon(): Icon = AllIcons.FileTypes.Diagram

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true
}
