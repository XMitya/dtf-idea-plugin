package com.xmitya.ideadtf.cron

import com.intellij.psi.PsiFile

/**
 * One place a task's schedule is configured.
 *
 * Carries a file and an offset rather than a [com.intellij.psi.PsiElement], so that a format with no
 * PSI of its own - `.properties`, read as plain text - produces the same thing YAML does.
 *
 * @param offset start of the line the task's settings are declared on, which is what the click
 *   navigates to.
 * @param expression the cron as written, placeholders and all. Empty means the key is present but
 *   blank, which the framework reads as "not scheduled" - `TaskSettings.hasCron()` is
 *   `StringUtils.hasText`.
 */
data class CronConfigSite(val file: PsiFile, val offset: Int, val expression: String)
