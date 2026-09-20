package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.cron.DtfCronConfigSource
import com.xmitya.ideadtf.search.CronSitePresenter
import com.xmitya.ideadtf.search.NavigableCronSite

/** What the popup ends up showing for each configuration site. */
class DtfCronSitePresentationTest : DtfFixtureTestCase() {

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

    private fun present(): List<NavigableCronSite> = ReadAction.compute<List<NavigableCronSite>, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        val found = DtfCronConfigSource.EP.extensionList.flatMap { it.findSites(project, "HELLO_TASK", scope) }
        CronSitePresenter(project).present(found)
    }
}
