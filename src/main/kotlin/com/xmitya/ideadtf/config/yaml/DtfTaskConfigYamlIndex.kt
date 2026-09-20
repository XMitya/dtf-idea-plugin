package com.xmitya.ideadtf.config.yaml

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.xmitya.ideadtf.config.TaskConfigValue
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Maps a task name to every file configuring it, so that the highlighting pass can ask "is this a
 * cron task" without searching anything and the click has a file set to open.
 *
 * Holds no offsets on purpose: index data is only as fresh as the last commit, so a stored offset
 * would put the caret in the wrong place after an edit. The index narrows the file set; PSI supplies
 * the element when the icon is actually clicked.
 */
class DtfTaskConfigYamlIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(YAMLFileType.YML)

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content -> index(content) }

    private fun index(content: FileContent): Map<String, String> {
        // A text test before any parsing, so that a repository full of unrelated YAML - manifests,
        // CI pipelines - costs nothing.
        val text = content.contentAsText
        if (!StringUtil.contains(text, MARKER) && !StringUtil.contains(text, MARKER_RELAXED)) {
            return emptyMap()
        }
        val file = content.psiFile as? YAMLFile ?: return emptyMap()

        val found = HashMap<String, String>()
        for (document in file.documents) {
            YamlTaskPropertiesPath.forEachTaskEntry(document) { name, _, cron ->
                found[name] = TaskConfigValue.merge(found[name], TaskConfigValue.encode(cron))
            }
        }
        return found
    }

    companion object {
        // The id is a storage key rather than a name, so it deliberately still says "cron" - the
        // index outgrew that, and renaming it would strand what is already on disk.
        val NAME: ID<String, String> = ID.create("com.xmitya.ideadtf.cronYaml")

        private const val MARKER = "task-properties"
        private const val MARKER_RELAXED = "taskProperties"
    }
}
