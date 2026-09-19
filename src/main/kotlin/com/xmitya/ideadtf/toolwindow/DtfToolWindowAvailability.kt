package com.xmitya.ideadtf.toolwindow

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.xmitya.ideadtf.model.DtfTaskModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the stripe button in step with whether the project actually uses DTF.
 *
 * `ToolWindowFactory.shouldBeAvailable` is read once, during project open, while indexing is still
 * running - so on a cold start it always says "no". This re-asks as soon as the indexes are ready,
 * and again whenever the roots change, which is what makes the button appear by itself after the
 * first indexing pass and after someone adds the dependency.
 */
@Service(Service.Level.PROJECT)
class DtfToolWindowAvailability(private val project: Project, private val scope: CoroutineScope) {

    fun update(): Job = scope.launch {
        val available = smartReadAction(project) { DtfTaskModel.isDtfPresent(project) }
        val manager = ToolWindowManager.getInstance(project)
        // Not withContext(EDT): setAvailable also needs the tool window to exist, and invokeLater
        // queues until the manager has finished registering everything.
        manager.invokeLater { manager.getToolWindow(DtfTaskToolWindowFactory.ID)?.isAvailable = available }
    }

    companion object {
        fun getInstance(project: Project): DtfToolWindowAvailability = project.service()
    }
}

/** Wires [DtfToolWindowAvailability] up once per project. */
class DtfToolWindowActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val availability = DtfToolWindowAvailability.getInstance(project)
        availability.update()
        project.messageBus.connect().subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    availability.update()
                }
            },
        )
    }
}
