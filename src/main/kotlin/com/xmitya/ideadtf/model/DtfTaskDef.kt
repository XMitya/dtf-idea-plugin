package com.xmitya.ideadtf.model

import com.intellij.psi.PsiElement

/**
 * What the plugin managed to learn about a task's [com.xmitya.ideadtf.DtfFqns.TASK_DEF].
 *
 * @param anchors declarations a "reference to this TaskDef" can resolve to. More than one because
 *   a Kotlin `val` is reachable as a light field, as the `KtProperty` behind it, and - from Java -
 *   as a generated getter; searching all of them and de-duplicating is the only way to catch both
 *   Java and Kotlin call sites.
 * @param taskName the task's identity string, when it could be read off the definition. Used for
 *   presentation only.
 */
data class DtfTaskDef(
    val anchors: List<PsiElement>,
    val taskName: String?,
) {
    val isResolved: Boolean get() = anchors.isNotEmpty()

    companion object {
        val EMPTY = DtfTaskDef(emptyList(), null)
    }
}
