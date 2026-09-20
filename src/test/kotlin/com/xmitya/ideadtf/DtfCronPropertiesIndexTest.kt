package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.cron.CronConfigSite
import com.xmitya.ideadtf.cron.DtfCronConfigSource

/** The flattened `.properties` form of the same configuration. */
class DtfCronPropertiesIndexTest : DtfFixtureTestCase() {

    private val prefix = "distributed-task.task-properties-group.task-properties"

    /** Brackets are the form that actually binds for an upper-case name. */
    fun testBracketNotationIsFound() {
        configure("$prefix[HELLO_TASK].cron=0 0 1 * * *")
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
    }

    /** The dotted form binds only for lower-case kebab names, which do occur. */
    fun testDottedNotationIsFound() {
        configure("$prefix.postponed-events-check-v1.cron=0 0/10 * ? * *")
        assertEquals("0 0/10 * ? * *", single("postponed-events-check-v1").expression)
    }

    fun testColonSeparatorIsAccepted() {
        configure("$prefix[HELLO_TASK].cron: 0 0 1 * * *")
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
    }

    fun testBlankCronIsASiteButNotASchedule() {
        configure("$prefix[HELLO_TASK].cron=")
        assertEquals("", single("HELLO_TASK").expression)
        assertFalse(summarised("HELLO_TASK"))
    }

    fun testCommentIsIgnored() {
        configure("#$prefix[HELLO_TASK].cron=0 0 1 * * *")
        assertEmpty(sites("HELLO_TASK"))
    }

    fun testOtherSettingsAreNotCron() {
        configure("$prefix[HELLO_TASK].max-parallel-in-cluster=1")
        assertEmpty(sites("HELLO_TASK"))
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

    private fun sites(taskName: String): List<CronConfigSite> = ReadAction.compute<List<CronConfigSite>, RuntimeException> {
        DtfCronConfigSource.EP.extensionList
            .flatMap { it.findSites(project, taskName, GlobalSearchScope.projectScope(project)) }
    }

    private fun single(taskName: String): CronConfigSite = sites(taskName).single()

    private fun summarised(taskName: String): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        DtfCronConfigSource.EP.extensionList
            .any { it.summarise(project, taskName, GlobalSearchScope.projectScope(project))?.anyNonBlank == true }
    }
}
