package com.xmitya.ideadtf.config.properties

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.xmitya.ideadtf.config.TaskConfigValue

/**
 * The `.properties` counterpart of [com.xmitya.ideadtf.config.yaml.DtfTaskConfigYamlIndex].
 *
 * Filters on the extension rather than on `PropertiesFileType`, so that this needs no dependency on
 * the Properties plugin and can live in the main descriptor.
 */
class DtfTaskConfigPropertiesIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.extension.equals(EXTENSION, ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content ->
        val found = HashMap<String, String>()
        // A task is spread over one line per setting here, so the same name is met several times
        // and only the one that carries a cron decides what the index says about it.
        for (entry in PropertiesTaskConfigScanner.scan(content.contentAsText)) {
            val encoded = TaskConfigValue.encode(if (entry.isCron) entry.value else null)
            found[entry.name] = TaskConfigValue.merge(found[entry.name], encoded)
        }
        found
    }

    companion object {
        // The id is a storage key rather than a name, so it deliberately still says "cron" - the
        // index outgrew that, and renaming it would strand what is already on disk.
        val NAME: ID<String, String> = ID.create("com.xmitya.ideadtf.cronProperties")

        private const val EXTENSION = "properties"
    }
}
