package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** Live logcat of the app's process only, in a tab of the Bidaide tool window. */
class AdbLogcatAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, packageName ->
            devices.forEach { AdbLogcat.open(project, it, packageName ?: return@resolve) }
        }
    }
}

object AdbLogcat {
    fun command(adb: String, serial: String, pid: String) =
        listOf(adb, "-s", serial, "logcat", "-v", "threadtime", "-v", "color", "--pid=$pid")

    fun open(project: Project, device: IDevice, packageName: String) {
        AdbTasks.fetch(
            project, BidaideBundle.message("adb.logcat.task", packageName),
            work = {
                // logcat filters by process id, so the app has to be running; a restarted app gets a new id.
                val pid = AdbShell.pidOf(device, packageName)
                    ?: throw AdbException(BidaideBundle.message("adb.logcat.notRunning", packageName, AdbTargetResolver.deviceLabel(device)))
                ColoredProcessHandler(GeneralCommandLine(command(AdbFiles.adbExecutable(project).path, device.serialNumber, pid)))
            },
        ) { handler -> show(project, handler, "Logcat: $packageName") }
    }

    private fun show(project: Project, handler: ProcessHandler, title: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Bidaide")
        if (toolWindow == null) {
            handler.destroyProcess()
            Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.logcat.noToolWindow"))
            return
        }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        val content = ContentFactory.getInstance().createContent(console.component, title, false)
        content.isCloseable = true
        Disposer.register(content, console)
        // Closing the tab stops the adb process behind it.
        Disposer.register(content) { handler.destroyProcess() }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        toolWindow.activate(null)
        handler.startNotify()
    }
}
