package com.xmitya.ideadtf.flow.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The value types the layout works in.
 *
 * Deliberately not `java.awt` ones: the layout is pure and unit-tested without a toolkit, and
 * borrowing `Rectangle` would drag mutability and a `Graphics`-shaped mindset into it. The canvas
 * converts at the boundary, which is also where scaling happens.
 */
data class FlowPoint(val x: Int, val y: Int)

data class FlowSize(val width: Int, val height: Int) {
    companion object {
        val EMPTY = FlowSize(0, 0)
    }
}

data class FlowRect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(point: FlowPoint): Boolean = point.x in x..right && point.y in y..bottom

    fun translated(dx: Int, dy: Int): FlowRect = copy(x = x + dx, y = y + dy)
}

/** Distance from [point] to the segment [from]-[to]; what edge hit testing is made of. */
internal fun distanceToSegment(point: FlowPoint, from: FlowPoint, to: FlowPoint): Double {
    val dx = (to.x - from.x).toDouble()
    val dy = (to.y - from.y).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypotenuse(point.x - from.x, point.y - from.y)

    val t = (((point.x - from.x) * dx + (point.y - from.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    val projectedX = from.x + t * dx
    val projectedY = from.y + t * dy
    return sqrt((point.x - projectedX) * (point.x - projectedX) + (point.y - projectedY) * (point.y - projectedY))
}

private fun hypotenuse(dx: Int, dy: Int): Double = sqrt((dx * dx + dy * dy).toDouble())

/**
 * Where two segments cross, if they properly cross at all.
 *
 * Null for parallel or collinear segments, and null when they only meet at an end - two arrows
 * leaving the same box share that point by construction, and marking it as a crossing would put a
 * hop on every fan-out.
 */
internal fun crossingOf(a1: FlowPoint, a2: FlowPoint, b1: FlowPoint, b2: FlowPoint): FlowPoint? {
    val ax = (a2.x - a1.x).toDouble()
    val ay = (a2.y - a1.y).toDouble()
    val bx = (b2.x - b1.x).toDouble()
    val by = (b2.y - b1.y).toDouble()

    val denominator = ax * by - ay * bx
    if (abs(denominator) < EPSILON) return null

    val t = ((b1.x - a1.x) * by - (b1.y - a1.y) * bx) / denominator
    val u = ((b1.x - a1.x) * ay - (b1.y - a1.y) * ax) / denominator
    if (t <= ENDPOINT_MARGIN || t >= 1 - ENDPOINT_MARGIN) return null
    if (u <= ENDPOINT_MARGIN || u >= 1 - ENDPOINT_MARGIN) return null

    return FlowPoint((a1.x + t * ax).roundToInt(), (a1.y + t * ay).roundToInt())
}

private const val EPSILON = 1e-9

/** How far from either end a crossing has to be before it counts as one rather than a meeting. */
private const val ENDPOINT_MARGIN = 1e-6

/**
 * Which way a flow runs.
 *
 * The arrangement is computed once, along an abstract "rank" axis, and only mapped onto x and y at
 * the very end - so a different direction is a different mapping rather than a different algorithm.
 */
enum class DtfFlowOrientation {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP,
    ;

    /** Whether ranks progress along x. The other two run down the page instead. */
    val isHorizontal: Boolean get() = this == LEFT_TO_RIGHT || this == RIGHT_TO_LEFT

    /** Whether the rank axis is mirrored, so rank 0 ends up at the far side. */
    val isReversed: Boolean get() = this == RIGHT_TO_LEFT || this == BOTTOM_TO_TOP
}

/**
 * The knobs the arrangement uses, in logical units.
 *
 * Scaling is the canvas's job, so nothing here knows about HiDPI - which is what keeps the whole
 * layout assertable against exact numbers in a test.
 *
 * @param rankGap horizontal distance between two columns. Wider than an arrowhead needs, because it
 *   is also the room a message-type or condition label will be given once those are drawn.
 * @param labelSlot the space reserved on an edge that has something to say. Computed today, painted
 *   later.
 */
data class DtfFlowLayoutStyle(
    val rankGap: Int = 96,
    val nodeGap: Int = 24,
    val labelSlot: FlowSize = FlowSize(120, 16),
    val dummyHeight: Int = 8,
    val orderingSweeps: Int = 4,
    val padding: Int = 16,
)

internal fun clampMin(value: Int, minimum: Int): Int = max(value, minimum)

internal fun highest(values: Collection<Int>): Int = values.maxOrNull() ?: 0

internal fun medianOf(values: List<Int>): Int = when {
    values.isEmpty() -> -1

    values.size % 2 == 1 -> values.sorted()[values.size / 2]

    else -> {
        val sorted = values.sorted()
        val left = sorted[values.size / 2 - 1]
        val right = sorted[values.size / 2]
        min(left, right) + (right - left) / 2
    }
}
