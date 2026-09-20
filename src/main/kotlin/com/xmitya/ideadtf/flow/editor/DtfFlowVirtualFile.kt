package com.xmitya.ideadtf.flow.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile
import com.xmitya.ideadtf.flow.DtfFlowScope

/**
 * The file a flow tab is opened on.
 *
 * A [LightVirtualFile] because there is no file behind the tab at all, and
 * [VirtualFileWithoutContent] so that indexing and content loading leave it alone.
 *
 * Equality is deliberately left as identity: overriding it on a `VirtualFile` would break the
 * platform maps that assume VFS identity. Reopening the same diagram therefore goes through
 * [DtfFlowTabService]'s registry instead, which is keyed on the scope.
 *
 * It also means a flow tab is never restored after a restart, which is the behaviour we want: the
 * platform remembers open tabs by URL, and this file's URL resolves to nothing.
 */
class DtfFlowVirtualFile(val scope: DtfFlowScope, title: String) :
    LightVirtualFile(title),
    VirtualFileWithoutContent {

    init {
        isWritable = false
    }

    override fun getFileType(): FileType = DtfFlowFileType
}
