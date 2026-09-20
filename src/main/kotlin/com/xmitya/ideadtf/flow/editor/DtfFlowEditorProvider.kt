package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens a [DtfFlowVirtualFile] as a diagram.
 *
 * `DumbAware` so an open diagram survives an indexing pass; the action that *builds* one is not,
 * because the search behind it needs the indexes.
 */
class DtfFlowEditorProvider :
    FileEditorProvider,
    DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is DtfFlowVirtualFile

    /** An `instanceof` resolves nothing, so the platform need not take a read action for it. */
    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DtfFlowFileEditor(project, file as DtfFlowVirtualFile)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "com.xmitya.ideadtf.flow"
    }
}
