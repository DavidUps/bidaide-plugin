package com.bidaide.adb

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Opens Android Device Hub (`android device hub`): a desktop window that mirrors and controls the device. The process
 * is not waited for with a progress bar, since the window can stay open for as long as the user wants; only a failure
 * is reported. The first run downloads the device-hub extension pack, so it can take a while.
 */
object DeviceHub {
    /** Must be called on the EDT: it may ask which device to use. */
    fun open(project: Project) {
        val android = AndroidCli.find()
            ?: return Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.deviceHub.needsCli"))
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            // With "all devices" the hub picks one itself and lets the user switch from its window.
            val serial = devices.singleOrNull()?.serialNumber
            val label = devices.singleOrNull()?.let(AdbTargetResolver::deviceLabel)
            val handler = try {
                OSProcessHandler(GeneralCommandLine(listOf(android) + AndroidCli.deviceHubArgs(serial)).withCharset(Charsets.UTF_8))
            } catch (e: Exception) {
                return@resolve Notifier.error(project, BidaideBundle.message("adb.deviceHub.failed"), e.message ?: e.javaClass.simpleName)
            }
            handler.addProcessListener(object : CapturingProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    if (event.exitCode == 0) return
                    val details = output.stderr.trim().ifEmpty { output.stdout.trim() }
                        .ifEmpty { BidaideBundle.message("adb.cli.exitCode", event.exitCode) }
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) Notifier.error(project, BidaideBundle.message("adb.deviceHub.failed"), details.take(MAX_OUTPUT))
                    }
                }
            })
            handler.startNotify()
            Notifier.info(
                project, AndroidCli.TITLE,
                label?.let { BidaideBundle.message("adb.deviceHub.opening", it) } ?: BidaideBundle.message("adb.deviceHub.openingAny"),
            )
        }
    }

    private const val MAX_OUTPUT = 1500
}

class AdbDeviceHubAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        DeviceHub.open(e.project ?: return)
    }
}
