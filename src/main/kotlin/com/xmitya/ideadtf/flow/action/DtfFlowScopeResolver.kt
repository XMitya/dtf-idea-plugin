package com.xmitya.ideadtf.flow.action

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.marker.DtfScheduleMarkers
import com.xmitya.ideadtf.marker.DtfTaskMarkers
import com.xmitya.ideadtf.model.DtfTaskDefResolver
import com.xmitya.ideadtf.model.DtfTaskModel
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElementOfType

/**
 * What a flow diagram would be of, given wherever the action was invoked from.
 *
 * One resolver rather than an action per invocation site: the four contexts - the tool window tree,
 * the Project view, the gutter icon and the editor - differ only in which key holds the answer, and a
 * second action class would be a second `update()` to keep honest.
 *
 * Resolves PSI, so it belongs on a background thread inside a read action.
 */
object DtfFlowScopeResolver {

    fun from(context: DataContext, project: Project): DtfFlowScope? {
        if (!DtfTaskModel.isDtfPresent(project)) return null

        DtfFlowDataKeys.FLOW_SCOPE.getData(context)?.let { return it }
        LangDataKeys.MODULE_CONTEXT.getData(context)?.let { return DtfFlowScope.Module(it.name, it.name) }

        CommonDataKeys.PSI_ELEMENT.getData(context)?.let { element ->
            scopeOfCall(element)?.let { return it }
            taskClassOf(element)?.let { return scopeOf(it) }
        }
        CommonDataKeys.PSI_FILE.getData(context)?.let { file ->
            scopeOfCaret(file, context)?.let { return it }
            topLevelTaskOf(file)?.let { return scopeOf(it) }
        }
        return null
    }

    fun scopeOf(taskClass: PsiClass): DtfFlowScope? {
        val qualifiedName = taskClass.qualifiedName ?: return null
        val title = DtfTaskDefResolver.resolveCached(taskClass).taskName ?: taskClass.name ?: qualifiedName
        return DtfFlowScope.Task(qualifiedName, title)
    }

    /** Right-clicking a `schedule(...)` call asks about the flow *that* call starts. */
    private fun scopeOfCall(element: PsiElement): DtfFlowScope? {
        val call = DtfScheduleMarkers.scheduleCallAt(element) ?: return null
        val psi = call.sourcePsi ?: return null
        val url = psi.containingFile?.virtualFile?.url ?: return null
        return DtfFlowScope.Call(url, psi.textRange.startOffset, psi.text.takeWhile { it != '(' })
    }

    private fun scopeOfCaret(file: PsiFile, context: DataContext): DtfFlowScope? {
        val offset = CommonDataKeys.CARET.getData(context)?.offset ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        scopeOfCall(leaf)?.let { return it }
        return DtfTaskMarkers.taskClassAt(leaf)?.let { scopeOf(it) }
    }

    private fun taskClassOf(element: PsiElement): PsiClass? {
        val psiClass = element as? PsiClass ?: element.toUElementOfType<UClass>()?.javaPsi ?: return null
        return psiClass.takeIf { DtfTaskModel.isMarkableTask(it) }
    }

    private fun topLevelTaskOf(file: PsiFile): PsiClass? = file.children
        .mapNotNull { it.toUElementOfType<UClass>()?.javaPsi ?: it as? PsiClass }
        .firstOrNull { DtfTaskModel.isMarkableTask(it) }
}
