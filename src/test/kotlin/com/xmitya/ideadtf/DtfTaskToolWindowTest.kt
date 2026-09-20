package com.xmitya.ideadtf

import com.intellij.ide.ui.IconMapperBean
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.treeStructure.Tree
import com.xmitya.ideadtf.toolwindow.DtfTaskToolWindowFactory
import com.xmitya.ideadtf.toolwindow.DtfTaskTreePanel
import java.util.concurrent.TimeUnit

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

    /**
     * Guards the `-Xjvm-default=all` compiler flag.
     *
     * Without it the Kotlin compiler materialises an override of every default method of
     * [ToolWindowFactory] - `getIcon`, `getAnchor`, `manage` - and the plugin verifier fails the
     * build on six internal-API usages that are not in the source at all. Nothing else notices
     * until CI runs the verifier.
     */
    fun testFactoryInheritsInterfaceDefaultsRatherThanRestatingThem() {
        val declared = DtfTaskToolWindowFactory::class.java.declaredMethods.map { it.name }
        for (inherited in listOf("getIcon", "getAnchor", "manage", "isApplicable", "isDoNotActivateOnStart")) {
            assertFalse("-Xjvm-default=all is missing: $inherited was materialised", inherited in declared)
        }
    }

    fun testStripeButtonIsAvailableWhenDtfIsOnTheClasspath() {
        assertTrue(DtfTaskToolWindowFactory().shouldBeAvailable(project))
    }

    /**
     * Off the EDT, with no read action in sight - which is how the platform actually calls it, from
     * the tool window initializer's coroutine. Calling it from the test thread proves nothing: the
     * EDT holds the write-intent lock and so hands the index access the read access it needs for
     * free. Without an explicit read action this throws, the platform logs "Cannot process toolwindow
     * DTF Tasks", and the tool window is not registered at all.
     */
    fun testStripeAvailabilityIsComputedWithoutAnAmbientReadAction() {
        val available = ApplicationManager.getApplication()
            .executeOnPooledThread<Boolean> { DtfTaskToolWindowFactory().shouldBeAvailable(project) }
            .get(1, TimeUnit.MINUTES)

        assertTrue(available)
    }

    /**
     * Opening the tool window: one content, focusing the tree, and a scan started on sight.
     *
     * The window is built on first open, so a mistake in here - content added to the wrong manager,
     * a listener on a disposable that is already gone - surfaces only when someone clicks the stripe
     * button, and never in a test that drives the panel directly.
     */
    fun testOpeningTheToolWindowShowsTheTreeAndStartsAScan() {
        val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(DtfTaskToolWindowFactory.ID) {
            anchor = ToolWindowAnchor.LEFT
        }

        DtfTaskToolWindowFactory().createToolWindowContent(project, toolWindow)

        val content = toolWindow.contentManager.contents.single()
        assertTrue(content.component is DtfTaskTreePanel)
        assertSame((content.component as DtfTaskTreePanel).preferredFocusComponent, content.preferredFocusableComponent)
        // `refreshIfStale` on an untouched panel always scans, and a started scan says so.
        assertEquals(DtfBundle.message("dtf.toolwindow.progress"), (content.component as DtfTaskTreePanel).emptyText())
    }

    /**
     * The sizes the stripe asks for, read from the files.
     *
     * `Icon.getIconWidth` was the obvious thing to assert and is the wrong one. It answers 1 - the
     * platform's 1x1 `EMPTY_ICON` - whenever the icon is not resolvable in that JVM at that moment,
     * and whether it is depends on which test class last left the icon manager activated. That is
     * global state this test does not own, and it is why the assertion passed locally and failed on
     * CI. The claim worth making is that the classic stripe gets 13x13 and the new UI 20x20, and
     * that is a property of the files.
     */
    fun testIconsDeclareTheSizesTheStripeAsksFor() {
        assertEquals(13, declaredSizeOf("icons/dtfToolWindow.svg"))
        assertEquals(13, declaredSizeOf("icons/dtfToolWindow_dark.svg"))
        assertEquals(16, declaredSizeOf("icons/expui/dtfToolWindow.svg"))
        assertEquals(16, declaredSizeOf("icons/expui/dtfToolWindow_dark.svg"))
        assertEquals(20, declaredSizeOf("icons/expui/dtfToolWindow@20x20.svg"))
        assertEquals(20, declaredSizeOf("icons/expui/dtfToolWindow@20x20_dark.svg"))
    }

    private fun declaredSizeOf(path: String): Int {
        val svg = javaClass.getResourceAsStream("/$path")!!.reader().readText()
        val width = SIZE.find(svg, svg.indexOf("width="))
        val height = SIZE.find(svg, svg.indexOf("height="))
        assertNotNull("no width in $path", width)
        assertNotNull("no height in $path", height)
        // A stripe icon that is not square is a stretched one, which no assertion elsewhere catches.
        assertEquals("$path is not square", width!!.groupValues[1], height!!.groupValues[1])
        return width.groupValues[1].toInt()
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

    private fun DtfTaskTreePanel.emptyText() = (preferredFocusComponent as Tree).emptyText.text

    private companion object {
        const val MAPPING_FILE = "DtfIconMappings.json"

        val SIZE = Regex("""(?:width|height)="(\d+)"""")

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
