package com.xmitya.ideadtf.flow.action

import com.intellij.openapi.actionSystem.DataKey
import com.xmitya.ideadtf.flow.DtfFlowScope

object DtfFlowDataKeys {

    /**
     * What the selected row of the DTF Tasks tree would be a diagram of.
     *
     * One key rather than one per shape: the tree already knows whether a task or a module is
     * selected, and handing the answer over beats making the action rebuild it from a qualified name
     * and a module name.
     */
    val FLOW_SCOPE: DataKey<DtfFlowScope> = DataKey.create("com.xmitya.ideadtf.flowScope")
}
