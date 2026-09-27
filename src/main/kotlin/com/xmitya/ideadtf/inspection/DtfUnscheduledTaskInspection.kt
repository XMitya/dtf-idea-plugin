package com.xmitya.ideadtf.inspection

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.xmitya.ideadtf.DtfBundle
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskModel
import com.xmitya.ideadtf.search.TaskLaunch
import com.xmitya.ideadtf.search.TaskLaunchSearcher
import org.jetbrains.uast.UClass

/**
 * Warns on a task nothing ever schedules - the DTF counterpart of IDEA's "class is never used".
 *
 * An inspection rather than a line marker because a warning is what this is: it underlines the
 * class name, explains itself on hover, can be switched off or suppressed per class, and *Inspect
 * Code* lists every such task in the project at once.
 *
 * Registered for the `UAST` meta-language, which covers Java and Kotlin from a single inspection.
 *
 * Deliberately not `DumbAware`, like the line marker providers: the search behind it needs indexes.
 */
class DtfUnscheduledTaskInspection : AbstractBaseUastLocalInspectionTool(UClass::class.java) {

    override fun checkClass(aClass: UClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val taskClass = aClass.javaPsi
        if (!DtfTaskModel.isDtfPresent(taskClass.project) || !DtfTaskModel.isMarkableTask(taskClass)) return null
        // The class name, as in the gutter: that is what IDEA underlines for an unused class too.
        val anchor = aClass.uastAnchor?.sourcePsi ?: return null

        val key = when (TaskLaunchSearcher(taskClass.project).launchOf(taskClass)) {
            TaskLaunch.LAUNCHED -> return null
            TaskLaunch.ONLY_FROM_TESTS -> "dtf.inspection.unscheduled.tests"
            TaskLaunch.NEVER -> "dtf.inspection.unscheduled.never"
        }
        val taskName = DtfTaskDefResolver.resolveCached(taskClass).taskName ?: taskClass.name.orEmpty()
        val problem = manager.createProblemDescriptor(
            anchor,
            DtfBundle.message(key, taskName),
            isOnTheFly,
            LocalQuickFix.EMPTY_ARRAY,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        )
        return arrayOf(problem)
    }
}
