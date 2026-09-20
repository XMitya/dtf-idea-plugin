package com.xmitya.ideadtf.flow.editor

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.xmitya.ideadtf.flow.layout.DtfFlowLayoutStyle
import com.xmitya.ideadtf.flow.layout.FlowSize
import java.awt.BasicStroke
import java.awt.Color

/**
 * Every pixel and every colour the diagram uses.
 *
 * Scaling happens here and nowhere else, which is what lets the layout stay in logical units and be
 * asserted against exact numbers. Colours go through [JBColor] throughout - a literal `Color` would
 * look wrong in half the themes the IDE ships with.
 */
object DtfFlowStyle {

    private const val ARROW_LENGTH = 9
    private const val ARROW_HALF_WIDTH = 4

    fun layoutStyle(): DtfFlowLayoutStyle = DtfFlowLayoutStyle(
        rankGap = JBUI.scale(88),
        nodeGap = JBUI.scale(22),
        labelSlot = FlowSize(JBUI.scale(120), JBUI.scale(16)),
        dummyHeight = JBUI.scale(8),
        padding = JBUI.scale(20),
    )

    fun horizontalPadding(): Int = JBUI.scale(12)

    fun verticalPadding(): Int = JBUI.scale(8)

    fun iconGap(): Int = JBUI.scale(6)

    fun cornerRadius(): Int = JBUI.scale(10)

    fun gatewaySize(): Int = JBUI.scale(30)

    fun arrowLength(): Int = JBUI.scale(ARROW_LENGTH)

    fun arrowHalfWidth(): Int = JBUI.scale(ARROW_HALF_WIDTH)

    fun edgeHitTolerance(): Int = JBUI.scale(5)

    /** The bridge an arrow makes over one it crosses. Big enough to read, small enough not to blur. */
    fun hopRadius(): Int = JBUI.scale(4)

    val boxBackground: JBColor = JBColor.namedColor("Tree.background", JBColor(0xFFFFFF, 0x3C3F41))
    val boxBorder: JBColor = JBColor.namedColor("Component.borderColor", JBColor(0xC4C4C4, 0x5E6060))
    val hoverBackground: JBColor = JBColor.namedColor("Table.hoverBackground", JBColor(0xEDF5FC, 0x464A4D))
    val edgeColor: JBColor = JBColor.namedColor("Component.infoForeground", JBColor(0x8C8C8C, 0xA1A3A5))
    val canvasBackground: JBColor = JBColor.namedColor("Editor.background", JBColor(0xFFFFFF, 0x2B2B2B))
    val joinAccent: JBColor = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x548AF7))

    val greyText: Color get() = UIUtil.getInactiveTextColor()

    fun edgeStroke(): BasicStroke = BasicStroke(JBUI.scale(1).toFloat())

    /** A guess is drawn as a guess: an inferred join and a detached fork both read as "not stated". */
    fun dashedStroke(): BasicStroke = BasicStroke(
        JBUI.scale(1).toFloat(),
        BasicStroke.CAP_BUTT,
        BasicStroke.JOIN_MITER,
        JBUI.scale(4).toFloat(),
        floatArrayOf(JBUI.scale(4).toFloat(), JBUI.scale(3).toFloat()),
        0f,
    )
}
