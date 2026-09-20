package com.xmitya.ideadtf.toolwindow

import com.xmitya.ideadtf.DtfBundle

/**
 * What a row of the DTF Tasks tree is called.
 *
 * The one place that answers it. Two features need the answer and neither can take it from the
 * node: `toString()` on these user objects is `Object.toString()`, which is what speed search used
 * to match against (see [DtfTaskTreeSearchText]) and what the clipboard used to receive. The words
 * here are the ones [DtfTaskTreeRenderer] paints, minus the trailing task count - a name, not a row.
 */
internal object DtfTaskRowName {

    /** Null for anything the tree does not own. */
    fun of(userObject: Any?): String? = when (userObject) {
        is DtfTaskEntry -> userObject.displayName
        is DtfTaskModuleGroup -> userObject.moduleName ?: DtfBundle.message("dtf.toolwindow.module.none")
        is DtfTaskSnapshot -> userObject.projectName
        else -> null
    }
}
