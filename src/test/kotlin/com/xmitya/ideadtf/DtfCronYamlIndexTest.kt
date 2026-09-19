package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.cron.CronConfigSite
import com.xmitya.ideadtf.cron.DtfCronConfigSource

/**
 * The parsing layer, where the long tail of real configuration files lives.
 *
 * Goes through the extension point rather than the index class, so that it also covers the wiring:
 * an index registered in an optional descriptor is the one assumption the whole feature rests on.
 */
class DtfCronYamlIndexTest : DtfFixtureTestCase() {

    /** If the optional descriptor does not load, every other cron test fails for obscure reasons. */
    fun testYamlSourceIsRegistered() {
        assertTrue(
            "no cron config source registered - is the optional YAML descriptor loading?",
            DtfCronConfigSource.EP.extensionList.isNotEmpty(),
        )
    }

    fun testCronUnderTaskPropertiesIsFound() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    execution-guarantees: AT_LEAST_ONCE
                    cron: 0 0 1 * * *
            """,
        )
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
    }

    /** The common production shape: the schedule lives in a deploy variable, not in the file. */
    fun testPlaceholderCronIsKeptVerbatim() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: ${'$'}{PURCHASE_AND_INVOICE_PAID_CRON}
            """,
        )
        assertEquals("${'$'}{PURCHASE_AND_INVOICE_PAID_CRON}", single("HELLO_TASK").expression)
    }

    /** `hasCron()` is `StringUtils.hasText`, so a blank one is how a test profile switches it off. */
    fun testBlankCronIsASiteButNotASchedule() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: ''
            """,
        )
        assertEquals("", single("HELLO_TASK").expression)
        assertFalse(summary("HELLO_TASK")!!.anyNonBlank)
    }

    fun testTaskWithoutCronKeyIsNotConfigured() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    max-parallel-in-cluster: 1
            """,
        )
        assertEmpty(sites("HELLO_TASK"))
        assertNull(summary("HELLO_TASK"))
    }

    fun testKebabCaseTaskName() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  postponed-events-scheduled-check-task-v1:
                    cron: 0 0/10 * ? * *
            """,
        )
        assertEquals("0 0/10 * ? * *", single("postponed-events-scheduled-check-task-v1").expression)
    }

    fun testQuotedTaskNameKey() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  "HELLO_TASK":
                    cron: 0 0 1 * * *
                  'OTHER_TASK':
                    cron: 0 0 2 * * *
            """,
        )
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
        assertEquals("0 0 2 * * *", single("OTHER_TASK").expression)
    }

    /** Spring profiles in one file - the case the platform's own helper silently misses. */
    fun testMultiDocumentYamlFindsBothProfiles() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            ---
            spring:
              config:
                activate:
                  on-profile: local
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0/5 * * * *
            """,
        )
        assertEquals(2, sites("HELLO_TASK").size)
    }

    fun testRelaxedBindingOnThePathPrefix() {
        configure(
            """
            distributedTask:
              taskPropertiesGroup:
                taskProperties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            """,
        )
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
    }

    fun testCompressedDottedPrefixKey() {
        configure(
            """
            distributed-task.task-properties-group:
              task-properties:
                HELLO_TASK:
                  cron: 0 0 1 * * *
            """,
        )
        assertEquals("0 0 1 * * *", single("HELLO_TASK").expression)
    }

    fun testFlowMappingEntry() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK: { cron: "0 0 * * * *", max-parallel-in-cluster: 1 }
            """,
        )
        assertEquals("0 0 * * * *", single("HELLO_TASK").expression)
    }

    fun testCommentedOutCronIsNotConfiguration() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    execution-guarantees: AT_LEAST_ONCE
            #         cron: 0 0 1 * * *
            """,
        )
        assertEmpty(sites("HELLO_TASK"))
    }

    /** A repository full of unrelated YAML must cost nothing and find nothing. */
    fun testUnrelatedYamlIsNotIndexed() {
        configure(
            """
            apiVersion: batch/v1
            kind: CronJob
            metadata:
              name: HELLO_TASK
            spec:
              schedule: 0 0 1 * * *
            """,
        )
        assertEmpty(sites("HELLO_TASK"))
    }

    /** One task, three profiles - the shape every real service has. */
    fun testSameTaskInThreeFilesGivesThreeSites() {
        addYaml("app/src/main/resources/application.yaml", "0 0 1 * * *")
        addYaml("app/src/main/resources/application-local.yaml", "0 0/5 * * * *")
        addYaml("app/src/test/resources/application-test.yaml", "")
        assertEquals(3, sites("HELLO_TASK").size)
        assertTrue(summary("HELLO_TASK")!!.anyNonBlank)
    }

    /**
     * `default-properties` sits below `@TaskSchedule` in the framework's merge order, so honouring
     * it would put a clock on every task in the module.
     */
    fun testDefaultPropertiesCronDoesNotConfigureTasks() {
        configure(
            """
            distributed-task:
              task-properties-group:
                default-properties:
                  cron: 0 0 1 * * *
                task-properties:
                  HELLO_TASK:
                    max-parallel-in-cluster: 1
            """,
        )
        assertEmpty(sites("HELLO_TASK"))
    }

    private fun configure(yaml: String) {
        myFixture.addFileToProject("app/src/main/resources/application.yaml", yaml.trimIndent())
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

    private fun sites(taskName: String): List<CronConfigSite> = ReadAction.compute<List<CronConfigSite>, RuntimeException> {
        DtfCronConfigSource.EP.extensionList
            .flatMap { it.findSites(project, taskName, GlobalSearchScope.projectScope(project)) }
    }

    private fun single(taskName: String): CronConfigSite = sites(taskName).single()

    private fun summary(taskName: String) = ReadAction.compute<com.xmitya.ideadtf.cron.CronSummary?, RuntimeException> {
        DtfCronConfigSource.EP.extensionList
            .firstNotNullOfOrNull { it.summarise(project, taskName, GlobalSearchScope.projectScope(project)) }
    }
}
