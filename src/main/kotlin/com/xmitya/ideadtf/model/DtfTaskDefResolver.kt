package com.xmitya.ideadtf.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.InheritanceUtil
import com.xmitya.ideadtf.DtfFqns
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.nonStructuralChildren
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Works out which `TaskDef` a task class is identified by, by reading its `getDef()`.
 *
 * Handles the declaration shapes that occur in practice: a constant in the task class, a constant
 * in a separate holder (class, interface or Kotlin `object`), and a Kotlin `companion object` val -
 * with either a block body or an expression body, and with or without a declared return type.
 */
object DtfTaskDefResolver {

    fun resolve(psiClass: PsiClass): DtfTaskDef {
        val getDef = findGetDefMethod(psiClass) ?: return DtfTaskDef.EMPTY
        val uMethod = asUast<UMethod>(getDef) ?: return DtfTaskDef.EMPTY

        val anchors = LinkedHashSet<PsiElement>()
        var taskName: String? = null

        for (expression in returnedExpressions(uMethod)) {
            // getDef() building the definition inline: nothing to search for, but the name is
            // still worth showing.
            val inlineCall = asCall(expression)
            if (inlineCall != null) {
                if (taskName == null) taskName = taskNameOf(inlineCall)
                continue
            }
            val resolved = (expression as? UReferenceExpression)?.resolve() ?: continue
            if (resolved === getDef) continue // getDef() delegating to itself
            if (!isTaskDefDeclaration(resolved)) continue
            anchors += anchorsFor(resolved)
            if (taskName == null) taskName = taskNameOf(resolved)
        }
        return if (anchors.isEmpty() && taskName == null) DtfTaskDef.EMPTY
        else DtfTaskDef(anchors.toList(), taskName)
    }

    /**
     * The `getDef()` that actually has a body: the class's own override, or an inherited one when
     * the definition lives in a base class.
     */
    fun findGetDefMethod(psiClass: PsiClass): PsiMethod? =
        psiClass.findMethodsByName(DtfFqns.GET_DEF, true)
            .firstOrNull { !it.hasModifierProperty(PsiModifier.ABSTRACT) && it.parameterList.isEmpty }

    /**
     * Every expression `getDef()` can hand back.
     *
     * Java uses real `return` statements; a Kotlin expression body is an implicit return.
     * [nonStructuralChildren] then unwraps blocks, parentheses and conditionals, so a
     * `getDef() = if (x) A else B` contributes both branches.
     */
    private fun returnedExpressions(uMethod: UMethod): List<UExpression> {
        val body = uMethod.uastBody ?: return emptyList()
        val returned = mutableListOf<UExpression>()
        body.accept(object : AbstractUastVisitor() {
            override fun visitReturnExpression(node: UReturnExpression): Boolean {
                node.returnExpression?.let { returned += it }
                return false
            }
        })
        if (returned.isEmpty()) returned += body
        return returned.flatMap { nonStructuralChildren(it).toList() }
    }

    /**
     * A declaration that holds a `TaskDef`.
     *
     * Java gives a [PsiField]. Reading a Kotlin `val` resolves to its generated accessor instead,
     * so a no-argument method returning a `TaskDef` counts too.
     */
    private fun isTaskDefDeclaration(element: PsiElement): Boolean = when (element) {
        is PsiField -> InheritanceUtil.isInheritor(element.type, DtfFqns.TASK_DEF)
        is PsiMethod -> element.parameterList.isEmpty &&
            element.returnType?.let { InheritanceUtil.isInheritor(it, DtfFqns.TASK_DEF) } == true
        else -> false
    }

    /**
     * All declarations a reference to [resolved] can resolve to.
     *
     * A Kotlin `val` in a `companion object` resolves to a light [PsiField], but Kotlin's own
     * reference search works off the `KtProperty` behind it, and Java call sites go through the
     * generated getter. Searching all three and de-duplicating covers every direction without
     * depending on the Kotlin plugin.
     */
    fun anchorsFor(resolved: PsiElement): List<PsiElement> {
        val anchors = LinkedHashSet<PsiElement>()
        anchors += resolved
        // For Kotlin this is the KtProperty; Kotlin's reference search expands it back to the light
        // elements, which is what catches Java call sites.
        resolved.navigationElement
            ?.takeIf { it !== resolved && it.isValid }
            ?.let { anchors += it }

        when (resolved) {
            is PsiField -> {
                val getter = "get" + resolved.name.replaceFirstChar { it.uppercaseChar() }
                resolved.containingClass
                    ?.findMethodsByName(getter, false)
                    ?.filter { it.parameterList.isEmpty }
                    ?.forEach { anchors += it }
            }
            is PsiMethod -> {
                // The backing field of a companion-object val lives on the outer class, not the
                // companion, so both are worth a look.
                val fieldName = resolved.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                val owner = resolved.containingClass
                listOfNotNull(owner, owner?.containingClass)
                    .mapNotNull { it.findFieldByName(fieldName, false) ?: it.findFieldByName(resolved.name.removePrefix("get"), false) }
                    .forEach { anchors += it }
            }
        }
        return anchors.toList()
    }

    /**
     * Digs the call out of an expression.
     *
     * `TaskDef.privateTaskDef(...)` is a qualified reference in UAST, with the call as its
     * selector, so the call has to be unwrapped rather than matched directly.
     */
    private fun asCall(expression: UExpression): UCallExpression? = when (expression) {
        is UCallExpression -> expression
        is UQualifiedReferenceExpression -> asCall(expression.selector)
        is UParenthesizedExpression -> asCall(expression.expression)
        else -> null
    }

    /**
     * Converts to UAST, preferring the source declaration.
     *
     * A Kotlin light method or light field converts to a lazy stub whose body is empty; only the
     * `KtNamedFunction` / `KtProperty` behind it carries the real expressions. For Java the
     * navigation element is the declaration itself, so this is a no-op there.
     */
    private inline fun <reified T : UElement> asUast(declaration: PsiElement): T? {
        val source = declaration.navigationElement?.takeIf { it.isValid }
        return source?.toUElementOfType<T>() ?: declaration.toUElementOfType<T>()
    }

    /** Reads the task name out of the initializer of a `TaskDef` constant. */
    private fun taskNameOf(declaration: PsiElement): String? {
        val initializer = asUast<UVariable>(declaration)?.uastInitializer ?: return null
        return nonStructuralChildren(initializer)
            .toList()
            .firstNotNullOfOrNull { child -> asCall(child)?.let { taskNameOf(it) } }
    }

    /**
     * Reads the task name out of a `TaskDef.privateTaskDef(...)` / `publicTaskDef(...)` call.
     *
     * The name is argument 0 of the private factories and argument 1 of the public ones, which take
     * the application name first.
     */
    private fun taskNameOf(call: UCallExpression): String? {
        val method = call.resolve() ?: return null
        if (method.containingClass?.qualifiedName != DtfFqns.TASK_DEF) return null
        val nameIndex = when (method.name) {
            "privateTaskDef" -> 0
            "publicTaskDef" -> 1
            else -> return null
        }
        return call.getArgumentForParameter(nameIndex)?.evaluateString()
    }
}
