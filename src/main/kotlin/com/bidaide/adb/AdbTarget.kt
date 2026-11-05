package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.projectsystem.getAndroidFacets
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import org.jetbrains.android.sdk.AndroidSdkUtils

/** Remembers the last device/app picked so repeated operations do not ask again. */
@Service(Service.Level.PROJECT)
class AdbSelectionMemory {
    /** A serial number, or [ALL_DEVICES]. */
    var deviceSerial: String? = null
    var packageName: String? = null

    companion object {
        const val ALL_DEVICES = "*"
    }
}

object AdbTargetResolver {

    /**
     * Must be called on the EDT. [onResolved] is invoked on the EDT once the devices (several when the user picks
     * "all devices") and the app are known.
     */
    fun resolve(project: Project, needsPackage: Boolean, onResolved: (List<IDevice>, String?) -> Unit) {
        val memory = project.service<AdbSelectionMemory>()
        chooseDevices(project, memory) { devices ->
            if (!needsPackage) {
                onResolved(devices, null)
            } else {
                choosePackage(project, memory) { packageName -> onResolved(devices, packageName) }
            }
        }
    }

    fun applicationIds(project: Project): List<String> =
        project.getAndroidFacets()
            .filter { it.configuration.isAppProject }
            .mapNotNull { AndroidModel.get(it)?.applicationId }
            .filter { it.isNotBlank() && it != AndroidModel.UNINITIALIZED_APPLICATION_ID }
            .distinct()

    fun onlineDevices(project: Project): List<IDevice> =
        AndroidSdkUtils.getDebugBridge(project)?.devices?.filter { it.isOnline }.orEmpty()

    /** A device entry of the chooser; null [device] stands for "all of them". */
    private class Choice(val device: IDevice?, val label: String)

    private fun chooseDevices(project: Project, memory: AdbSelectionMemory, onChosen: (List<IDevice>) -> Unit) {
        val devices = onlineDevices(project)
        val remembered = devices.firstOrNull { it.serialNumber == memory.deviceSerial }
        when {
            devices.isEmpty() -> Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.target.noDevices"))
            devices.size == 1 -> onChosen(devices)
            memory.deviceSerial == AdbSelectionMemory.ALL_DEVICES -> onChosen(devices)
            remembered != null -> onChosen(listOf(remembered))
            else -> {
                val choices = devices.map { Choice(it, deviceLabel(it)) } + Choice(null, BidaideBundle.message("adb.target.allDevices", devices.size))
                JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                    .setTitle(BidaideBundle.message("adb.target.chooseDevice"))
                    .setRenderer(SimpleListCellRenderer.create("") { it.label })
                    .setItemChosenCallback { choice ->
                        memory.deviceSerial = choice.device?.serialNumber ?: AdbSelectionMemory.ALL_DEVICES
                        onChosen(choice.device?.let(::listOf) ?: devices)
                    }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }
    }

    private fun choosePackage(project: Project, memory: AdbSelectionMemory, onChosen: (String) -> Unit) {
        val ids = applicationIds(project)
        val remembered = ids.firstOrNull { it == memory.packageName }
        when {
            ids.isEmpty() -> Notifier.warn(
                project, AndroidCli.TITLE, BidaideBundle.message("adb.target.noModules")
            )
            ids.size == 1 -> onChosen(ids.single())
            remembered != null -> onChosen(remembered)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(ids)
                .setTitle(BidaideBundle.message("adb.target.chooseApp"))
                .setItemChosenCallback {
                    memory.packageName = it
                    onChosen(it)
                }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    fun deviceLabel(device: IDevice): String =
        if (device.isEmulator) BidaideBundle.message("adb.target.emulator", device.name) else device.name
}
