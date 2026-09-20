package com.xmitya.ideadtf

import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.LineMarkerSettings
import com.xmitya.ideadtf.marker.DtfScheduleCallLineMarkerProvider
import com.xmitya.ideadtf.marker.DtfTaskLineMarkerProvider

/**
 * What Settings | Editor | General | Gutter Icons shows, and what it remembers.
 *
 * The id is the key the user's on/off choice is stored under, so renaming the class must not move
 * it - and an id shared by the two providers would wire their checkboxes together. Nothing else
 * notices either mistake: both look correct until someone switches an icon off and the wrong one
 * disappears, or until an update silently turns a disabled icon back on.
 */
class DtfLineMarkerDescriptorTest : DtfFixtureTestCase() {

    fun testTheTaskIconIsDescribedForTheSettingsList() {
        val provider = DtfTaskLineMarkerProvider()
        assertEquals("com.xmitya.ideadtf.taskGutter", provider.id)
        assertEquals(DtfBundle.message("dtf.gutter.name"), provider.name)
        assertSame(DtfIcons.TaskGutter, provider.icon)
    }

    fun testTheScheduleIconIsDescribedForTheSettingsList() {
        val provider = DtfScheduleCallLineMarkerProvider()
        assertEquals("com.xmitya.ideadtf.scheduleGutter", provider.id)
        assertEquals(DtfBundle.message("dtf.gutter.schedule.name"), provider.name)
        assertSame(DtfIcons.ScheduleGutter, provider.icon)
    }

    /** Two ids, or the two checkboxes become one. */
    fun testTheTwoProvidersAreRememberedSeparately() {
        assertFalse(DtfTaskLineMarkerProvider().id == DtfScheduleCallLineMarkerProvider().id)
    }

    /** Both are on out of the box: an icon nobody can find is the same as no icon. */
    fun testBothIconsAreEnabledByDefault() {
        val settings = LineMarkerSettings.getSettings()
        for (provider in listOf<LineMarkerProviderDescriptor>(DtfTaskLineMarkerProvider(), DtfScheduleCallLineMarkerProvider())) {
            assertTrue(provider.id, settings.isEnabled(provider))
        }
    }
}
