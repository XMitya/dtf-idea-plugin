package com.xmitya.ideadtf.config.yaml

import com.xmitya.ideadtf.DtfFqns
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Finds DTF's task settings inside one YAML document.
 *
 * Deliberately not [org.jetbrains.yaml.YAMLUtil.getQualifiedKeyInFile]: that one is
 * `getDocuments().get(0)`, so it reads only the first document and silently misses everything past a
 * `---` - which is exactly how Spring profiles are written - besides throwing on a file with no
 * documents at all.
 */
object YamlTaskPropertiesPath {

    private val TASK_PROPERTIES_PATH =
        listOf(DtfFqns.CONFIG_PREFIX, DtfFqns.CONFIG_GROUP, DtfFqns.CONFIG_TASK_PROPERTIES)

    /**
     * Spring's relaxed binding, reduced to what matters here: case and the separators are noise, so
     * `distributed-task`, `distributedTask` and `DISTRIBUTED_TASK` are one name.
     *
     * Applies to the path prefix only. The keys *under* `task-properties` are task names, which the
     * framework looks up with a plain case-sensitive `Map#get`.
     */
    fun canonical(segment: String): String = buildString(segment.length) {
        for (ch in segment) {
            if (ch != '-' && ch != '_') append(ch.lowercaseChar())
        }
    }

    /**
     * Hands every `<TASK_NAME>:` entry to [consumer], with the cron it declares.
     *
     * A cron is one setting among several - `timeout`, `retry`, `max-parallel-in-cluster` - so an
     * entry without one is still a place the task is configured and still worth navigating to. The
     * three answers are told apart by null versus blank: null is no `cron` key at all, blank is a
     * key that is there and switched off.
     *
     * An entry whose value is not a mapping is skipped: a bare `<TASK_NAME>:` configures nothing,
     * and accepting it would mean accepting scalars and sequences under `task-properties` too.
     */
    fun forEachTaskEntry(document: YAMLDocument, consumer: (name: String, entry: YAMLKeyValue, cron: String?) -> Unit) {
        val mapping = findMapping(document, TASK_PROPERTIES_PATH) ?: return
        for (entry in mapping.keyValues) {
            val name = entry.keyText
            if (name.isEmpty()) continue
            val settings = entry.value as? YAMLMapping ?: continue
            // Presence, not text: getValueText() returns "" both for `cron:` with nothing after it
            // and for no `cron` key at all, and those are now two different answers rather than one.
            consumer(name, entry, findChild(settings, DtfFqns.CONFIG_CRON)?.valueText?.trim())
        }
    }

    /** The mapping [path] leads to, or null if the document does not have it. */
    fun findMapping(document: YAMLDocument, path: List<String>): YAMLMapping? {
        var current = document.topLevelValue as? YAMLMapping ?: return null
        var index = 0
        while (index < path.size) {
            val (entry, consumed) = findEntry(current, path, index) ?: return null
            index += consumed
            current = entry.value as? YAMLMapping ?: return null
        }
        return current
    }

    private fun findChild(mapping: YAMLMapping, name: String): YAMLKeyValue? =
        mapping.keyValues.firstOrNull { canonical(it.keyText) == canonical(name) }

    /**
     * The entry matching [path] from [from] onwards, and how many segments it swallowed.
     *
     * More than one when the key is written compressed - `distributed-task.task-properties-group:`
     * on a single line, which Spring accepts and which then has to match two segments at once.
     */
    private fun findEntry(mapping: YAMLMapping, path: List<String>, from: Int): Pair<YAMLKeyValue, Int>? {
        for (entry in mapping.keyValues) {
            val segments = entry.keyText.split('.').filter { it.isNotEmpty() }
            if (segments.isEmpty() || segments.size > path.size - from) continue
            val matches = segments.indices.all { canonical(segments[it]) == canonical(path[from + it]) }
            if (matches) return entry to segments.size
        }
        return null
    }
}
