package com.xmitya.ideadtf

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.xmitya.ideadtf.toolwindow.DtfTaskToolWindowFactory

/**
 * The other half of the availability rule, and the reason this does not extend [DtfFixtureTestCase]:
 * that base stubs the DTF API into every project, which is exactly the condition being ruled out
 * here. A plugin that shows its button in every project is a plugin people uninstall.
 */
class DtfToolWindowWithoutDtfTest : LightJavaCodeInsightFixtureTestCase() {

    fun testStripeButtonIsHiddenWhenDtfIsAbsent() {
        assertFalse(DtfTaskToolWindowFactory().shouldBeAvailable(project))
    }
}
