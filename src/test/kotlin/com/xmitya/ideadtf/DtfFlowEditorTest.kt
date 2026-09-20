package com.xmitya.ideadtf

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.editor.DtfFlowEditorProvider
import com.xmitya.ideadtf.flow.editor.DtfFlowFileEditor
import com.xmitya.ideadtf.flow.editor.DtfFlowFileType
import com.xmitya.ideadtf.flow.editor.DtfFlowVirtualFile

/**
 * The tab itself.
 *
 * Mostly descriptor wiring, which nothing else would catch: a diagram that cannot be opened looks
 * exactly like an action that does nothing.
 */
class DtfFlowEditorTest : DtfFixtureTestCase() {

    private val provider = DtfFlowEditorProvider()

    fun testProviderAcceptsOnlyItsOwnFile() {
        assertTrue(provider.accept(project, DtfFlowVirtualFile(scope(), "HELLO flow")))
        // The guard that also keeps a tab from being restored after a restart: the platform
        // remembers a tab by URL, and a URL can never produce a DtfFlowVirtualFile.
        assertFalse(provider.accept(project, LightVirtualFile("HELLO flow")))
    }

    fun testProviderWiring() {
        assertEquals("com.xmitya.ideadtf.flow", provider.editorTypeId)
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
        assertFalse("an instanceof needs no read action", provider.acceptRequiresReadAction())
    }

    fun testTheSyntheticFileIsReadOnlyAndCarriesTheScope() {
        val file = DtfFlowVirtualFile(scope(), "HELLO flow")

        assertFalse(file.isWritable)
        assertSame(DtfFlowFileType, file.fileType)
        assertEquals(scope(), file.scope)
        assertEquals("HELLO flow", file.name)
    }

    fun testTheFileTypeIsOnlyThereForTheTabGlyph() {
        assertEquals("DTF Flow", DtfFlowFileType.name)
        assertEquals("", DtfFlowFileType.defaultExtension)
        assertTrue(DtfFlowFileType.isBinary)
        assertTrue(DtfFlowFileType.isReadOnly)
        assertNotNull(DtfFlowFileType.icon)
        assertEquals(DtfBundle.message("dtf.flow.filetype.description"), DtfFlowFileType.description)
    }

    fun testEditorCanBeBuiltAndDisposed() {
        val file = DtfFlowVirtualFile(scope(), "HELLO flow")
        val editor = provider.createEditor(project, file) as DtfFlowFileEditor

        assertNotNull(editor.component)
        assertNotNull(editor.preferredFocusedComponent)
        assertEquals(DtfBundle.message("dtf.flow.editor.name"), editor.name)
        assertSame(file, editor.file)
        assertFalse(editor.isModified)
        assertTrue(editor.isValid)
        assertNotNull(editor.getState(FileEditorStateLevel.FULL))
        editor.setState(editor.getState(FileEditorStateLevel.FULL))
        editor.addPropertyChangeListener { }
        editor.removePropertyChangeListener { }

        Disposer.dispose(editor)
    }

    private fun scope() = DtfFlowScope.Task("HelloTask", "HELLO")
}
