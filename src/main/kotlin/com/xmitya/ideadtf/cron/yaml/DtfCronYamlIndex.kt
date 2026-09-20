package com.xmitya.ideadtf.cron.yaml

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
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Maps a task name to the cron configured for it, so that the highlighting pass can ask "is this a
 * cron task" without searching anything.
 *
 * Holds no offsets on purpose: index data is only as fresh as the last commit, so a stored offset
 * would put the caret in the wrong place after an edit. The index narrows the file set; PSI supplies
 * the element when the icon is actually clicked.
 */
class DtfCronYamlIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 1

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
            YamlCronPath.forEachTaskCron(document) { name, _, expression ->
                // One value per key per file, so several documents in one file have to be merged.
                // A real expression beats a blank one; the popup still lists both sites.
                val existing = found[name]
                if (existing == null || (existing.isEmpty() && expression.isNotEmpty())) {
                    found[name] = expression
                }
            }
        }
        return found
    }

    companion object {
        val NAME: ID<String, String> = ID.create("com.xmitya.ideadtf.cronYaml")

        private const val MARKER = "task-properties"
        private const val MARKER_RELAXED = "taskProperties"
    }
}
