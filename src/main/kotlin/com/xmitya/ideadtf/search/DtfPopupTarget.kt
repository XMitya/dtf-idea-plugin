package com.xmitya.ideadtf.search

import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable

/**
 * A row in one of this plugin's "go to target" popups: something to open, and the label to open it
 * by.
 *
 * Not folded into [PointerNavigatable], although every implementation extends it: a tool window row
 * and a diagram box navigate the same way and are drawn by something other than a
 * [TargetPresentation], so the two ideas are kept apart. What this interface buys is a popup that
 * can list results of more than one kind - a task's schedule calls beside the places it is
 * configured.
 */
interface DtfPopupTarget : Navigatable {
    val presentation: TargetPresentation
}
