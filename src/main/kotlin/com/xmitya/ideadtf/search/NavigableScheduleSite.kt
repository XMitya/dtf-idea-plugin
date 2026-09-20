package com.xmitya.ideadtf.search

import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPsiElementPointer

/**
 * A schedule call site, prepared for display.
 *
 * Holds a smart pointer and an already-rendered presentation rather than live PSI, so that showing
 * the popup and navigating from it never touch PSI on the UI thread.
 */
class NavigableScheduleSite(pointer: SmartPsiElementPointer<*>, override val presentation: TargetPresentation, val tier: ScheduleTier) :
    PointerNavigatable(pointer),
    DtfPopupTarget
