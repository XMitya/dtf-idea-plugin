package com.xmitya.ideadtf.config.properties

import com.xmitya.ideadtf.DtfFqns

/**
 * Reads DTF task settings out of a `.properties` file.
 *
 * Hand-written rather than going through the Properties plugin's PSI: an entry is one line, so there
 * is nothing here a parser would get right and this would not - unlike YAML, where flow mappings,
 * block scalars and multiple documents make a scanner a bad bet.
 */
object PropertiesTaskConfigScanner {

    /**
     * `distributed-task.task-properties-group.task-properties[NAME].<setting>`, or the dotted form.
     *
     * Brackets are the form that actually works for the names seen in practice: Spring normalises a
     * dotted map key to lowercase `[a-z0-9-]`, so `...task-properties.GET_X_TASK.cron` never binds
     * to the key `GET_X_TASK`. The dotted branch is accepted for all-lowercase-kebab names, which do
     * bind.
     *
     * The setting is one trailing group rather than a single segment, so that a nested one -
     * `retry.fixed.delay` - is read whole. Requiring it to be there at all is what keeps
     * `...task-properties.enabled=true` from being read as a task named `enabled`.
     */
    private val KEY = Regex(
        """^distributed[-_]?task\.task[-_]?properties[-_]?group\.task[-_]?properties""" +
            """(?:\[([^]]+)]|\.([A-Za-z0-9-]+))\.(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** One `<name>.<setting>` assignment, with where its line starts. */
    data class Entry(val name: String, val setting: String, val value: String, val offset: Int) {
        val isCron: Boolean get() = setting.equals(DtfFqns.CONFIG_CRON, ignoreCase = true)
    }

    fun scan(text: CharSequence): List<Entry> {
        val entries = mutableListOf<Entry>()
        var lineStart = 0
        val length = text.length

        while (lineStart <= length) {
            var lineEnd = lineStart
            while (lineEnd < length && text[lineEnd] != '\n') lineEnd++
            parseLine(text, lineStart, lineEnd)?.let { entries += it }
            if (lineEnd >= length) break
            lineStart = lineEnd + 1
        }
        return entries
    }

    private fun parseLine(text: CharSequence, start: Int, end: Int): Entry? {
        // The offset is the start of the line rather than of the key, so that the caret lands where
        // the setting is written rather than after any indentation.
        val line = text.subSequence(start, end).toString().trim()
        if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) return null

        val separator = line.indexOfFirst { it == '=' || it == ':' }
        if (separator <= 0) return null

        val match = KEY.matchEntire(line.substring(0, separator).trim()) ?: return null
        val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
        if (name.isEmpty()) return null

        return Entry(name, match.groupValues[3], line.substring(separator + 1).trim(), offset = start)
    }
}
