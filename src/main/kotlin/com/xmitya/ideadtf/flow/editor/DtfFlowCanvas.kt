package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.pom.Navigatable
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.Magnificator
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xmitya.ideadtf.DtfIcons
import com.xmitya.ideadtf.flow.DtfFlowCallerNode
import com.xmitya.ideadtf.flow.DtfFlowEdgeKind
import com.xmitya.ideadtf.flow.DtfFlowGatewayNode
import com.xmitya.ideadtf.flow.DtfFlowGraph
import com.xmitya.ideadtf.flow.DtfFlowHighlight
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowScope
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode
import com.xmitya.ideadtf.flow.layout.DtfFlowLayout
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.DtfFlowOrientation
import com.xmitya.ideadtf.flow.layout.FlowEdgeRoute
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowRect
import com.xmitya.ideadtf.flow.layout.FlowSize
import com.xmitya.ideadtf.model.DtfRowText
import com.xmitya.ideadtf.model.DtfTextRun
import com.xmitya.ideadtf.model.DtfTextStyle
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Paints a flow and answers clicks on it.
 *
 * Deliberately thin. Measuring text and converting between screen and diagram coordinates is all it
 * really owns: where the boxes go is [DtfFlowLayouter]'s decision, what a row says is
 * [DtfRowText]'s, and what a box leads to is a [Navigatable] the builder already attached.
 */
class DtfFlowCanvas :
    JComponent(),
    Scrollable,
    UiDataProvider {

    /** The graph as built. What is drawn - [graph] - is this less the calls from tests while those are hidden. */
    private var built: DtfFlowGraph = DtfFlowGraph.empty("")
    private var graph: DtfFlowGraph = built
    private var layout: DtfFlowLayout = DtfFlowLayout.EMPTY
    private var hoveredId: String? = null
    private var selectedId: String? = null
    private var highlight: DtfFlowHighlight? = null

    /** The box a highlight was asked for, so that it can be worked out again once what is drawn changes. */
    private var highlightedFrom: String? = null

    /** The boxes [showTests] takes off the diagram. */
    private var testCallIds: Set<String> = emptySet()

    /**
     * The background the IDE gives each box's file - green for a test, out of the box - looked up
     * once per graph rather than on every repaint of a few hundred boxes.
     */
    private var fileColors: Map<String, Color> = emptyMap()

    /**
     * Where a box's file colour comes from. Supplied by the panel, which has the project this needs;
     * the canvas itself stays a plain component a test can build without one.
     */
    var fileColorOf: (DtfFlowNode) -> Color? = { null }

    /** Whether a box stands for code in test sources. Supplied by the panel, as [fileColorOf] is. */
    var isInTests: (DtfFlowNode) -> Boolean = { false }

    /**
     * Whether calls made from tests are drawn.
     *
     * Tests call a task in ways production code never does - with every variant of its input, from a
     * dozen test classes - and in a module's diagram they can be most of the callers there are. Kept
     * across a rebuild, so that Refresh does not bring back what the reader chose to hide.
     */
    var showTests: Boolean = true
        set(value) {
            if (value == field) return
            field = value
            showVisible()
        }

    val hasTestCalls: Boolean get() = testCallIds.isNotEmpty()

    /** Boxes the reader has dragged. Everything else is still arranged around them. */
    private val pinned = LinkedHashMap<String, FlowPoint>()

    var edgeStyle: DtfFlowEdgeStyle = DtfFlowEdgeStyle.ORTHOGONAL
        set(value) {
            if (value == field) return
            field = value
            repaint()
        }

    var orientation: DtfFlowOrientation = DtfFlowOrientation.LEFT_TO_RIGHT
        set(value) {
            if (value == field) return
            field = value
            // Positions from the old direction mean nothing in the new one.
            pinned.clear()
            relayout()
        }

    var zoom: Double = 1.0
        set(value) {
            val clamped = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (clamped != field) {
                field = clamped
                revalidate()
                repaint()
            }
        }

    init {
        isOpaque = true
        // Set explicitly rather than inherited: a canvas that has not been added to a window yet has
        // neither, and both measuring and painting need them.
        font = JBFont.label()
        foreground = UIUtil.getLabelForeground()
        background = DtfFlowStyle.canvasBackground
        ToolTipManager.sharedInstance().registerComponent(this)
        // Focusable, so that Esc reaches the action that clears a highlight.
        isFocusable = true
        // One handler for both, not one each: a press starts a drag and a motion continues it, and
        // two instances would each hold half of that state and neither would ever act on it.
        val mouse = ClickHandler()
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener { event -> if (event.isControlDown || event.isMetaDown) zoomBy(event) else parent?.dispatchEvent(event) }
        // A trackpad pinch. The platform does the gesture itself - on macOS it shows a scaled snapshot
        // while the fingers move - and asks the view of the scroll pane under them for this once they
        // stop. Without it a pinch over the diagram does nothing at all.
        putClientProperty(Magnificator.CLIENT_PROPERTY_KEY, Magnificator { scale, at -> magnify(scale, at) })
    }

    /**
     * Adds the right-click menu, and Esc for clearing what it highlights.
     *
     * Not done in the constructor: building an action group needs the `ActionManager`, and a canvas
     * is a plain Swing component that should be constructible before any of that exists.
     */
    fun installPopupMenu(ownScope: DtfFlowScope? = null) {
        PopupHandler.installPopupMenu(this, DtfFlowLayoutActions.popupGroup(this, ownScope), POPUP_PLACE)
    }

    /** Replaces what is drawn. EDT only. */
    fun setGraph(graph: DtfFlowGraph) {
        built = graph
        hoveredId = null
        selectedId = null
        highlightedFrom = null
        pinned.clear()
        // Calling code only: the boxes that stand for a call - a caller, or the scheduleJoin a gateway
        // is. A task declared in test sources is still a task, and taking those off would leave the
        // diagram of a test module empty.
        testCallIds =
            graph.nodes.filter { (it is DtfFlowCallerNode || it is DtfFlowGatewayNode) && isInTests(it) }.mapTo(HashSet()) { it.id }
        fileColors = colorsOf(graph)
        showVisible()
    }

    /**
     * Draws what [built] and [showTests] call for, and lets go of whatever referred to a box that is
     * no longer drawn. Dragged positions are kept even for a hidden box, so that one brought back
     * returns to where it was put.
     */
    private fun showVisible() {
        graph = if (showTests) built else built.without(testCallIds)
        if (hoveredId?.let(graph::node) == null) hoveredId = null
        if (selectedId?.let(graph::node) == null) selectedId = null
        highlight = highlightedFrom?.let { DtfFlowHighlight.of(graph, it) }
        if (highlight == null) highlightedFrom = null
        relayout()
    }

    private fun colorsOf(graph: DtfFlowGraph): Map<String, Color> =
        graph.nodes.mapNotNull { node -> fileColorOf(node)?.let { node.id to it } }.toMap()

    /** Public so a test can assert what a box is painted with. */
    fun fileColorOfNode(nodeId: String): Color? = fileColors[nodeId]

    /** The box the last press landed on, a right-click's included - which is what the context menu acts on. */
    val selectedNodeId: String? get() = selectedId

    val hasHighlight: Boolean get() = highlight != null

    /**
     * Lights up the chain [nodeId] takes part in - everything leading to it and everything it leads
     * to - and dims the rest. Kept across a drag or a turn of the layout, since it names boxes
     * rather than places; a rebuild drops it, since the boxes may be gone.
     */
    fun highlightFlowOf(nodeId: String) {
        highlight = DtfFlowHighlight.of(graph, nodeId)
        highlightedFrom = nodeId.takeIf { highlight != null }
        repaint()
    }

    fun clearHighlight() {
        if (highlight == null) return
        highlight = null
        highlightedFrom = null
        repaint()
    }

    fun highlightedNodeIds(): Set<String> = highlight?.nodeIds.orEmpty()

    /** Puts every box back where the arrangement would have it. */
    fun resetPositions() {
        if (pinned.isEmpty()) return
        pinned.clear()
        relayout()
    }

    val hasMovedBoxes: Boolean get() = pinned.isNotEmpty()

    /** Moves one box, as dragging it does. Public so the behaviour can be driven from a test. */
    fun moveNode(nodeId: String, to: FlowPoint) {
        if (graph.node(nodeId) == null) return
        pinned[nodeId] = FlowPoint(to.x.coerceAtLeast(0), to.y.coerceAtLeast(0))
        relayout()
    }

    fun positionOf(nodeId: String): FlowPoint? = layout.nodes[nodeId]?.let { FlowPoint(it.x, it.y) }

    fun currentGraph(): DtfFlowGraph = graph

    /** Public so that "a click here opens that" can be asserted without synthesising a mouse. */
    fun nodeIdAt(point: Point): String? = layout.nodeAt(toDiagram(point))

    /**
     * Zooms by [factor] while keeping the part of the diagram under [anchor] - a point on the canvas,
     * such as the cursor - exactly where it is on screen. Zooming about the corner instead throws the
     * reader somewhere else in a large diagram with every step.
     */
    fun zoomAround(factor: Double, anchor: Point) {
        val viewport = parent as? JViewport
        val before = zoom
        zoom *= factor
        if (viewport == null || zoom == before) return
        val onScreen = Point(anchor.x - viewport.viewPosition.x, anchor.y - viewport.viewPosition.y)
        // Grown now rather than on the next layout pass, or the new position is clamped to the old size.
        viewport.viewSize = preferredSize
        val extent = viewport.extentSize
        val x = (anchor.x * zoom / before).roundToInt() - onScreen.x
        val y = (anchor.y * zoom / before).roundToInt() - onScreen.y
        viewport.viewPosition = Point(
            x.coerceIn(0, (preferredSize.width - extent.width).coerceAtLeast(0)),
            y.coerceIn(0, (preferredSize.height - extent.height).coerceAtLeast(0)),
        )
    }

    fun zoomIn() = zoomAround(BUTTON_STEP, visibleCentre())

    fun zoomOut() = zoomAround(1 / BUTTON_STEP, visibleCentre())

    private fun visibleCentre(): Point = visibleRect.let { Point(it.centerX.roundToInt(), it.centerY.roundToInt()) }

    /**
     * What a finished pinch asks for: zoom by [scale] about [at], and say where that point of the
     * diagram is now, so that the platform can scroll it back under the fingers.
     */
    fun magnify(scale: Double, at: Point): Point {
        val before = zoom
        zoom *= scale
        return Point((at.x * zoom / before).roundToInt(), (at.y * zoom / before).roundToInt())
    }

    fun fitContent(viewport: Dimension) {
        if (layout.size.width == 0 || layout.size.height == 0) return
        val horizontal = viewport.width.toDouble() / layout.size.width
        val vertical = viewport.height.toDouble() / layout.size.height
        zoom = minOf(horizontal, vertical, 1.0)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val target = selectedId?.let { graph.node(it) }?.target ?: return
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf<Navigatable>(target)
    }

    override fun updateUI() {
        super.updateUI()
        // Both the font and JBUIScale can change under an open tab - moving the window to another
        // monitor is enough - and a cached arrangement would then be off by the scale factor. A
        // theme change brings a different green for tests, too.
        fileColors = colorsOf(built)
        relayout()
    }

    private fun relayout() {
        layout = DtfFlowLayouter.layout(
            graph,
            graph.nodes.associate { it.id to measure(it) },
            DtfFlowStyle.layoutStyle(),
            orientation,
            pinned,
        )
        revalidate()
        repaint()
    }

    // --- measuring ------------------------------------------------------------------------------

    private fun measure(node: DtfFlowNode): FlowSize {
        if (node is DtfFlowGatewayNode) {
            val side = DtfFlowStyle.gatewaySize()
            return FlowSize(side, side)
        }
        val runs = runsOf(node)
        val textWidth = runs.sumOf { widthOf(it) }
        val width = DtfFlowStyle.horizontalPadding() * 2 + iconWidth() + DtfFlowStyle.iconGap() + textWidth
        val height = DtfFlowStyle.verticalPadding() * 2 + maxOf(lineHeight(), iconWidth())
        return FlowSize(width, height)
    }

    private fun runsOf(node: DtfFlowNode): List<DtfTextRun> = when (node) {
        is DtfFlowTaskNode -> DtfRowText.taskRuns(node.taskName, node.className, null)

        is DtfFlowCallerNode -> buildList {
            add(DtfTextRun(node.displayName, DtfTextStyle.LEAD))
            node.className?.let { add(DtfTextRun("  $it", DtfTextStyle.GREY)) }
        }

        is DtfFlowTimerNode -> listOf(DtfTextRun(node.expression ?: "cron", DtfTextStyle.LEAD))

        is DtfFlowGatewayNode -> emptyList()
    }

    private fun widthOf(run: DtfTextRun): Int = getFontMetrics(fontFor(run.style)).stringWidth(run.text)

    private fun lineHeight(): Int = getFontMetrics(fontFor(DtfTextStyle.LEAD)).height

    private fun iconWidth(): Int = DtfIcons.TaskGutter.iconWidth

    private fun fontFor(style: DtfTextStyle): Font = when (style) {
        DtfTextStyle.LEAD -> JBFont.label().asBold()
        DtfTextStyle.GREY -> JBFont.label()
    }

    // --- painting -------------------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            GraphicsUtil.setupAAPainting(g2)
            g2.scale(zoom, zoom)
            paintEdges(g2)
            paintNodes(g2)
        } finally {
            g2.dispose()
        }
    }

    /** Highlighted arrows last, so that no dimmed one is drawn across them. */
    private fun paintEdges(g2: Graphics2D) {
        val (lit, rest) = layout.edges.partition { highlight?.contains(it.edge) == true }
        dimmedIf(g2, highlight != null) { rest.forEach { paintEdge(g2, it, lit = false) } }
        lit.forEach { paintEdge(g2, it, lit = true) }
    }

    private fun paintEdge(g2: Graphics2D, route: FlowEdgeRoute, lit: Boolean) {
        val dashed = route.edge.kind == DtfFlowEdgeKind.FORK || approximateJoin(route.edge.toId)
        g2.color = if (lit) DtfFlowStyle.flowHighlight else DtfFlowStyle.edgeColor
        g2.stroke = strokeOf(lit, dashed)
        g2.draw(DtfFlowEdgePainter.pathOf(route.points, edgeStyle, route.hops, DtfFlowStyle.hopRadius()))
        val last = route.points.last()
        val previous = route.points[route.points.size - 2]
        paintArrowHead(g2, previous, last)
        route.labelSlot?.let { slot ->
            route.edge.label?.let { label ->
                g2.stroke = DtfFlowStyle.edgeStroke()
                g2.font = fontFor(DtfTextStyle.GREY)
                g2.drawString(label, slot.x, slot.y + slot.height)
            }
        }
    }

    private fun strokeOf(lit: Boolean, dashed: Boolean) = when {
        lit -> DtfFlowStyle.highlightStroke(dashed)
        dashed -> DtfFlowStyle.dashedStroke()
        else -> DtfFlowStyle.edgeStroke()
    }

    /** Paints what [paint] draws faded, when [dimmed]: everything a highlight does not take in. */
    private fun dimmedIf(g2: Graphics2D, dimmed: Boolean, paint: () -> Unit) {
        if (!dimmed) return paint()
        val previous = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DtfFlowStyle.DIMMED_ALPHA)
        try {
            paint()
        } finally {
            g2.composite = previous
        }
    }

    private fun approximateJoin(nodeId: String): Boolean = (graph.node(nodeId) as? DtfFlowGatewayNode)?.approximate == true

    private fun paintArrowHead(g2: Graphics2D, from: FlowPoint, to: FlowPoint) {
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val length = kotlin.math.hypot(dx, dy).takeIf { it > 0 } ?: return
        val ux = dx / length
        val uy = dy / length
        val head = DtfFlowStyle.arrowLength()
        val half = DtfFlowStyle.arrowHalfWidth()
        val baseX = to.x - ux * head
        val baseY = to.y - uy * head
        val path = Path2D.Double()
        path.moveTo(to.x.toDouble(), to.y.toDouble())
        path.lineTo(baseX - uy * half, baseY + ux * half)
        path.lineTo(baseX + uy * half, baseY - ux * half)
        path.closePath()
        g2.fill(path)
    }

    private fun paintNodes(g2: Graphics2D) {
        val highlight = highlight
        for (node in graph.nodes) {
            val rect = layout.nodes[node.id] ?: continue
            val lit = highlight != null && node.id in highlight.nodeIds
            dimmedIf(g2, highlight != null && !lit) {
                when (node) {
                    is DtfFlowGatewayNode -> paintGateway(g2, node, rect, lit)
                    else -> paintBox(g2, node, rect, lit)
                }
            }
        }
    }

    /** Hovering wins over the file colour, so the box under the cursor still shows that it is. */
    private fun fillOf(node: DtfFlowNode): Color =
        if (node.id == hoveredId) DtfFlowStyle.hoverBackground else fileColors[node.id] ?: DtfFlowStyle.boxBackground

    private fun paintBox(g2: Graphics2D, node: DtfFlowNode, rect: FlowRect, lit: Boolean) {
        val radius = DtfFlowStyle.cornerRadius()
        g2.color = fillOf(node)
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, radius, radius)

        val joinTarget = (node as? DtfFlowTaskNode)?.isJoinTarget == true
        g2.color = when {
            lit -> DtfFlowStyle.flowHighlight
            joinTarget -> DtfFlowStyle.joinAccent
            else -> DtfFlowStyle.boxBorder
        }
        g2.stroke = strokeOf(lit, dashed = node is DtfFlowCallerNode)
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, radius, radius)

        val icon = iconOf(node)
        val iconY = rect.centerY - icon.iconHeight / 2
        icon.paintIcon(this, g2, rect.x + DtfFlowStyle.horizontalPadding(), iconY)

        var x = rect.x + DtfFlowStyle.horizontalPadding() + icon.iconWidth + DtfFlowStyle.iconGap()
        val baseline = rect.centerY + lineHeight() / 2 - getFontMetrics(fontFor(DtfTextStyle.LEAD)).descent
        for (run in runsOf(node)) {
            g2.font = fontFor(run.style)
            g2.color = if (run.style == DtfTextStyle.GREY) DtfFlowStyle.greyText else foreground
            g2.drawString(run.text, x, baseline)
            x += widthOf(run)
        }
    }

    /** A diamond with a plus: BPMN's parallel gateway, dashed when its branches were inferred. */
    private fun paintGateway(g2: Graphics2D, node: DtfFlowGatewayNode, rect: FlowRect, lit: Boolean) {
        val path = Path2D.Double()
        path.moveTo(rect.centerX.toDouble(), rect.y.toDouble())
        path.lineTo(rect.right.toDouble(), rect.centerY.toDouble())
        path.lineTo(rect.centerX.toDouble(), rect.bottom.toDouble())
        path.lineTo(rect.x.toDouble(), rect.centerY.toDouble())
        path.closePath()

        g2.color = fillOf(node)
        g2.fill(path)
        g2.color = if (lit) DtfFlowStyle.flowHighlight else DtfFlowStyle.joinAccent
        g2.stroke = strokeOf(lit, dashed = node.approximate)
        g2.draw(path)

        val arm = rect.width / 4
        g2.stroke = DtfFlowStyle.edgeStroke()
        g2.drawLine(rect.centerX - arm, rect.centerY, rect.centerX + arm, rect.centerY)
        g2.drawLine(rect.centerX, rect.centerY - arm, rect.centerX, rect.centerY + arm)
    }

    private fun iconOf(node: DtfFlowNode) = when {
        node is DtfFlowTaskNode && node.isCron -> DtfIcons.CronGutter
        node is DtfFlowTaskNode -> DtfIcons.TaskGutter
        node is DtfFlowTimerNode -> DtfIcons.CronGutter
        else -> AllIcons.Nodes.Method
    }

    // --- interaction ----------------------------------------------------------------------------

    private inner class ClickHandler : MouseAdapter() {

        private var panFrom: Point? = null
        private var draggedId: String? = null

        /** Where inside the box the drag started, so it does not jump to the cursor. */
        private var grabOffset: FlowPoint = FlowPoint(0, 0)

        override fun mousePressed(event: MouseEvent) {
            requestFocusInWindow()
            val id = nodeIdAt(event.point)
            selectedId = id
            if (id != null && SwingUtilities.isLeftMouseButton(event)) {
                val rect = layout.nodes[id]
                val point = toDiagram(event.point)
                draggedId = id
                grabOffset = FlowPoint(point.x - (rect?.x ?: 0), point.y - (rect?.y ?: 0))
            }
            panFrom = event.point.takeIf { id == null && SwingUtilities.isLeftMouseButton(event) }
            repaint()
        }

        override fun mouseReleased(event: MouseEvent) {
            panFrom = null
            draggedId = null
        }

        override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount != 2) return
            navigateTo(nodeIdAt(event.point) ?: return)
        }

        override fun mouseMoved(event: MouseEvent) {
            val id = nodeIdAt(event.point)
            if (id != hoveredId) {
                hoveredId = id
                repaint()
            }
            toolTipText = id?.let { DtfFlowTooltip.of(graph, it) }
        }

        override fun mouseDragged(event: MouseEvent) {
            draggedId?.let { id ->
                val point = toDiagram(event.point)
                moveNode(id, FlowPoint(point.x - grabOffset.x, point.y - grabOffset.y))
                return
            }
            val from = panFrom ?: return
            val visible = visibleRect
            scrollRectToVisible(
                Rectangle(visible.x + (from.x - event.x), visible.y + (from.y - event.y), visible.width, visible.height),
            )
        }
    }

    /** Public so the navigation a double-click performs can be driven from a test. */
    fun navigateTo(nodeId: String) {
        val target = graph.node(nodeId)?.target ?: return
        if (target.canNavigate()) target.navigate(true)
    }

    /**
     * Ctrl or Cmd with the wheel, about the cursor. The precise rotation rather than whole notches,
     * so that the same with two fingers on a trackpad zooms smoothly instead of in jumps.
     */
    private fun zoomBy(event: MouseWheelEvent) {
        zoomAround(WHEEL_STEP.pow(-event.preciseWheelRotation), event.point)
    }

    private fun toDiagram(point: Point): FlowPoint = FlowPoint((point.x / zoom).roundToInt(), (point.y / zoom).roundToInt())

    // --- Scrollable -----------------------------------------------------------------------------

    override fun getPreferredSize(): Dimension = Dimension(
        (layout.size.width * zoom).roundToInt().coerceAtLeast(JBUI.scale(1)),
        (layout.size.height * zoom).roundToInt().coerceAtLeast(JBUI.scale(1)),
    )

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUIScale.scale(16)

    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == javax.swing.SwingConstants.HORIZONTAL) visible.width else visible.height

    override fun getScrollableTracksViewportWidth(): Boolean = false

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val POPUP_PLACE = "DtfFlowDiagramPopup"

        // Low enough for Fit Content to show a whole module at once, as an overview to zoom into.
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 3.0
        const val WHEEL_STEP = 1.1
        const val BUTTON_STEP = 1.2
    }
}
