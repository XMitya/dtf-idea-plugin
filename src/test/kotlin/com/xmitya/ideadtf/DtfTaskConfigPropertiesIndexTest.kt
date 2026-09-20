package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.config.TaskConfigSite

/** The flattened `.properties` form of the same configuration. */
class DtfTaskConfigPropertiesIndexTest : DtfFixtureTestCase() {

    private val prefix = "distributed-task.task-properties-group.task-properties"

    /** Brackets are the form that actually binds for an upper-case name. */
    fun testBracketNotationIsFound() {
        configure("$prefix[HELLO_TASK].cron=0 0 1 * * *")
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
    }

    /** The dotted form binds only for lower-case kebab names, which do occur. */
    fun testDottedNotationIsFound() {
        configure("$prefix.postponed-events-check-v1.cron=0 0/10 * ? * *")
        assertEquals("0 0/10 * ? * *", single("postponed-events-check-v1").cron)
    }

    fun testColonSeparatorIsAccepted() {
        configure("$prefix[HELLO_TASK].cron: 0 0 1 * * *")
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
    }

    fun testBlankCronIsASiteButNotASchedule() {
        configure("$prefix[HELLO_TASK].cron=")
        assertEquals("", single("HELLO_TASK").cron)
        assertFalse(summarised("HELLO_TASK"))
    }

    fun testCommentIsIgnored() {
        configure("#$prefix[HELLO_TASK].cron=0 0 1 * * *")
        assertEmpty(sites("HELLO_TASK"))
    }

    fun testOtherSettingsAreAConfigSiteWithNoCron() {
        configure("$prefix[HELLO_TASK].max-parallel-in-cluster=1")
        assertNull(single("HELLO_TASK").cron)
        assertFalse(summarised("HELLO_TASK"))
    }

    /** A nested setting is one key, not a task called `retry`. */
    fun testNestedSettingKeyIsRecognised() {
        configure("$prefix[HELLO_TASK].retry.fixed.delay=PT1S")
        assertNull(single("HELLO_TASK").cron)
    }

    /** Without a trailing setting there is no task here - `enabled` is a flag on the group. */
    fun testSingleSegmentUnderTaskPropertiesIsNotATask() {
        configure("$prefix.enabled=true")
        assertEmpty(sites("enabled"))
    }

    /**
     * One row per file rather than per line: a task is spread over as many lines as it has
     * settings, and the cron is the line the row is labelled with.
     */
    fun testTheCronLineWinsTheAnchorAmongSeveralSettings() {
        configure(
            """
            $prefix[HELLO_TASK].timeout=PT1H
            $prefix[HELLO_TASK].cron=0 0 1 * * *
            $prefix[HELLO_TASK].dlt-enabled=false
            """.trimIndent(),
        )
        val site = single("HELLO_TASK")
        assertEquals("0 0 1 * * *", site.cron)
        assertTrue(site.file.text.substring(site.offset).startsWith("$prefix[HELLO_TASK].cron="))
    }

    fun testWithoutACronTheFirstSettingLineIsTheAnchor() {
        configure(
            """
            $prefix[HELLO_TASK].timeout=PT1H
            $prefix[HELLO_TASK].dlt-enabled=false
            """.trimIndent(),
        )
        val site = single("HELLO_TASK")
        assertNull(site.cron)
        assertTrue(site.file.text.substring(site.offset).startsWith("$prefix[HELLO_TASK].timeout="))
    }

    /** A profile that blanks the cron out and then sets it must not hide the live one. */
    fun testDuplicateCronLinesPickTheLiveOne() {
        configure(
            """
            $prefix[HELLO_TASK].cron=
            $prefix[HELLO_TASK].cron=0 0 1 * * *
            """.trimIndent(),
        )
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
        assertTrue(summarised("HELLO_TASK"))
    }

    fun testSiteLandsOnItsOwnLine() {
        configure(
            """
            spring.application.name=demo
            $prefix[HELLO_TASK].cron=0 0 1 * * *
            """.trimIndent(),
        )
        val site = single("HELLO_TASK")
        val text = site.file.text
        assertTrue(text.substring(site.offset).startsWith(prefix))
    }

    private fun configure(text: String) {
        myFixture.addFileToProject("app/src/main/resources/application.properties", text)
    }

    private fun sites(taskName: String): List<TaskConfigSite> = ReadAction.compute<List<TaskConfigSite>, RuntimeException> {
        DtfTaskConfigSource.EP.extensionList
            .flatMap { it.findSites(project, taskName, GlobalSearchScope.projectScope(project)) }
    }

    private fun single(taskName: String): TaskConfigSite = sites(taskName).single()

    private fun summarised(taskName: String): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        DtfTaskConfigSource.EP.extensionList
            .any { it.summarise(project, taskName, GlobalSearchScope.projectScope(project))?.anyNonBlank == true }
    }
}
