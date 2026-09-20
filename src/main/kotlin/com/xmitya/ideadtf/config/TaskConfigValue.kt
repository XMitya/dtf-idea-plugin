package com.xmitya.ideadtf.config

/**
 * How a task's settings entry is stored in the indexes, and what can be read back out of it.
 *
 * Both indexes are keyed by task name and hold one string per file, so that string has to carry the
 * one thing the highlighting pass asks about - the cron - while still recording entries that declare
 * no cron at all. A leading discriminator does that in the [com.intellij.util.io.EnumeratorStringDescriptor]
 * both indexes already use, rather than introducing a serialization format that would then need
 * versioning of its own alongside `getVersion()`.
 *
 * The merge and summary rules live here too: they were written out identically in both indexes and
 * both sources, and they are the part that a third format would have to get right as well.
 */
object TaskConfigValue {

    /** The entry exists but declares no `cron` key. */
    private const val ABSENT = '-'

    /** A `cron` key is present; the rest of the string is its value, blank included. */
    private const val PRESENT = '+'

    fun encode(cron: String?): String = if (cron == null) ABSENT.toString() else PRESENT + cron

    /** The cron as written, "" for a blank one, null when the entry declares no `cron` at all. */
    fun decode(value: String): String? = if (value.firstOrNull() == PRESENT) value.substring(1) else null

    /**
     * One value per key per file, so several documents in one file have to be merged.
     *
     * A real expression beats a blank one, which beats no `cron` key: the popup still lists every
     * site, so the only thing lost here is which of them the tooltip quotes.
     */
    fun merge(existing: String?, candidate: String): String =
        if (existing == null || rank(candidate) > rank(existing)) candidate else existing

    /**
     * What the index knows about this task's *cron*, or null when nothing configures one.
     *
     * Deliberately blind to settings-only entries: a task configured for its timeout is not a task
     * the framework launches on a schedule, and this is what keeps the clock off its gutter.
     */
    fun summaryOf(values: List<String>): CronSummary? {
        val crons = values.mapNotNull { decode(it) }
        if (crons.isEmpty()) return null
        return CronSummary(
            anyNonBlank = crons.any { it.isNotEmpty() },
            sampleExpression = crons.firstOrNull { it.isNotEmpty() },
        )
    }

    private fun rank(value: String): Int = when (decode(value)) {
        null -> 0
        "" -> 1
        else -> 2
    }
}
