package com.xmitya.ideadtf.flow.editor

import com.xmitya.ideadtf.flow.layout.FlowPoint
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot

/** How an arrow is drawn between the points the arrangement gave it. */
enum class DtfFlowEdgeStyle {
    /** Straight runs and square corners, which is what makes a rank structure readable. */
    ORTHOGONAL,

    /** One smooth sweep from box to box, easier to follow across a crowded picture. */
    CURVED,
}

/**
 * Turns a routed arrow into a shape.
 *
 * Kept out of the canvas because it is the fiddly half of the drawing and none of it needs a
 * component: a list of points in, a [Path2D] out, which is a thing a test can measure.
 */
object DtfFlowEdgePainter {

    fun pathOf(points: List<FlowPoint>, style: DtfFlowEdgeStyle, hops: List<FlowPoint>, hopRadius: Int): Path2D.Double {
        val path = Path2D.Double()
        if (points.size < 2) return path
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        // Hops only make sense on straight runs: a curve already has a direction of its own, and a
        // semicircle bolted onto one reads as a kink rather than as a bridge.
        if (style == DtfFlowEdgeStyle.CURVED) appendCurves(path, points) else appendSegments(path, points, hops, hopRadius)
        return path
    }

    // --- orthogonal, with bridges over anything crossed ------------------------------------------

    private fun appendSegments(path: Path2D.Double, points: List<FlowPoint>, hops: List<FlowPoint>, hopRadius: Int) {
        for ((from, to) in points.zipWithNext()) {
            val onThis = hops.filter { isOn(it, from, to) }.sortedBy { distance(from, it) }
            var previous: FlowPoint? = null
            for (hop in onThis) {
                // Two bridges closer than one is wide would overlap, and the second would start behind
                // the end of the first - drawing the line back under it. One bridge says "crossing" as
                // well as two: this happens where arrows side by side are crossed at once.
                if (previous != null && distance(previous, hop) < 2 * hopRadius) continue
                appendHop(path, hop, from, to, hopRadius)
                previous = hop
            }
            path.lineTo(to.x.toDouble(), to.y.toDouble())
        }
    }

    /**
     * A half-circle over the crossing, entered and left along the segment.
     *
     * Appending with `connect` draws the straight run up to the arc for us, so the only thing to get
     * right is which way round the arc goes - it has to bulge away from the line, and start at the
     * side the arrow arrives from.
     */
    private fun appendHop(path: Path2D.Double, hop: FlowPoint, from: FlowPoint, to: FlowPoint, radius: Int) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val (start, extent) = when {
            abs(dx) >= abs(dy) && dx >= 0 -> 180.0 to -180.0
            abs(dx) >= abs(dy) -> 0.0 to 180.0
            dy >= 0 -> 90.0 to -180.0
            else -> 270.0 to 180.0
        }
        val arc = Arc2D.Double(
            (hop.x - radius).toDouble(),
            (hop.y - radius).toDouble(),
            (radius * 2).toDouble(),
            (radius * 2).toDouble(),
            start,
            extent,
            Arc2D.OPEN,
        )
        path.append(arc, true)
    }

    /** Whether [point] lies on the segment, give or take rounding. */
    private fun isOn(point: FlowPoint, from: FlowPoint, to: FlowPoint): Boolean {
        val whole = distance(from, to)
        if (whole == 0.0) return false
        return distance(from, point) + distance(point, to) <= whole + ON_SEGMENT_SLACK
    }

    private fun distance(from: FlowPoint, to: FlowPoint): Double = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())

    // --- curved ---------------------------------------------------------------------------------

    /**
     * One cubic from end to end, leaving and arriving along the directions the arrangement chose.
     *
     * The elbow points are deliberately not drawn through: they exist to make a square corner, and a
     * curve that visits them is a curve with corners in it. What they are kept for is the tangents -
     * which is what makes a curved arrow still leave a box on the side the layout intended.
     */
    private fun appendCurves(path: Path2D.Double, points: List<FlowPoint>) {
        val start = points.first()
        val end = points.last()
        val outward = unit(start, points[1])
        val inward = unit(points[points.size - 2], end)
        val reach = distance(start, end) * CURVE_REACH

        path.curveTo(
            start.x + outward.first * reach,
            start.y + outward.second * reach,
            end.x - inward.first * reach,
            end.y - inward.second * reach,
            end.x.toDouble(),
            end.y.toDouble(),
        )
    }

    private fun unit(from: FlowPoint, to: FlowPoint): Pair<Double, Double> {
        val length = distance(from, to)
        if (length == 0.0) return 0.0 to 0.0
        return (to.x - from.x) / length to (to.y - from.y) / length
    }

    private const val ON_SEGMENT_SLACK = 1.5

    /** How far the control points reach out. Enough to bend, not enough to overshoot the target. */
    private const val CURVE_REACH = 0.45
}
