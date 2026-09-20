package com.xmitya.ideadtf

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowEdge
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode
import com.xmitya.ideadtf.flow.GatewayKind
import com.xmitya.ideadtf.flow.editor.DtfFlowCanvas
import com.xmitya.ideadtf.flow.editor.DtfFlowEdgeStyle
import com.xmitya.ideadtf.flow.editor.DtfFlowLayoutActions
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation
import com.xmitya.ideadtf.flow.layout.FlowPoint
import java.awt.Dimension
import java.awt.Point
import java.awt.image.BufferedImage

/**
 * What the diagram draws, and what a click on it does.
 *
 * Painting is exercised rather than excluded: the coverage gate counts every line, and a
 * `NullPointerException` halfway through `paintComponent` is exactly the kind of failure that only
 * shows up in a running IDE otherwise.
 */
class DtfFlowCanvasTest : DtfFlowFixtureTestCase() {

    private val canvas = DtfFlowCanvas()

    /** Every node kind, every edge kind, and the truncation banner, in one pass of the painter. */
    fun testEveryShapeCanBePainted() {
        canvas.setGraph(everyShape())

        paint()

        assertTrue(canvas.preferredSize.width > 0)
        assertTrue(canvas.preferredSize.height > 0)
    }

    fun testLabelledEdgeIsPaintedToo() {
        val left = task("Left", "LEFT")
        val right = task("Right", "RIGHT")
        canvas.setGraph(
            DtfFlowGraph(
                "labelled",
                listOf(left, right),
                listOf(DtfFlowEdge(left.id, right.id, DtfFlowEdgeKind.SCHEDULE, messageType = "OrderDto", condition = "paid")),
            ),
        )

        paint()
    }

    fun testHitTestingFindsTheBoxUnderThePointer() {
        canvas.setGraph(everyShape())

        val first = canvas.currentGraph().nodes.first()
        assertNotNull(canvas.nodeIdAt(centreOf(first)))
        assertNull(canvas.nodeIdAt(Point(-50, -50)))
    }

    /** Double-clicking a task box is how the diagram earns its place next to the tool window. */
    fun testNavigatingFromATaskBoxOpensTheTask() {
        addJavaTask("HelloTask", "HELLO")
        val graph = buildFlowOf("HelloTask")
        canvas.setGraph(graph)

        canvas.navigateTo(graph.nodes.single().id)

        assertEquals("HelloTask.java", openedFile()?.name)
    }

    fun testSelectedBoxIsOfferedAsANavigationTarget() {
        addJavaTask("HelloTask", "HELLO")
        val graph = buildFlowOf("HelloTask")
        canvas.setGraph(graph)
        canvas.size = canvas.preferredSize
        canvas.dispatchEvent(press(centreOf(graph.nodes.single())))

        val sink = RecordingDataSink()
        canvas.uiDataSnapshot(sink)

        assertEquals(1, sink[CommonDataKeys.NAVIGATABLE_ARRAY]?.size)
    }

    fun testZoomIsClampedAtBothEnds() {
        canvas.zoom = 99.0
        assertEquals(3.0, canvas.zoom)

        canvas.zoom = 0.01
        assertEquals(0.25, canvas.zoom)
    }

    fun testFitContentShrinksADiagramLargerThanItsViewport() {
        canvas.setGraph(everyShape())

        canvas.fitContent(Dimension(80, 40))

        assertTrue("expected a zoom below 1, got ${canvas.zoom}", canvas.zoom < 1.0)
    }

    /** Nothing to fit means nothing to change, rather than a division by zero. */
    fun testFitContentOnAnEmptyDiagramKeepsTheZoom() {
        canvas.setGraph(DtfFlowGraph.empty("empty"))

        canvas.fitContent(Dimension(100, 100))

        assertEquals(1.0, canvas.zoom)
    }

    /** Scale and fonts can change under an open tab, so the arrangement is remeasured. */
    fun testUpdatingTheLookAndFeelRemeasures() {
        canvas.setGraph(everyShape())
        val before = canvas.preferredSize

        canvas.updateUI()

        assertEquals(before, canvas.preferredSize)
    }

    fun testScrollingIncrementsAreSane() {
        val visible = java.awt.Rectangle(0, 0, 200, 100)

        assertTrue(canvas.getScrollableUnitIncrement(visible, javax.swing.SwingConstants.VERTICAL, 1) > 0)
        assertEquals(100, canvas.getScrollableBlockIncrement(visible, javax.swing.SwingConstants.VERTICAL, 1))
        assertEquals(200, canvas.getScrollableBlockIncrement(visible, javax.swing.SwingConstants.HORIZONTAL, 1))
        assertFalse(canvas.scrollableTracksViewportWidth)
        assertFalse(canvas.scrollableTracksViewportHeight)
        assertEquals(canvas.preferredSize, canvas.preferredScrollableViewportSize)
    }

    /** Both arrow styles have to paint, including the bridges the square one draws. */
    fun testBothEdgeStylesPaint() {
        canvas.setGraph(everyShape())

        canvas.edgeStyle = DtfFlowEdgeStyle.CURVED
        paint()
        assertEquals(DtfFlowEdgeStyle.CURVED, canvas.edgeStyle)

        canvas.edgeStyle = DtfFlowEdgeStyle.ORTHOGONAL
        paint()
        canvas.edgeStyle = DtfFlowEdgeStyle.ORTHOGONAL // setting the same one changes nothing
    }

    /** A crossing made by dragging has to be painted as a bridge rather than as a junction. */
    fun testADraggedCrossingIsPaintedWithABridge() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize
        val nodes = canvas.currentGraph().nodes

        canvas.moveNode(nodes[3].id, FlowPoint(0, 0))
        canvas.moveNode(nodes[4].id, FlowPoint(400, 400))
        paint()
    }

    /** The menu has to reflect what the diagram is doing, and change it when picked. */
    fun testTheLayoutMenuReflectsAndChangesTheDiagram() {
        canvas.setGraph(everyShape())
        val children = DtfFlowLayoutActions.group(canvas).getChildren(null)
        val toggles = children.filterIsInstance<com.intellij.openapi.actionSystem.ToggleAction>()

        val topToBottom = toggles.first { it.templatePresentation.text == DtfBundle.message("dtf.flow.layout.topToBottom") }
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(topToBottom)
        assertFalse(topToBottom.isSelected(event))

        topToBottom.setSelected(event, true)
        assertEquals(DtfFlowOrientation.TOP_TO_BOTTOM, canvas.orientation)
        assertTrue(topToBottom.isSelected(event))

        // Unticking a radio-style entry means nothing; some other entry is picked instead.
        topToBottom.setSelected(event, false)
        assertEquals(DtfFlowOrientation.TOP_TO_BOTTOM, canvas.orientation)

        val curved = toggles.first { it.templatePresentation.text == DtfBundle.message("dtf.flow.edges.curved") }
        curved.setSelected(com.intellij.testFramework.TestActionEvent.createTestEvent(curved), true)
        assertEquals(DtfFlowEdgeStyle.CURVED, canvas.edgeStyle)
    }

    /** Resetting is only worth offering once something has actually been moved. */
    fun testResetIsOfferedOnlyWhenThereIsSomethingToReset() {
        canvas.setGraph(everyShape())
        val reset = DtfFlowLayoutActions.group(canvas).getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first { it.templatePresentation.text == DtfBundle.message("dtf.flow.layout.reset") }

        val before = com.intellij.testFramework.TestActionEvent.createTestEvent(reset)
        reset.update(before)
        assertFalse(before.presentation.isEnabled)

        canvas.moveNode(canvas.currentGraph().nodes.first().id, FlowPoint(300, 300))
        val after = com.intellij.testFramework.TestActionEvent.createTestEvent(reset)
        reset.update(after)
        assertTrue(after.presentation.isEnabled)

        reset.actionPerformed(after)
        assertFalse(canvas.hasMovedBoxes)
    }

    private fun paint() {
        canvas.size = canvas.preferredSize
        val image = BufferedImage(
            canvas.preferredSize.width.coerceAtLeast(1),
            canvas.preferredSize.height.coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val graphics = image.createGraphics()
        try {
            canvas.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    private fun centreOf(node: DtfFlowNode): Point {
        canvas.size = canvas.preferredSize
        // Walk the diagram until the box is found; the exact coordinates are the layout's business.
        for (x in 0 until canvas.preferredSize.width step 4) {
            for (y in 0 until canvas.preferredSize.height step 4) {
                if (canvas.nodeIdAt(Point(x, y)) == node.id) return Point(x, y)
            }
        }
        return Point(0, 0)
    }

    private fun press(point: Point) = java.awt.event.MouseEvent(
        canvas,
        java.awt.event.MouseEvent.MOUSE_PRESSED,
        System.currentTimeMillis(),
        0,
        point.x,
        point.y,
        1,
        false,
    )

    private fun everyShape(): DtfFlowGraph {
        val caller = DtfFlowCallerNode("caller:1", "start", "Caller", "Caller.java:9", null)
        val timer = DtfFlowTimerNode("timer:1", "0 0 1 * * *", null)
        val cron = DtfFlowTaskNode("CronTask", "CRON", "CronTask", "CronTask", false, true, null)
        val left = task("Left", "LEFT")
        val right = task("Right", "RIGHT")
        val gateway = DtfFlowGatewayNode("join:1", GatewayKind.JOIN, approximate = true, target = null)
        val join = DtfFlowTaskNode("JoinTask", "JOIN", "JoinTask", "JoinTask", true, false, null)
        val unnamed = DtfFlowTaskNode("Opaque", null, "Opaque", "Opaque", false, false, null)
        return DtfFlowGraph(
            "everything",
            listOf(caller, timer, cron, left, right, gateway, join, unnamed),
            listOf(
                DtfFlowEdge(caller.id, left.id, DtfFlowEdgeKind.SCHEDULE),
                DtfFlowEdge(timer.id, cron.id, DtfFlowEdgeKind.TIMER),
                DtfFlowEdge(cron.id, right.id, DtfFlowEdgeKind.FORK),
                DtfFlowEdge(left.id, gateway.id, DtfFlowEdgeKind.JOIN_BRANCH),
                DtfFlowEdge(right.id, gateway.id, DtfFlowEdgeKind.JOIN_BRANCH),
                DtfFlowEdge(gateway.id, join.id, DtfFlowEdgeKind.GATEWAY_OUT),
                DtfFlowEdge(join.id, join.id, DtfFlowEdgeKind.SCHEDULE),
                DtfFlowEdge(join.id, unnamed.id, DtfFlowEdgeKind.IMMEDIATE),
            ),
            truncated = true,
        )
    }

    private fun task(id: String, name: String) = DtfFlowTaskNode(id, name, id, id, false, false, null)

    private fun openedFile(): VirtualFile? = FileEditorManager.getInstance(project).selectedEditor?.file

    /** Double-clicking a box is the whole point of the diagram being clickable. */
    fun testDoubleClickOnATaskBoxOpensIt() {
        addJavaTask("HelloTask", "HELLO")
        val graph = buildFlowOf("HelloTask")
        canvas.setGraph(graph)
        canvas.size = canvas.preferredSize

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_CLICKED, centreOf(graph.nodes.single()), clicks = 2))

        assertEquals("HelloTask.java", openedFile()?.name)
    }

    /** A single click is a selection, not a navigation. */
    fun testASingleClickDoesNotNavigate() {
        addJavaTask("HelloTask", "HELLO")
        val graph = buildFlowOf("HelloTask")
        canvas.setGraph(graph)
        canvas.size = canvas.preferredSize

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_CLICKED, centreOf(graph.nodes.single()), clicks = 1))

        assertNull(openedFile())
    }

    fun testDoubleClickOnEmptyCanvasDoesNothing() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_CLICKED, Point(-20, -20), clicks = 2))

        assertNull(openedFile())
    }

    /** Hovering a box explains it; hovering the gap explains nothing. */
    fun testHoveringABoxShowsItsTooltip() {
        canvas.setGraph(everyShape())
        val node = canvas.currentGraph().nodes.filterIsInstance<DtfFlowTaskNode>().first()

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_MOVED, centreOf(node)))
        assertEquals(node.qualifiedName, canvas.toolTipText)

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_MOVED, Point(-20, -20)))
        assertNull(canvas.toolTipText)
    }

    /** Dragging the background pans the view; it must not move anything. */
    fun testDraggingTheBackgroundPansWithoutMovingABox() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_PRESSED, Point(-20, -20)))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, Point(-40, -40)))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_RELEASED, Point(-40, -40)))

        assertFalse(canvas.hasMovedBoxes)
    }

    /** Releasing ends the drag, so moving the mouse afterwards must not keep dragging the box. */
    fun testAReleasedBoxStopsFollowingTheCursor() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize
        val node = canvas.currentGraph().nodes.first()
        val grab = centreOf(node)

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_PRESSED, grab))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, Point(grab.x + 40, grab.y)))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_RELEASED, Point(grab.x + 40, grab.y)))
        val settled = requireNotNull(canvas.positionOf(node.id))

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, Point(grab.x + 300, grab.y)))

        assertEquals(settled, canvas.positionOf(node.id))
    }

    /** Ctrl and the wheel zoom; the wheel on its own is the scroll pane's business. */
    fun testCtrlWheelZooms() {
        canvas.setGraph(everyShape())
        val before = canvas.zoom

        canvas.dispatchEvent(wheel(rotation = -1, withControl = true))
        assertTrue(canvas.zoom > before)

        canvas.dispatchEvent(wheel(rotation = 1, withControl = true))
        assertEquals(before, canvas.zoom, 0.0001)
    }

    fun testNavigatingToSomethingWithNoDeclarationIsHarmless() {
        canvas.setGraph(everyShape())

        canvas.navigateTo(canvas.currentGraph().nodes.first().id)
        canvas.navigateTo("no-such-node")

        assertNull(openedFile())
    }

    /** Dragging a box is the way out of a tangle no arrangement can untangle. */
    fun testDraggingABoxMovesIt() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize
        val node = canvas.currentGraph().nodes.first()
        val grab = centreOf(node)
        val before = canvas.positionOf(node.id)

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_PRESSED, grab))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, Point(grab.x + 120, grab.y + 90)))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_RELEASED, Point(grab.x + 120, grab.y + 90)))

        val after = requireNotNull(canvas.positionOf(node.id))
        assertTrue(canvas.hasMovedBoxes)
        assertEquals(requireNotNull(before).x + 120, after.x)
        assertEquals(before.y + 90, after.y)
    }

    /** The box follows the cursor by the amount dragged, not by jumping its corner to it. */
    fun testADraggedBoxKeepsTheGrabPoint() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize
        val node = canvas.currentGraph().nodes.first()
        val rect = requireNotNull(canvas.positionOf(node.id))
        val grab = Point(rect.x + 5, rect.y + 5)

        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_PRESSED, grab))
        canvas.dispatchEvent(mouse(java.awt.event.MouseEvent.MOUSE_DRAGGED, Point(grab.x + 50, grab.y)))

        assertEquals(rect.x + 50, requireNotNull(canvas.positionOf(node.id)).x)
    }

    fun testResettingPutsAMovedBoxBack() {
        canvas.setGraph(everyShape())
        canvas.size = canvas.preferredSize
        val node = canvas.currentGraph().nodes.first()
        val before = requireNotNull(canvas.positionOf(node.id))
        canvas.moveNode(node.id, FlowPoint(before.x + 200, before.y + 200))

        canvas.resetPositions()

        assertFalse(canvas.hasMovedBoxes)
        assertEquals(before, canvas.positionOf(node.id))
        canvas.resetPositions() // a second time is a no-op rather than a re-layout
    }

    /** A drag must never push a box off the top-left of the canvas, where it cannot be reached. */
    fun testABoxCannotBeDraggedOffTheCanvas() {
        canvas.setGraph(everyShape())
        val node = canvas.currentGraph().nodes.first()

        canvas.moveNode(node.id, FlowPoint(-500, -500))

        val moved = requireNotNull(canvas.positionOf(node.id))
        assertEquals(0, moved.x)
        assertEquals(0, moved.y)
    }

    fun testMovingSomethingThatIsNotThereIsHarmless() {
        canvas.setGraph(everyShape())

        canvas.moveNode("no-such-node", FlowPoint(10, 10))

        assertFalse(canvas.hasMovedBoxes)
    }

    /** Turning the flow rearranges it, and drops positions that only meant something the old way. */
    fun testChangingOrientationRearrangesAndForgetsMovedBoxes() {
        canvas.setGraph(everyShape())
        val node = canvas.currentGraph().nodes.first()
        canvas.moveNode(node.id, FlowPoint(400, 400))

        canvas.orientation = DtfFlowOrientation.TOP_TO_BOTTOM

        assertFalse(canvas.hasMovedBoxes)
        assertEquals(DtfFlowOrientation.TOP_TO_BOTTOM, canvas.orientation)
        canvas.orientation = DtfFlowOrientation.TOP_TO_BOTTOM // setting the same one changes nothing
        paint()
    }

    fun testTheLayoutMenuOffersEveryDirectionAndAReset() {
        val group = DtfFlowLayoutActions.group(canvas)
        val children = group.getChildren(null)

        assertEquals(DtfFlowOrientation.entries.size + DtfFlowEdgeStyle.entries.size + 3, children.size)
    }

    /**
     * The button mask is not decoration: `SwingUtilities.isLeftMouseButton` reads the modifiers, not
     * the button field, so an event without it is ignored by every drag the canvas has.
     */
    private fun mouse(id: Int, point: Point, clicks: Int = 1) = java.awt.event.MouseEvent(
        canvas,
        id,
        System.currentTimeMillis(),
        java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
        point.x,
        point.y,
        clicks,
        false,
        java.awt.event.MouseEvent.BUTTON1,
    )

    private fun wheel(rotation: Int, withControl: Boolean) = java.awt.event.MouseWheelEvent(
        canvas,
        java.awt.event.MouseWheelEvent.MOUSE_WHEEL,
        System.currentTimeMillis(),
        if (withControl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0,
        0,
        0,
        0,
        false,
        java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
        1,
        rotation,
    )
}
