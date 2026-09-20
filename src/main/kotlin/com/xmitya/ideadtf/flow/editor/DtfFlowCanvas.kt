package com.xmitya.ideadtf.flow.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.pom.Navigatable
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
import com.xmitya.ideadtf.flow.DtfFlowNode
import com.xmitya.ideadtf.flow.DtfFlowTaskNode
import com.xmitya.ideadtf.flow.DtfFlowTimerNode
import com.xmitya.ideadtf.flow.layout.DtfFlowLayout
import com.xmitya.ideadtf.flow.layout.DtfFlowLayouter
import com.xmitya.ideadtf.flow.layout.FlowPoint
import com.xmitya.ideadtf.flow.layout.FlowRect
import com.xmitya.ideadtf.flow.layout.FlowSize
import com.xmitya.ideadtf.model.DtfRowText
import com.xmitya.ideadtf.model.DtfTextRun
import com.xmitya.ideadtf.model.DtfTextStyle
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
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
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

    private var graph: DtfFlowGraph = DtfFlowGraph.empty("")
    private var layout: DtfFlowLayout = DtfFlowLayout.EMPTY
    private var hoveredId: String? = null
    private var selectedId: String? = null

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
        addMouseListener(ClickHandler())
        addMouseMotionListener(ClickHandler())
        addMouseWheelListener { event -> if (event.isControlDown || event.isMetaDown) zoomBy(event) else parent?.dispatchEvent(event) }
    }

    /** Replaces what is drawn. EDT only. */
    fun setGraph(graph: DtfFlowGraph) {
        this.graph = graph
        this.hoveredId = null
        this.selectedId = null
        relayout()
    }

    fun currentGraph(): DtfFlowGraph = graph

    /** Public so that "a click here opens that" can be asserted without synthesising a mouse. */
    fun nodeIdAt(point: Point): String? = layout.nodeAt(toDiagram(point))

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
        // monitor is enough - and a cached arrangement would then be off by the scale factor.
        relayout()
    }

    private fun relayout() {
        layout = DtfFlowLayouter.layout(graph, graph.nodes.associate { it.id to measure(it) }, DtfFlowStyle.layoutStyle())
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

    private fun paintEdges(g2: Graphics2D) {
        g2.color = DtfFlowStyle.edgeColor
        for (route in layout.edges) {
            g2.stroke = if (route.edge.kind == DtfFlowEdgeKind.FORK || approximateJoin(route.edge.toId)) {
                DtfFlowStyle.dashedStroke()
            } else {
                DtfFlowStyle.edgeStroke()
            }
            route.points.zipWithNext().forEach { (from, to) -> g2.drawLine(from.x, from.y, to.x, to.y) }
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
        for (node in graph.nodes) {
            val rect = layout.nodes[node.id] ?: continue
            when (node) {
                is DtfFlowGatewayNode -> paintGateway(g2, node, rect)
                else -> paintBox(g2, node, rect)
            }
        }
    }

    private fun paintBox(g2: Graphics2D, node: DtfFlowNode, rect: FlowRect) {
        val radius = DtfFlowStyle.cornerRadius()
        g2.color = if (node.id == hoveredId) DtfFlowStyle.hoverBackground else DtfFlowStyle.boxBackground
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, radius, radius)

        val joinTarget = (node as? DtfFlowTaskNode)?.isJoinTarget == true
        g2.color = if (joinTarget) DtfFlowStyle.joinAccent else DtfFlowStyle.boxBorder
        g2.stroke = if (node is DtfFlowCallerNode) DtfFlowStyle.dashedStroke() else DtfFlowStyle.edgeStroke()
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
    private fun paintGateway(g2: Graphics2D, node: DtfFlowGatewayNode, rect: FlowRect) {
        val path = Path2D.Double()
        path.moveTo(rect.centerX.toDouble(), rect.y.toDouble())
        path.lineTo(rect.right.toDouble(), rect.centerY.toDouble())
        path.lineTo(rect.centerX.toDouble(), rect.bottom.toDouble())
        path.lineTo(rect.x.toDouble(), rect.centerY.toDouble())
        path.closePath()

        g2.color = if (node.id == hoveredId) DtfFlowStyle.hoverBackground else DtfFlowStyle.boxBackground
        g2.fill(path)
        g2.color = DtfFlowStyle.joinAccent
        g2.stroke = if (node.approximate) DtfFlowStyle.dashedStroke() else DtfFlowStyle.edgeStroke()
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

        override fun mousePressed(event: MouseEvent) {
            selectedId = nodeIdAt(event.point)
            panFrom = event.point.takeIf { selectedId == null && SwingUtilities.isLeftMouseButton(event) }
            repaint()
        }

        override fun mouseReleased(event: MouseEvent) {
            panFrom = null
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

    private fun zoomBy(event: MouseWheelEvent) {
        zoom *= if (event.wheelRotation < 0) ZOOM_STEP else 1 / ZOOM_STEP
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
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.1
    }
}
