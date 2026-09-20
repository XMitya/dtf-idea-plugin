package com.xmitya.ideadtf.flow.layout

import kotlin.math.max
import kotlin.math.min
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
