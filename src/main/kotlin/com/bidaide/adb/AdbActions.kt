package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.settings.BidaideSettings
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.JComponent

private val LOG = logger<AdbTasks>()

/** Runs ADB work in the background on one or several devices and reports each result as a notification. */
object AdbTasks {
    fun run(project: Project, title: String, devices: List<IDevice>, packageName: String?, work: (AdbContext) -> String) {
        object : Task.Backgroundable(project, "${AndroidCli.TITLE}: $title", false) {
            override fun run(indicator: ProgressIndicator) {
                for (device in devices) {
                    val context = AdbContext(project, device, packageName)
                    try {
                        report(project, "${work(context)} — ${AdbTargetResolver.deviceLabel(device)}", context.resultFile)
                    } catch (e: Exception) {
                        LOG.info("ADB task '$title' failed", e)
                        Notifier.error(project, "${AndroidCli.TITLE}: $title", "${e.message ?: e.javaClass.simpleName} — ${AdbTargetResolver.deviceLabel(device)}")
                    }
                }
            }
        }.queue()
    }

    /** Runs [work] in the background and hands its result to [onSuccess] on the EDT; failures become notifications. */
    fun <T> fetch(project: Project, title: String, work: () -> T, onSuccess: (T) -> Unit) {
        object : Task.Backgroundable(project, "${AndroidCli.TITLE}: $title", false) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                result = runCatching(work)
            }

            override fun onFinished() {
                result?.onSuccess(onSuccess)?.onFailure {
                    LOG.info("ADB task '$title' failed", it)
                    Notifier.error(project, "${AndroidCli.TITLE}: $title", it.message ?: it.javaClass.simpleName)
                }
            }
        }.queue()
    }

    private fun report(project: Project, message: String, file: File?) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Bidaide")
            .createNotification(AndroidCli.TITLE, message, NotificationType.INFORMATION)
        if (file != null) {
            notification.addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
        }
        notification.notify(project)
    }
}

abstract class AdbBaseAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

abstract class AdbOperationAction(private val operation: AdbOperation) : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, operation.needsPackage) { devices, packageName ->
            AdbTasks.run(project, operation.label, devices, packageName, operation.run)
        }
    }
}

class AdbUninstallAction : AdbOperationAction(AdbOperation.UNINSTALL)
class AdbKillAction : AdbOperationAction(AdbOperation.KILL)
class AdbStartAction : AdbOperationAction(AdbOperation.START)
class AdbRestartAction : AdbOperationAction(AdbOperation.RESTART)
class AdbClearDataAction : AdbOperationAction(AdbOperation.CLEAR_DATA)
class AdbClearDataRestartAction : AdbOperationAction(AdbOperation.CLEAR_DATA_RESTART)
class AdbClearCacheAction : AdbOperationAction(AdbOperation.CLEAR_CACHE)
class AdbReinstallAction : AdbOperationAction(AdbOperation.REINSTALL)
class AdbStartDebugAction : AdbOperationAction(AdbOperation.START_DEBUG)
class AdbRestartDebugAction : AdbOperationAction(AdbOperation.RESTART_DEBUG)
class AdbClearDataRestartDebugAction : AdbOperationAction(AdbOperation.CLEAR_DATA_RESTART_DEBUG)
class AdbGrantPermissionsAction : AdbOperationAction(AdbOperation.GRANT_PERMISSIONS)
class AdbRevokePermissionsAction : AdbOperationAction(AdbOperation.REVOKE_PERMISSIONS)
class AdbRevokePermissionsRestartAction : AdbOperationAction(AdbOperation.REVOKE_PERMISSIONS_RESTART)
class AdbWifiOnAction : AdbOperationAction(AdbOperation.WIFI_ON)
class AdbWifiOffAction : AdbOperationAction(AdbOperation.WIFI_OFF)
class AdbMobileDataOnAction : AdbOperationAction(AdbOperation.MOBILE_DATA_ON)
class AdbMobileDataOffAction : AdbOperationAction(AdbOperation.MOBILE_DATA_OFF)
class AdbScreenshotAction : AdbOperationAction(AdbOperation.SCREENSHOT)
class AdbRecordAction : AdbOperationAction(AdbOperation.RECORD_TOGGLE)
class AdbDarkModeAction : AdbOperationAction(AdbOperation.DARK_MODE)
class AdbFontScaleAction : AdbOperationAction(AdbOperation.FONT_SCALE)
class AdbAnimationsAction : AdbOperationAction(AdbOperation.ANIMATIONS)
class AdbDontKeepActivitiesAction : AdbOperationAction(AdbOperation.DONT_KEEP_ACTIVITIES)
class AdbShowTouchesAction : AdbOperationAction(AdbOperation.SHOW_TOUCHES)
class AdbAirplaneModeAction : AdbOperationAction(AdbOperation.AIRPLANE_MODE)
class AdbBatteryLowAction : AdbOperationAction(AdbOperation.BATTERY_LOW)
class AdbDozeAction : AdbOperationAction(AdbOperation.DOZE_ON)
class AdbPowerResetAction : AdbOperationAction(AdbOperation.POWER_RESET)
class AdbKillInBackgroundAction : AdbOperationAction(AdbOperation.KILL_IN_BACKGROUND)
class AdbRotateAction : AdbOperationAction(AdbOperation.ROTATE)
class AdbAutoRotateAction : AdbOperationAction(AdbOperation.AUTO_ROTATE)
class AdbSlowNetworkAction : AdbOperationAction(AdbOperation.SLOW_NETWORK)
class AdbNormalNetworkAction : AdbOperationAction(AdbOperation.NORMAL_NETWORK)
class AdbPullDataAction : AdbOperationAction(AdbOperation.PULL_DATA)
class AdbBackupAction : AdbOperationAction(AdbOperation.BACKUP)

/** Recently used values of a dialog field, most recent first, stored per project. */
internal class History(private val project: Project, private val key: String) {
    fun values(): List<String> = PropertiesComponent.getInstance(project).getList(key).orEmpty()

    fun remember(value: String) {
        PropertiesComponent.getInstance(project).setList(key, (listOf(value) + values()).distinct().take(MAX))
    }

    private companion object {
        const val MAX = 15
    }
}

/** Launches a URL or custom-scheme link on the device, keeping a history of the ones used. */
class AdbDeepLinkAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val history = History(project, "Bidaide.Adb.DeepLinks")
        val combo = ComboBox(history.values().toTypedArray()).apply { isEditable = true }
        val onlyApp = JBCheckBox(BidaideBundle.message("adb.deepLink.onlyApp"), true)
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("adb.deepLink.title")
                setOKButtonText(BidaideBundle.message("adb.deepLink.ok"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row("URL:") { cell(combo).align(AlignX.FILL).comment(BidaideBundle.message("adb.deepLink.comment")) }
                row { cell(onlyApp) }
            }

            override fun getPreferredFocusedComponent() = combo

            override fun doValidate(): ValidationInfo? =
                if (combo.editor.item?.toString().isNullOrBlank()) ValidationInfo(BidaideBundle.message("adb.deepLink.empty"), combo) else null
        }
        if (!dialog.showAndGet()) return
        val url = combo.editor.item.toString().trim()
        history.remember(url)
        AdbTargetResolver.resolve(project, onlyApp.isSelected) { devices, packageName ->
            AdbTasks.run(project, "Deep link", devices, packageName) { ctx ->
                AdbShell.openDeepLink(ctx.device, url, packageName)
                BidaideBundle.message("adb.deepLink.done", url)
            }
        }
    }
}

/** Per-app language (Android 13+). */
class AdbAppLocaleAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val locales = Messages.showInputDialog(
            project, BidaideBundle.message("adb.appLocale.prompt"), BidaideBundle.message("adb.appLocale.title"), null,
        ) ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, packageName ->
            AdbTasks.run(project, BidaideBundle.message("adb.appLocale.title"), devices, packageName) { ctx ->
                AdbShell.setAppLocale(ctx.device, ctx.packageName, locales)
                if (locales.isBlank()) BidaideBundle.message("adb.appLocale.system", ctx.packageName) else BidaideBundle.message("adb.appLocale.set", ctx.packageName, locales.trim())
            }
        }
    }
}

/** Restores a snapshot made with "save app state". */
class AdbRestoreAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, packageName ->
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("tar").withTitle(BidaideBundle.message("adb.restore.chooserTitle", packageName))
            val start = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(AdbFiles.backupDir(packageName!!))
            val archive = FileChooser.chooseFile(descriptor, project, start) ?: return@resolve
            AdbTasks.run(project, BidaideBundle.message("adb.restore.task"), devices, packageName) { ctx ->
                AdbFiles.restore(ctx.device, ctx.packageName, VfsUtilCore.virtualToIoFile(archive))
                BidaideBundle.message("adb.restore.done", ctx.packageName, archive.name)
            }
        }
    }
}

/** `adb reverse`: lets the device reach a server running on this machine through localhost. */
class AdbReverseAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val history = History(project, "Bidaide.Adb.ReversePorts")
        val input = Messages.showInputDialog(
            project, BidaideBundle.message("adb.reverse.prompt"),
            "adb reverse", null, history.values().firstOrNull().orEmpty(), null,
        ) ?: return
        val ports = input.trim().split(':').map { it.trim() }
        if (input.isNotBlank() && (ports.size > 2 || ports.any { it.toIntOrNull() == null })) {
            Notifier.warn(project, AndroidCli.TITLE, BidaideBundle.message("adb.reverse.invalid", input))
            return
        }
        if (input.isNotBlank()) history.remember(input.trim())
        AdbTargetResolver.resolve(project, needsPackage = false) { devices, _ ->
            AdbTasks.run(project, "adb reverse", devices, null) { ctx ->
                if (input.isBlank()) {
                    AdbFiles.adb(project, "-s", ctx.device.serialNumber, "reverse", "--remove-all")
                    BidaideBundle.message("adb.reverse.removed")
                } else {
                    val devicePort = ports.first()
                    val localPort = ports.last()
                    AdbFiles.adb(project, "-s", ctx.device.serialNumber, "reverse", "tcp:$devicePort", "tcp:$localPort")
                    BidaideBundle.message("adb.reverse.done", devicePort, localPort)
                }
            }
        }
    }
}

/** Wireless debugging: pairs with the code shown on the device and connects. */
class AdbPairAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pairAddress = JBTextField()
        val code = JBTextField()
        val connectAddress = JBTextField()
        val dialog = object : DialogWrapper(project) {
            init {
                title = BidaideBundle.message("adb.pair.title")
                setOKButtonText(BidaideBundle.message("adb.pair.ok"))
                init()
            }

            override fun createCenterPanel(): JComponent = panel {
                row { comment(BidaideBundle.message("adb.pair.help")) }
                row(BidaideBundle.message("adb.pair.pairAddress")) { cell(pairAddress).align(AlignX.FILL) }
                row(BidaideBundle.message("adb.pair.code")) { cell(code).align(AlignX.FILL).comment(BidaideBundle.message("adb.pair.code.comment")) }
                row(BidaideBundle.message("adb.pair.connectAddress")) {
                    cell(connectAddress).align(AlignX.FILL).comment(BidaideBundle.message("adb.pair.connectAddress.comment"))
                }
            }

            override fun doValidate(): ValidationInfo? = when {
                pairAddress.text.isBlank() != code.text.isBlank() -> ValidationInfo(BidaideBundle.message("adb.pair.missingCode"), pairAddress)
                pairAddress.text.isBlank() && connectAddress.text.isBlank() -> ValidationInfo(BidaideBundle.message("adb.pair.missingAddress"), connectAddress)
                else -> null
            }
        }
        if (!dialog.showAndGet()) return
        object : Task.Backgroundable(project, BidaideBundle.message("adb.pair.progress"), false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val messages = ArrayList<String>()
                    if (pairAddress.text.isNotBlank()) messages += AdbFiles.adb(project, "pair", pairAddress.text.trim(), code.text.trim())
                    if (connectAddress.text.isNotBlank()) messages += AdbFiles.adb(project, "connect", connectAddress.text.trim())
                    Notifier.info(project, AndroidCli.TITLE, messages.joinToString("<br>"))
                } catch (ex: Exception) {
                    Notifier.error(project, BidaideBundle.message("adb.pair.error"), ex.message ?: ex.javaClass.simpleName)
                }
            }
        }.queue()
    }
}

/** A user-defined shell command from the settings. */
class CustomAdbCommand(val name: String, val command: String) {
    val needsPackage: Boolean get() = PACKAGE in command

    fun resolve(packageName: String?): String = command.replace(PACKAGE, packageName.orEmpty())

    companion object {
        const val PACKAGE = "{package}"

        /** One command per line as `Name = command`; blank lines and lines starting with # are skipped. */
        fun parse(text: String): List<CustomAdbCommand> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .map { CustomAdbCommand(it.substringBefore('=').trim(), it.substringAfter('=').trim()) }
            .filter { it.name.isNotEmpty() && it.command.isNotEmpty() }
            .toList()
    }
}

/** The commands the user defined in Settings, listed inline in the ADB popup. */
class AdbCustomCommandsGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        CustomAdbCommand.parse(BidaideSettings.getInstance().customAdbCommands).map { command ->
            object : AdbBaseAction() {
                init {
                    templatePresentation.text = "${AndroidCli.TITLE}: ${command.name}"
                    templatePresentation.description = command.command
                }

                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    AdbTargetResolver.resolve(project, command.needsPackage) { devices, packageName ->
                        AdbTasks.run(project, command.name, devices, packageName) { ctx ->
                            val output = AdbShell.run(ctx.device, command.resolve(packageName), timeoutSeconds = 60)
                            "${command.name}: ${output.ifEmpty { BidaideBundle.message("adb.custom.done") }.take(MAX_OUTPUT)}"
                        }
                    }
                }
            }
        }.toTypedArray()

    private companion object {
        const val MAX_OUTPUT = 1500
    }
}

/** Forgets the remembered device/app so the next operation asks again. */
class AdbForgetSelectionAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AdbSelectionMemory>().apply {
            deviceSerial = null
            packageName = null
        }
        Notifier.info(project, AndroidCli.TITLE, BidaideBundle.message("adb.forget.done"))
    }
}

/** Shows every ADB operation in a speed-searchable popup. */
class AdbPopupAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val group = ActionManager.getInstance().getAction(OPERATIONS_GROUP_ID) as ActionGroup
        JBPopupFactory.getInstance()
            .createActionGroupPopup("${AndroidCli.TITLE} — Bidaide", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showCenteredInCurrentWindow(project)
    }

    private companion object {
        const val OPERATIONS_GROUP_ID = "Bidaide.Adb.Operations"
    }
}
