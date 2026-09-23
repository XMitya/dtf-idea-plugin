package com.xmitya.ideadtf.flow.layout

import kotlin.math.sqrt

/**
 * Where each independent flow goes on the canvas.
 *
 * A module holds dozens of flows that share nothing, and giving them one shared set of ranks is what
 * turned such a diagram into a column of fifty tasks with their callers somewhere else entirely. Each
 * flow is therefore arranged on its own and the finished pictures are tiled: rows of them, filled
 * left to right, towards the proportions of a screen rather than one long strip.
 */
internal object DtfFlowPacking {

    /**
     * The top-left corner of each flow, in the order given, with the first row starting at (0, 0).
     *
     * Next-fit by decreasing height, the tallest first: simple, deterministic, and wasting little
     * room when the flows are of similar size, as a module's mostly are.
     */
    fun shelvesOf(sizes: List<FlowSize>, gap: Int, aspect: Double): List<FlowPoint> {
        if (sizes.size <= 1) return sizes.map { FlowPoint(0, 0) }

        val area = sizes.sumOf { (it.width + gap).toDouble() * (it.height + gap) }
        val targetWidth = maxOf(sizes.maxOf { it.width }.toDouble(), sqrt(area * aspect))
        val corners = arrayOfNulls<FlowPoint>(sizes.size)
        var x = 0
        var y = 0
        var shelfHeight = 0
        for (index in sizes.indices.sortedWith(compareBy({ -sizes[it].height }, { it }))) {
            val size = sizes[index]
            if (x > 0 && x + size.width > targetWidth) {
                x = 0
                y += shelfHeight + gap
                shelfHeight = 0
            }
            corners[index] = FlowPoint(x, y)
            x += size.width + gap
            shelfHeight = maxOf(shelfHeight, size.height)
        }
        return corners.map { requireNotNull(it) }
    }

    /**
     * [shelvesOf], padded and turned to the way the flows read: the tiling starts wherever reading
     * starts, so right to left fills rows from the right and bottom to top stacks them upwards.
     */
    fun packed(sizes: List<FlowSize>, style: DtfFlowLayoutStyle, orientation: DtfFlowOrientation): List<FlowPoint> {
        val corners = shelvesOf(sizes, style.componentGap, style.packAspect)
        val width = sizes.indices.maxOfOrNull { corners[it].x + sizes[it].width } ?: 0
        val height = sizes.indices.maxOfOrNull { corners[it].y + sizes[it].height } ?: 0
        return corners.mapIndexed { index, corner ->
            val x = if (orientation == DtfFlowOrientation.RIGHT_TO_LEFT) width - corner.x - sizes[index].width else corner.x
            val y = if (orientation == DtfFlowOrientation.BOTTOM_TO_TOP) height - corner.y - sizes[index].height else corner.y
            FlowPoint(x + style.padding, y + style.padding)
        }
    }
}
