package com.xmitya.ideadtf

import com.intellij.ide.ui.IconMapperBean
import com.intellij.openapi.wm.ToolWindowEP
import com.xmitya.ideadtf.toolwindow.DtfTaskToolWindowFactory

/**
 * The descriptor wiring, which nothing else would catch.
 *
 * A typo in `plugin.xml` or in an icon path costs nothing at build time and shows up only as a
 * missing button in a running IDE, so it is asserted here instead.
 */
class DtfTaskToolWindowTest : DtfFixtureTestCase() {

    fun testToolWindowIsRegisteredWithTheExpectedWiring() {
        val bean = ToolWindowEP.EP_NAME.extensionList.single { it.id == DtfTaskToolWindowFactory.ID }
        assertEquals("com.xmitya.ideadtf.toolwindow.DtfTaskToolWindowFactory", bean.factoryClass)
        assertEquals("com.xmitya.ideadtf.DtfIcons.ToolWindow", bean.icon)
        assertEquals("left", bean.anchor)
    }

    fun testStripeButtonIsAvailableWhenDtfIsOnTheClasspath() {
        assertTrue(DtfTaskToolWindowFactory().shouldBeAvailable(project))
    }

    /** Loading the SVG rather than a "missing icon" placeholder is what the size proves. */
    fun testStripeIconLoadsAtTheClassicStripeSize() {
        assertEquals(13, DtfIcons.ToolWindow.iconWidth)
        assertEquals(13, DtfIcons.ToolWindow.iconHeight)
    }

    /**
     * The new UI asks for 20x20 and reaches it through the mapping file, so all three sizes and both
     * themes have to be present under the exact names the mapping names.
     */
    fun testNewUiIconVariantsAreShipped() {
        assertTrue(IconMapperBean.EP_NAME.extensionList.any { it.mappingFile == MAPPING_FILE })
        for (path in listOf(MAPPING_FILE) + ICON_PATHS) {
            assertNotNull("missing resource: $path", javaClass.getResourceAsStream("/$path"))
        }
    }

    /** The mapping is keyed on the new-UI path and points back at the classic one. */
    fun testMappingFilePointsAtTheClassicIcon() {
        val json = javaClass.getResourceAsStream("/$MAPPING_FILE")!!.reader().readText()
        assertTrue(json.contains("\"dtfToolWindow.svg\": \"icons/dtfToolWindow.svg\""))
    }

    private companion object {
        const val MAPPING_FILE = "DtfIconMappings.json"

        val ICON_PATHS = listOf(
            "icons/dtfToolWindow.svg",
            "icons/dtfToolWindow_dark.svg",
            "icons/expui/dtfToolWindow.svg",
            "icons/expui/dtfToolWindow_dark.svg",
            "icons/expui/dtfToolWindow@20x20.svg",
            "icons/expui/dtfToolWindow@20x20_dark.svg",
        )
    }
}
