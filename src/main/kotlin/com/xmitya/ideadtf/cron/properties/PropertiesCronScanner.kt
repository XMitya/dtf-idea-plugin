package com.xmitya.ideadtf.cron.properties

/**
 * Reads DTF cron settings out of a `.properties` file.
 *
 * Hand-written rather than going through the Properties plugin's PSI: an entry is one line, so there
 * is nothing here a parser would get right and this would not - unlike YAML, where flow mappings,
 * block scalars and multiple documents make a scanner a bad bet.
 */
object PropertiesCronScanner {

    /**
     * `distributed-task.task-properties-group.task-properties[NAME].cron`, or the dotted form.
     *
     * Brackets are the form that actually works for the names seen in practice: Spring normalises a
     * dotted map key to lowercase `[a-z0-9-]`, so `...task-properties.GET_X_TASK.cron` never binds
     * to the key `GET_X_TASK`. The dotted branch is accepted for all-lowercase-kebab names, which do
     * bind.
     */
    private val KEY = Regex(
        """^distributed[-_]?task\.task[-_]?properties[-_]?group\.task[-_]?properties""" +
            """(?:\[([^]]+)]|\.([A-Za-z0-9-]+))\.cron$""",
        RegexOption.IGNORE_CASE,
    )

    /** One `<name>.cron` assignment, with where its line starts. */
    data class Entry(val name: String, val expression: String, val offset: Int)

    fun scan(text: CharSequence): List<Entry> {
        val entries = mutableListOf<Entry>()
        var lineStart = 0
        val length = text.length

        while (lineStart <= length) {
            var lineEnd = lineStart
            while (lineEnd < length && text[lineEnd] != '\n') lineEnd++
            parseLine(text, lineStart, lineEnd)?.let { (name, expression) ->
                entries += Entry(name, expression, lineStart)
            }
            if (lineEnd >= length) break
            lineStart = lineEnd + 1
        }
        return entries
    }

    private fun parseLine(text: CharSequence, start: Int, end: Int): Pair<String, String>? {
        val line = text.subSequence(start, end).toString().trim()
        if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) return null

        val separator = line.indexOfFirst { it == '=' || it == ':' }
        if (separator <= 0) return null

        val match = KEY.matchEntire(line.substring(0, separator).trim()) ?: return null
        val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
        if (name.isEmpty()) return null

        return name to line.substring(separator + 1).trim()
    }
}
