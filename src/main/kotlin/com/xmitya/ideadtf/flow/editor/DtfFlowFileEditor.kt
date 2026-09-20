package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.xmitya.ideadtf.DtfBundle
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * A flow diagram as an editor tab.
 *
 * There is no state worth remembering - the diagram is rebuilt from its scope, and the scope lives
 * on the file - so `getState` and the property listeners are honest no-ops rather than plumbing.
 */
class DtfFlowFileEditor(project: Project, private val file: DtfFlowVirtualFile) :
    UserDataHolderBase(),
    FileEditor {

    private val panel = DtfFlowPanel(project, file.scope)

    init {
        panel.refresh()
    }

    /** Public so a test can look at what the tab actually shows. */
    val flowPanel: DtfFlowPanel get() = panel

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusComponent

    override fun getName(): String = DtfBundle.message("dtf.flow.editor.name")

    override fun getFile(): VirtualFile = file

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Disposer.dispose(panel)
}
