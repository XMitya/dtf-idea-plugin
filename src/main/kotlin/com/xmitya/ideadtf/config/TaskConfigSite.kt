package com.xmitya.ideadtf.config

import com.intellij.psi.PsiFile

/**
 * One place a task's settings are configured.
 *
 * Carries a file and an offset rather than a [com.intellij.psi.PsiElement], so that a format with no
 * PSI of its own - `.properties`, read as plain text - produces the same thing YAML does.
 *
 * @param offset start of the line the task's settings are declared on, which is what the click
 *   navigates to.
 * @param cron the cron as written, placeholders and all. Three answers rather than two: null means
 *   the entry declares no `cron` at all - an ordinary task configured for its timeout or its retries
 *   - while empty means the key is there and blank, which the framework reads as "not scheduled"
 *   (`TaskSettings.hasCron()` is `StringUtils.hasText`).
 */
data class TaskConfigSite(val file: PsiFile, val offset: Int, val cron: String?)
