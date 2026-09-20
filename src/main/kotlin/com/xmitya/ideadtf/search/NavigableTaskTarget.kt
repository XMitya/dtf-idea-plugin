package com.xmitya.ideadtf.search

import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPsiElementPointer

/**
 * A task class, prepared for display.
 *
 * The counterpart of [NavigableScheduleSite], and for the same reason: a smart pointer and an
 * already-rendered presentation rather than live PSI, so that showing the popup and navigating from
 * it never touch PSI on the UI thread.
 */
class NavigableTaskTarget(pointer: SmartPsiElementPointer<*>, override val presentation: TargetPresentation) :
    PointerNavigatable(pointer),
    DtfPopupTarget
