package com.xmitya.ideadtf.cron.properties

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * The `.properties` counterpart of [com.xmitya.ideadtf.cron.yaml.DtfCronYamlIndex].
 *
 * Filters on the extension rather than on `PropertiesFileType`, so that this needs no dependency on
 * the Properties plugin and can live in the main descriptor.
 */
class DtfCronPropertiesIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.extension.equals(EXTENSION, ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content ->
        val found = HashMap<String, String>()
        for (entry in PropertiesCronScanner.scan(content.contentAsText)) {
            val existing = found[entry.name]
            if (existing == null || (existing.isEmpty() && entry.expression.isNotEmpty())) {
                found[entry.name] = entry.expression
            }
        }
        found
    }

    companion object {
        val NAME: ID<String, String> = ID.create("com.xmitya.ideadtf.cronProperties")

        private const val EXTENSION = "properties"
    }
}
