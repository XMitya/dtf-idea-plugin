package com.xmitya.ideadtf.search

import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPsiElementPointer

/**
 * A place a schedule is configured, prepared for display.
 *
 * Same shape as [NavigableScheduleSite] and for the same reason: a smart pointer and an
 * already-rendered presentation, so that showing the popup and navigating never touch PSI on the UI
 * thread.
 *
 * @param active whether the cron there actually schedules anything. A blank one disables it.
 */
class NavigableCronSite(pointer: SmartPsiElementPointer<*>, val presentation: TargetPresentation, val active: Boolean) :
    PointerNavigatable(pointer)
