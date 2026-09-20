package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.search.NavigableTaskConfigSite
import com.xmitya.ideadtf.search.TaskConfigSitePresenter

/** What the popup ends up showing for each configuration site. */
class DtfTaskConfigSitePresentationTest : DtfFixtureTestCase() {

    fun testPresentationLeadsWithTheExpressionAndLocation() {
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        val site = present().single()
        assertEquals("0 0 1 * * *", site.presentation.presentableText)
        assertEquals("application.yaml:4", site.presentation.locationText)
        assertTrue(site.active)
    }

    fun testDisabledCronIsLabelled() {
        addYaml("app/src/test/resources/application-test.yaml", "")
        val site = present().single()
        assertEquals(DtfBundle.message("dtf.cron.disabled"), site.presentation.presentableText)
        assertFalse(site.active)
    }

    /** A live schedule is what the reader is after; a switched-off profile sinks below it. */
    fun testLiveSchedulesSortAboveDisabledOnes() {
        addYaml("app/src/test/resources/application-test.yaml", "")
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        val sites = present()
        assertEquals(2, sites.size)
        assertTrue(sites.first().active)
        assertFalse(sites.last().active)
    }

    /** An ordinary task's block has no expression to lead with, so it says what it is instead. */
    fun testSettingsOnlyEntryIsLabelled() {
        addSettingsYaml("app/src/main/resources/application.yaml")
        val site = present().single()
        assertEquals(DtfBundle.message("dtf.config.settings"), site.presentation.presentableText)
        assertFalse(site.active)
    }

    /** For a cron task the two cron rows belong together, above the file that only tunes it. */
    fun testCronRowsSortAboveSettingsOnlyRows() {
        addSettingsYaml("app/src/main/resources/application-tuning.yaml")
        addYaml("app/src/test/resources/application-test.yaml", "")
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        assertEquals(
            listOf("0 0 1 * * *", DtfBundle.message("dtf.cron.disabled"), DtfBundle.message("dtf.config.settings")),
            present().map { it.presentation.presentableText },
        )
    }

    fun testContainerNamesTheSourceRoot() {
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        val container = present().single().presentation.containerText
        assertNotNull(container)
        assertTrue(container!!, container.contains("src/main/resources"))
    }

    private fun addYaml(path: String, cron: String) {
        myFixture.addFileToProject(
            path,
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: $cron
            """.trimIndent(),
        )
    }

    private fun addSettingsYaml(path: String) {
        myFixture.addFileToProject(
            path,
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    max-parallel-in-cluster: 1
            """.trimIndent(),
        )
    }

    private fun present(): List<NavigableTaskConfigSite> = ReadAction.compute<List<NavigableTaskConfigSite>, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        val found = DtfTaskConfigSource.EP.extensionList.flatMap { it.findSites(project, "HELLO_TASK", scope) }
        TaskConfigSitePresenter(project).present(found)
    }
}
