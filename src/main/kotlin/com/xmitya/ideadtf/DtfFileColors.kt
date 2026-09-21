package com.xmitya.ideadtf

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.VfsPresentationUtil
import java.awt.Color

/**
 * The background the IDE itself would paint a file with: green for test sources and test resources
 * out of the box, and whatever else is set up under Settings | Appearance | File Colors.
 *
 * This is the very call the Find in Files list makes for its own rows, and the one the platform's
 * `PsiElementListCellRenderer` feeds into `TargetPresentation.backgroundColor` - so a row here ends
 * up the colour its file has everywhere else, rather than a second, slightly different green.
 * Deciding it locally, from `TestSourcesFilter` and a colour of this plugin's own, would also keep
 * painting after someone turned file colours off; this answers null in that case by itself.
 */
object DtfFileColors {

    /** Null for a row that stands for no file, and for every file the IDE would leave plain. */
    fun of(project: Project, file: VirtualFile?): Color? = file?.let { VfsPresentationUtil.getFileBackgroundColor(project, it) }
}
