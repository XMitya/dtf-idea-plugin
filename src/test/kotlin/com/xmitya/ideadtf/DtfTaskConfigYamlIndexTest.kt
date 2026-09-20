package com.xmitya.ideadtf

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.xmitya.ideadtf.config.CronSummary
import com.xmitya.ideadtf.config.DtfTaskConfigSource
import com.xmitya.ideadtf.config.TaskConfigSite

/**
 * The parsing layer, where the long tail of real configuration files lives.
 *
 * Goes through the extension point rather than the index class, so that it also covers the wiring:
 * an index registered in an optional descriptor is the one assumption the whole feature rests on.
 */
class DtfTaskConfigYamlIndexTest : DtfFixtureTestCase() {

    /** If the optional descriptor does not load, every other cron test fails for obscure reasons. */
    fun testYamlSourceIsRegistered() {
        assertTrue(
            "no cron config source registered - is the optional YAML descriptor loading?",
            DtfTaskConfigSource.EP.extensionList.isNotEmpty(),
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
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
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
        assertEquals("${'$'}{PURCHASE_AND_INVOICE_PAID_CRON}", single("HELLO_TASK").cron)
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
        assertEquals("", single("HELLO_TASK").cron)
        assertFalse(summary("HELLO_TASK")!!.anyNonBlank)
    }

    /** Settings without a cron: a place to navigate to, but not a reason to call the task scheduled. */
    fun testTaskWithoutCronKeyIsStillAConfigSite() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    max-parallel-in-cluster: 1
            """,
        )
        assertNull(single("HELLO_TASK").cron)
        assertNull(summary("HELLO_TASK"))
    }

    /** The three answers the index has to keep apart, in one file. */
    fun testMissingAndBlankCronAreDifferent() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  PLAIN_TASK:
                    timeout: PT1H
                  DISABLED_TASK:
                    cron: ''
                  LIVE_TASK:
                    cron: 0 0 1 * * *
            """,
        )
        assertNull(single("PLAIN_TASK").cron)
        assertEquals("", single("DISABLED_TASK").cron)
        assertEquals("0 0 1 * * *", single("LIVE_TASK").cron)
        assertNull(summary("PLAIN_TASK"))
        assertFalse(summary("DISABLED_TASK")!!.anyNonBlank)
        assertTrue(summary("LIVE_TASK")!!.anyNonBlank)
    }

    /** One value per key per file, so the document that actually schedules has to win the merge. */
    fun testMultiDocumentMergePrefersTheRealCron() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    timeout: PT1H
            ---
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    cron: 0 0 1 * * *
            """,
        )
        assertEquals("0 0 1 * * *", summary("HELLO_TASK")!!.sampleExpression)
        assertTrue(summary("HELLO_TASK")!!.anyNonBlank)
    }

    /** A bare key configures nothing; accepting it would mean accepting scalars and sequences too. */
    fun testEntryWithNoSettingsMappingIsNotASite() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
            """,
        )
        assertEmpty(sites("HELLO_TASK"))
    }

    /** The click opens the task's block, the same as it does for a cron. */
    fun testSiteAnchorsOnTheTaskEntryWithoutACron() {
        configure(
            """
            distributed-task:
              task-properties-group:
                task-properties:
                  HELLO_TASK:
                    max-parallel-in-cluster: 1
            """,
        )
        val site = single("HELLO_TASK")
        assertEquals("HELLO_TASK:", site.file.text.substring(site.offset, site.offset + 11))
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
        assertEquals("0 0/10 * ? * *", single("postponed-events-scheduled-check-task-v1").cron)
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
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
        assertEquals("0 0 2 * * *", single("OTHER_TASK").cron)
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
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
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
        assertEquals("0 0 1 * * *", single("HELLO_TASK").cron)
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
        assertEquals("0 0 * * * *", single("HELLO_TASK").cron)
    }

    fun testCommentedOutCronIsNotACron() {
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
        assertNull(single("HELLO_TASK").cron)
        assertNull(summary("HELLO_TASK"))
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
        // The task's own block is still a place to navigate to - what must not happen is the group
        // cron being read as this task's schedule.
        assertNull(single("HELLO_TASK").cron)
        assertNull(summary("HELLO_TASK"))
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

    private fun sites(taskName: String): List<TaskConfigSite> = ReadAction.compute<List<TaskConfigSite>, RuntimeException> {
        DtfTaskConfigSource.EP.extensionList
            .flatMap { it.findSites(project, taskName, GlobalSearchScope.projectScope(project)) }
    }

    private fun single(taskName: String): TaskConfigSite = sites(taskName).single()

    private fun summary(taskName: String) = ReadAction.compute<CronSummary?, RuntimeException> {
        DtfTaskConfigSource.EP.extensionList
            .firstNotNullOfOrNull { it.summarise(project, taskName, GlobalSearchScope.projectScope(project)) }
    }
}
